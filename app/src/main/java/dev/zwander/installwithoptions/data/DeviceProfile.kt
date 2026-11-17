package dev.zwander.installwithoptions.data

import android.os.Build

/**
 * Targeted defaults for the Korean Galaxy A17 (SM-A175N) running Android 17.
 * The ROM's One UI version isn't inferred from its Android API level.
 */
object DeviceProfile {
    const val MODEL = "SM-A175N"
    const val ANDROID_API = 37

    fun matches(manufacturer: String, model: String, sdk: Int): Boolean =
        manufacturer.equals("samsung", ignoreCase = true) &&
            model.equals(MODEL, ignoreCase = true) &&
            sdk == ANDROID_API

    val isExactMatch: Boolean
        get() = matches(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT)

    val description: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL} · Android API ${Build.VERSION.SDK_INT}"

    /** Avoids aggressive allocation, verifier bypass and destructive downgrade flags. */
    fun safeOptions(): List<InstallOption> = listOf(InstallOption.ReplaceExisting)

    /** An explicitly selected preset for development/legacy APKs, not a default. */
    fun legacyOptions(): List<InstallOption> =
        listOf(
            InstallOption.ReplaceExisting,
            InstallOption.AllowTest,
            InstallOption.BypassLowTargetSdkBlock,
        )
}
