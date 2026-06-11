package com.ivy.sms.data

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-line build identity for SmsTrace captures:
 * `<versionName>(<versionCode>) installed=<lastUpdateTimeEpochMillis>`.
 * lastUpdateTime changes on every install, so two dev builds sharing a
 * versionName stay distinguishable — log forensics previously could not tell
 * which build produced a capture (the 18:08 "post-fixes" smoke log turned out
 * to predate the build it was supposed to validate).
 */
@Singleton
class BuildFingerprintProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val fingerprint: String by lazy {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = PackageInfoCompat.getLongVersionCode(info)
            "${info.versionName}($code) installed=${info.lastUpdateTime}"
        }.getOrElse { "unknown" }
    }
}
