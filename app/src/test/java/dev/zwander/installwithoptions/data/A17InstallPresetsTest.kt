package dev.zwander.installwithoptions.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class A17InstallPresetsTest {
    @Test
    fun safePresetDoesNotRequestPrivilegedFlags() {
        val flags = A17InstallPresets.optionsFor(A17InstallPreset.SAFE, sdk = 37)
        assertEquals(listOf(InstallOption.ReplaceExisting), flags)
        assertFalse(flags.contains(InstallOption.DisableVerification))
        assertFalse(flags.contains(InstallOption.GrantAllRequestedPermissions))
        assertFalse(flags.contains(InstallOption.AllowDowngrade))
    }

    @Test
    fun legacyPresetIsSdkGated() {
        val recent = A17InstallPresets.optionsFor(A17InstallPreset.LEGACY, sdk = 37)
        assertTrue(recent.contains(InstallOption.BypassLowTargetSdkBlock))
        val older = A17InstallPresets.optionsFor(A17InstallPreset.LEGACY, sdk = 33)
        assertFalse(older.contains(InstallOption.BypassLowTargetSdkBlock))
    }

    @Test
    fun testPresetNeverDisablesVerification() {
        val flags = A17InstallPresets.optionsFor(A17InstallPreset.TEST, sdk = 37)
        assertTrue(flags.contains(InstallOption.AllowTest))
        assertTrue(flags.contains(InstallOption.FromAdb))
        assertFalse(flags.contains(InstallOption.DisableVerification))
    }
}
