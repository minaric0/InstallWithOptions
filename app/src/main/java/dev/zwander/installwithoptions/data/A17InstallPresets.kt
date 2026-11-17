package dev.zwander.installwithoptions.data

import android.os.Build

/**
 * Conservative one-tap presets. No preset disables package verification,
 * grants runtime permissions, modifies all users, or requests downgrades.
 */
enum class A17InstallPreset {
    SAFE,
    LEGACY,
    TEST
}

object A17InstallPresets {
    val isTargetDevice: Boolean
        get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            Build.MODEL.equals("SM-A175N", ignoreCase = true) &&
            Build.VERSION.SDK_INT == 37

    fun optionsFor(preset: A17InstallPreset): List<InstallOption> {
        val common = listOf(InstallOption.ReplaceExisting)
        return when (preset) {
            A17InstallPreset.SAFE -> common
            A17InstallPreset.LEGACY -> common + listOf(
                InstallOption.AllowTest,
                InstallOption.BypassLowTargetSdkBlock,
            )
            A17InstallPreset.TEST -> common + listOf(
                InstallOption.AllowTest,
                InstallOption.FromAdb,
            )
        }.filter { Build.VERSION.SDK_INT in it.minSdk..it.maxSdk }
    }
}
