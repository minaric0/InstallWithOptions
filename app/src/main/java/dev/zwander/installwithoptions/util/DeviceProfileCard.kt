package dev.zwander.installwithoptions.util

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Read-only status for the Korean Galaxy A17 4G profile.
 * Does not claim shell capabilities that Samsung or Android may restrict.
 */
@Composable
fun DeviceProfileCard() {
    val context = LocalContext.current
    val connected = LocalShellInterface.current != null
    val compatible = Build.MODEL.equals("SM-A175N", ignoreCase = true) &&
        Build.VERSION.SDK_INT == 37
    val managerPackage = context.getShizukuPackageName()
    val managerVersion = remember(managerPackage) {
        managerPackage?.let { pkg ->
            runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(pkg, 0).versionName
            }.getOrNull()
        }
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "SM-A175N · One UI 9 · Android 17",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Detected: " + Build.MODEL + " · API " + Build.VERSION.SDK_INT +
                    " · " + Build.DISPLAY,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Privileged installer: " + if (connected) "connected" else "not connected",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!compatible) {
                Text(
                    "Device differs from the optimized SM-A175N / API 37 profile.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (managerVersion == "13.6.0") {
                Text(
                    "Shizuku 13.6.0 can fail on MediaTek. Use a fixed compatible fork " +
                        "or Shizuku 13.5.4.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
