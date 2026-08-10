package com.sam.openclone.clone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.sam.openclone.R

/**
 * Receives the outcome of a clone's install session.
 *
 * The interesting case is [PackageInstaller.STATUS_PENDING_USER_ACTION]: an
 * unprivileged installer cannot install silently, so the system hands back an
 * intent that must be shown for the user to approve. That intent is an
 * activity, and launching one from the background is blocked, so it goes out as
 * a notification whenever the app is not on screen.
 */
internal class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_STATUS) return
        val label = intent.getStringExtra(EXTRA_CLONE_LABEL).orEmpty()

        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = intent.getParcelableExtra(
                    Intent.EXTRA_INTENT, Intent::class.java,
                ) ?: return
                promptToInstall(context, label, confirmation)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                CloneNotifications.cancelInstallPrompt(context)
                CloneCoordinator.onFinished(
                    context.getString(R.string.clone_installed, label),
                    success = true,
                )
                CloneNotifications.result(
                    context,
                    context.getString(R.string.clone_installed_title),
                    context.getString(R.string.clone_installed_body, label),
                )
            }

            else -> {
                CloneNotifications.cancelInstallPrompt(context)
                val reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?: context.getString(R.string.install_cancelled)
                CloneCoordinator.onFinished(
                    context.getString(R.string.clone_failed, reason),
                    success = false,
                )
            }
        }
    }

    private fun promptToInstall(context: Context, label: String, confirmation: Intent) {
        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (CloneCoordinator.uiVisible) {
            // Foreground: show the prompt straight away rather than making the
            // user go find a notification.
            val started = runCatching { context.startActivity(confirmation) }.isSuccess
            if (started) return
        }
        CloneNotifications.installPrompt(context, label, confirmation)
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.sam.openclone.INSTALL_STATUS"
        const val EXTRA_CLONE_LABEL = "clone_label"
    }
}
