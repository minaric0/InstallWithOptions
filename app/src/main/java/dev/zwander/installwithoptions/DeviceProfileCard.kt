package dev.zwander.installwithoptions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import android.security.advancedprotection.AdvancedProtectionManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zwander.installwithoptions.data.InstallOption
import dev.zwander.installwithoptions.util.LocalShellInterface

/**
 * Device-scoped presets, not privileged bypasses. The firmware must still accept
 * every requested installation; notably, signatures and native ABIs are enforced.
 */
internal object A175nProfile {
    const val MODEL = "SM-A175N"
    const val ANDROID_17_API = 37

    val matchesDevice: Boolean
        get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            Build.MODEL.equals(MODEL, ignoreCase = true) &&
            Build.VERSION.SDK_INT == ANDROID_17_API

    fun standard(): List<InstallOption> = listOf(InstallOption.ReplaceExisting)

    fun testApk(): List<InstallOption> =
        listOf(InstallOption.ReplaceExisting, InstallOption.AllowTest)

    fun legacyTarget(): List<InstallOption> =
        listOf(InstallOption.ReplaceExisting, InstallOption.BypassLowTargetSdkBlock)
}

@Composable
internal fun DeviceProfileCard(
    enabled: Boolean,
    onApplyPreset: (List<InstallOption>) -> Unit,
) {
    val matches = A175nProfile.matchesDevice
    val connected = LocalShellInterface.current != null
    val context = LocalContext.current
    val advancedProtectionEnabled = Build.VERSION.SDK_INT >= 36 && runCatching {
        context.getSystemService(AdvancedProtectionManager::class.java)
            ?.isAdvancedProtectionEnabled == true
    }.getOrDefault(false)
    val availableBytes = runCatching {
        StatFs(Environment.getDataDirectory().path).availableBytes
    }.getOrDefault(-1L)
    val supportedAbis = Build.SUPPORTED_ABIS.joinToString(", ")
    val storageLabel = if (availableBytes >= 0L) {
        Formatter.formatShortFileSize(context, availableBytes)
    } else {
        "unknown"
    }
    val diagnostics = listOf(
        "Manufacturer: ${Build.MANUFACTURER}",
        "Model: ${Build.MODEL}",
        "Android API: ${Build.VERSION.SDK_INT}",
        "Build: ${Build.DISPLAY}",
        "Security patch: ${Build.VERSION.SECURITY_PATCH}",
        "Supported ABIs: $supportedAbis",
        "Free internal storage: $storageLabel",
        "Elevated service connected: $connected",
        "Advanced Protection enabled: $advancedProtectionEnabled",
    ).joinToString("\n")

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.a175n_profile_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    R.string.a175n_profile_device,
                    Build.MODEL,
                    Build.VERSION.SDK_INT,
                    Build.DISPLAY,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    if (connected) R.string.a175n_service_ready else R.string.a175n_service_missing,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.a175n_profile_abis, supportedAbis),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.a175n_profile_storage, storageLabel),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.a175n_profile_patch, Build.VERSION.SECURITY_PATCH),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText("A175N install diagnostics", diagnostics))
            }) {
                Text(stringResource(R.string.a175n_copy_diagnostics))
            }
            if (matches) {
                Text(
                    text = stringResource(R.string.a175n_auto_blocker_warning),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (advancedProtectionEnabled) {
                Text(
                    text = stringResource(R.string.a175n_advanced_protection_warning),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (matches) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    TextButton(
                        enabled = enabled,
                        onClick = { onApplyPreset(A175nProfile.standard()) },
                    ) { Text(stringResource(R.string.a175n_preset_standard)) }
                    TextButton(
                        enabled = enabled,
                        onClick = { onApplyPreset(A175nProfile.testApk()) },
                    ) { Text(stringResource(R.string.a175n_preset_test)) }
                    TextButton(
                        enabled = enabled,
                        onClick = { onApplyPreset(A175nProfile.legacyTarget()) },
                    ) { Text(stringResource(R.string.a175n_preset_legacy)) }
                }
                Text(
                    text = stringResource(R.string.a175n_profile_caveat),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text = stringResource(R.string.a175n_profile_mismatch),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
