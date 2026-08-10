package com.sam.openclone.clone

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.SystemClock
import com.sam.openclone.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

private const val EXTRA_PACKAGE = "package"
private const val EXTRA_LABEL = "label"
private const val EXTRA_PATHS = "paths"

/** Notification refresh interval; anything faster is throttled by the system. */
private const val NOTIFICATION_INTERVAL_MS = 250L

/**
 * Runs a clone to completion in the background.
 *
 * Rewriting and signing a large app takes long enough that the activity may be
 * gone before it finishes, and the work must not die with it — a half-written
 * installer session would leave the user with nothing. A foreground service
 * keeps the process alive and gives the copy a visible progress notification.
 */
internal class CloneService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val packageName = intent?.getStringExtra(EXTRA_PACKAGE)
        val label = intent?.getStringExtra(EXTRA_LABEL)
        val paths = intent?.getStringArrayListExtra(EXTRA_PATHS)
        if (packageName == null || label == null || paths.isNullOrEmpty()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Must happen before anything slow, or the system kills the service for
        // failing to post its notification in time.
        CloneNotifications.ensureChannels(this)
        startForeground(CloneNotifications.PROGRESS_ID, CloneNotifications.progress(this, label, 0f))

        scope.launch {
            try {
                clone(packageName, label, paths)
            } catch (t: Throwable) {
                fail(t.message ?: t.javaClass.simpleName)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun clone(originalPackage: String, label: String, paths: List<String>) {
        val identity = CloneKeyStore.identity(this)
        val name = CloneNaming.allocate(originalPackage, AppRepository.installedPackages(this))
        val cloneLabel = name.label(label)

        val totalBytes = paths.sumOf { File(it).length() }.coerceAtLeast(1L)
        var completedBytes = 0L
        var lastNotified = 0L

        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(name.packageName)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setSize(totalBytes)
        }

        val sessionId = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                paths.forEachIndexed { index, path ->
                    // Split APKs each carry their own manifest with the same
                    // package name, so every one of them has to be rewritten
                    // and they must all go into a single session.
                    val entryName = if (index == 0) "base.apk" else "split$index.apk"
                    session.openWrite(entryName, 0, -1).use { out ->
                        com.sam.openclone.apk.ApkCloner.clone(
                            sourcePath = path,
                            clonePackage = name.packageName,
                            cloneLabel = cloneLabel,
                            identity = identity,
                            out = out,
                        ) { written ->
                            val fraction = (completedBytes + written).toFloat() / totalBytes
                            CloneCoordinator.onProgress(fraction.coerceIn(0f, 1f))
                            val now = SystemClock.uptimeMillis()
                            if (now - lastNotified >= NOTIFICATION_INTERVAL_MS) {
                                lastNotified = now
                                updateProgressNotification(label, fraction)
                            }
                        }
                        session.fsync(out)
                    }
                    completedBytes += File(path).length()
                }

                CloneCoordinator.onAwaitingConfirmation(getString(R.string.install_pending))
                updateProgressNotification(label, 1f)
                session.commit(statusIntent(sessionId, cloneLabel).intentSender)
                committed = true
            }
        } finally {
            // An abandoned session leaks staged storage until reboot.
            if (!committed) runCatching { installer.abandonSession(sessionId) }
        }
    }

    private fun statusIntent(sessionId: Int, cloneLabel: String): PendingIntent {
        val intent = Intent(this, InstallResultReceiver::class.java)
            .setAction(InstallResultReceiver.ACTION_INSTALL_STATUS)
            .putExtra(InstallResultReceiver.EXTRA_CLONE_LABEL, cloneLabel)
        return PendingIntent.getBroadcast(
            this,
            sessionId,
            intent,
            // Mutable: the installer fills in the status extras on delivery.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private fun updateProgressNotification(label: String, fraction: Float) {
        runCatching {
            startForeground(
                CloneNotifications.PROGRESS_ID,
                CloneNotifications.progress(this, label, fraction),
            )
        }
    }

    private fun fail(reason: String) {
        CloneCoordinator.onFinished(getString(R.string.clone_failed, reason), success = false)
        CloneNotifications.result(this, getString(R.string.clone_failed_title), reason)
    }

    companion object {
        /** Starts a clone, unless one is already running. */
        fun start(context: Context, app: InstalledApp) {
            if (!CloneCoordinator.tryClaim(app.packageName, app.label)) return
            val intent = Intent(context, CloneService::class.java)
                .putExtra(EXTRA_PACKAGE, app.packageName)
                .putExtra(EXTRA_LABEL, app.label)
                .putStringArrayListExtra(EXTRA_PATHS, ArrayList(app.apkPaths))
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                // Nothing will arrive to clear the claim if the service never
                // came up, so hand the slot back here.
                CloneCoordinator.release()
            }
        }
    }
}
