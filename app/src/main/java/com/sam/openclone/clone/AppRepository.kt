package com.sam.openclone.clone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator

/** An installed, launchable app that can be cloned. */
internal class InstalledApp(
    val packageName: String,
    val label: String,
    /** Base APK first, then any config splits; all of them must be cloned. */
    val apkPaths: List<String>,
    val versionName: String?,
    val sizeBytes: Long,
    val isClone: Boolean,
) {
    /** Pre-folded once so filtering never re-lowercases on every keystroke. */
    val searchLabel: String = label.lowercase()
    val searchPackage: String = packageName.lowercase()
}

/**
 * Lists the apps worth showing.
 *
 * Discovery goes through a launcher-intent query rather than
 * `getInstalledApplications`, which needs QUERY_ALL_PACKAGES on API 30+. The
 * launcher query returns exactly the set a cloner cares about — apps with an
 * entry point the user can actually open — and needs only a `<queries>` filter.
 */
internal object AppRepository {

    // Reading every app's label costs a second or so on a full device. Holding
    // the result means a rotation or a return to the screen is instant, and it
    // is only rebuilt when a clone actually changes what is installed.
    @Volatile
    private var cached: List<InstalledApp>? = null

    suspend fun load(context: Context, refresh: Boolean = false): List<InstalledApp> {
        if (!refresh) cached?.let { return it }
        return query(context).also { cached = it }
    }

    private suspend fun query(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(
            launcherIntent,
            PackageManager.ResolveInfoFlags.of(0L),
        )

        val seen = HashSet<String>(resolved.size)
        val apps = ArrayList<InstalledApp>(resolved.size)
        for (info in resolved) {
            val appInfo = info.activityInfo?.applicationInfo ?: continue
            val packageName = appInfo.packageName
            // An app may publish several launcher entries; it is still one app.
            if (packageName == context.packageName || !seen.add(packageName)) continue

            val paths = buildList {
                appInfo.sourceDir?.let { add(it) }
                appInfo.splitSourceDirs?.let { addAll(it) }
            }
            if (paths.isEmpty()) continue

            apps.add(
                InstalledApp(
                    packageName = packageName,
                    label = appInfo.loadLabel(pm).toString(),
                    apkPaths = paths,
                    versionName = runCatching {
                        pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
                            .versionName
                    }.getOrNull(),
                    sizeBytes = paths.sumOf { File(it).length() },
                    isClone = CloneNaming.isClone(packageName),
                )
            )
        }

        // Collator so accented names sort where a user expects them to.
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        apps.sortWith { a, b -> collator.compare(a.label, b.label) }
        apps
    }

    /** Every package on the device, used to pick a free clone name. */
    fun installedPackages(context: Context): Set<String> {
        val pm = context.packageManager
        val result = HashSet<String>()
        pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            PackageManager.ResolveInfoFlags.of(0L),
        ).mapTo(result) { it.activityInfo.applicationInfo.packageName }
        return result
    }
}
