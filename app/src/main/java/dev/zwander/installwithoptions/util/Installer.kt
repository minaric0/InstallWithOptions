package dev.zwander.installwithoptions.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.res.AssetFileDescriptor
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatTextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.text.HtmlCompat
import androidx.documentfile.provider.DocumentFile
import dev.zwander.installwithoptions.BuildConfig
import dev.zwander.installwithoptions.IErrorCallback
import dev.zwander.installwithoptions.IOptionsApplier
import dev.zwander.installwithoptions.R
import dev.zwander.installwithoptions.data.DataModel
import dev.zwander.installwithoptions.data.InstallResult
import dev.zwander.installwithoptions.data.InstallStatus
import dev.zwander.installwithoptions.data.MutableOption
import dev.zwander.installwithoptions.data.Settings
import dev.zwander.installwithoptions.data.getMutableOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException

const val INSTALL_STATUS_ACTION =
    "${BuildConfig.APPLICATION_ID}.intent.action.INSTALL_STATUS"

data class Installer(
    val install: () -> Unit,
    val isInstalling: Boolean,
    val totalPackages: Int,
    val completed: Int,
)

@Composable
fun rememberPackageInstaller(files: Map<String, List<DocumentFile>>): Installer {
    val context = LocalContext.current
    val resources = LocalResources.current
    val shellInterface by rememberUpdatedState(LocalShellInterface.current)
    val updatedFiles by rememberUpdatedState(files)

    val scope = rememberCoroutineScope()
    val permissionStarter =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.StartActivityForResult()) {
            Log.e("InstallWithOptions", "permission result ${Settings.gson.toJson(it)}")
        }

    var statuses by remember {
        mutableStateOf<List<InstallResult>>(listOf())
    }
    var isInstalling by remember {
        mutableStateOf(false)
    }
    var expectedPackages by remember {
        mutableStateOf(setOf<String>())
    }

    val options by DataModel.selectedOptions.collectAsState()
    val updatedOptions by rememberUpdatedState(options)

    val applier = remember {
        object : IOptionsApplier.Stub() {
            override fun applyOptions(params: PackageInstaller.SessionParams): PackageInstaller.SessionParams {
                getMutableOptions().forEach { it.apply(params) }

                return params
            }
        }
    }

    val receiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == INSTALL_STATUS_ACTION) {
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
                    val specificMessage = context.getSpecificErrorMessage(
                        legacyStatus = intent.getIntExtra("android.content.pm.extra.LEGACY_STATUS", Int.MIN_VALUE),
                    )
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                    val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                        ?.takeIf { it.isNotBlank() }
                        ?: intent.getStringExtra(REQUESTED_PACKAGE_EXTRA).orEmpty()

                    when (status) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            val requestIntent = IntentCompat.getParcelableExtra(
                                intent,
                                Intent.EXTRA_INTENT,
                                Intent::class.java,
                            )

                            if (requestIntent != null) {
                                permissionStarter.launch(requestIntent)
                            } else {
                                statuses = statuses.toMutableList().apply {
                                    removeIf { it.packageName == packageName }
                                } + InstallResult(
                                    status = InstallStatus.FAILURE,
                                    packageName = packageName,
                                    message = context.resources.getString(R.string.permission_intent_was_null),
                                    specificMessage = specificMessage,
                                )
                            }
                        }

                        PackageInstaller.STATUS_SUCCESS -> {
                            statuses = statuses.toMutableList().apply {
                                removeIf { it.packageName == packageName }
                            } + InstallResult(
                                status = InstallStatus.SUCCESS,
                                packageName = packageName,
                                message = context.resources.getString(R.string.success),
                                specificMessage = specificMessage,
                            )
                        }

                        else -> {
                            statuses = statuses.toMutableList().apply {
                                removeIf { it.packageName == packageName }
                            } + InstallResult(
                                status = InstallStatus.FAILURE,
                                packageName = packageName,
                                message = message,
                                specificMessage = specificMessage,
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(statuses, expectedPackages) {
        if (expectedPackages.isNotEmpty() &&
            statuses.map { it.packageName }.toSet().containsAll(expectedPackages)
        ) {
            isInstalling = false
        }
    }

    DisposableEffect(key1 = null) {
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(INSTALL_STATUS_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    statuses.takeIf {
        it.isNotEmpty() && expectedPackages.isNotEmpty() &&
            it.map { result -> result.packageName }.toSet().containsAll(expectedPackages)
    }?.let { s ->
        AlertDialog(
            onDismissRequest = { statuses = listOf() },
            confirmButton = {
                TextButton(onClick = { statuses = listOf() }) {
                    Text(text = stringResource(id = android.R.string.ok))
                }
            },
            title = {
                Text(
                    text = stringResource(
                        id = if (s.any { it.status == InstallStatus.FAILURE }) {
                            R.string.installation_errors
                        } else {
                            R.string.installation_complete
                        },
                    ),
                )
            },
            text = {
                SelectionContainer {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(items = s) { res ->
                            AndroidView(
                                factory = { AppCompatTextView(it) },
                            ) { tv ->
                                tv.text = HtmlCompat.fromHtml(
                                    resources.getString(
                                        if (res.specificMessage != null) {
                                            R.string.status_item_extra_message
                                        } else {
                                            R.string.status_item
                                        },
                                        try {
                                            context.packageManager.getApplicationInfo(res.packageName, 0)
                                                .loadLabel(context.packageManager).toString()
                                        } catch (_: Throwable) {
                                            res.packageName
                                        },
                                        res.message,
                                        res.specificMessage,
                                    ),
                                    HtmlCompat.FROM_HTML_MODE_COMPACT,
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    return Installer(
        install = remember {
            {
                val backend = shellInterface
                val installFiles = updatedFiles
                if (backend == null || isInstalling || installFiles.isEmpty()) {
                    if (backend == null) {
                        android.widget.Toast.makeText(
                            context,
                            R.string.a17_backend_not_ready,
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                } else {
                    statuses = emptyList()
                    expectedPackages = installFiles.keys
                    isInstalling = true

                    scope.launch(Dispatchers.IO) {
                        val openedDescriptors = mutableListOf<AssetFileDescriptor>()
                        try {
                            val descriptors = installFiles.mapValues { (_, documentFiles) ->
                                documentFiles.map { file ->
                                    (context.contentResolver.openAssetFileDescriptor(file.uri, "r")
                                        ?: throw IOException("Cannot read ${file.uri}")).also {
                                        openedDescriptors.add(it)
                                    }
                                }
                            }
                            backend.install(
                                descriptors,
                                updatedOptions.map { it.value }.toIntArray(),
                                applier,
                                MutableOption.InstallerPackage.settingsKey.getValue(),
                                MutableOption.TargetUser.settingsKey.getValue(),
                                object : IErrorCallback.Stub() {
                                    override fun onError(error: String?, errorClass: String?) {
                                        SafeBugsnag.leaveBreadcrumb("$errorClass: $error")
                                        statuses = installFiles.keys.map { pkg ->
                                            InstallResult(
                                                status = InstallStatus.FAILURE,
                                                packageName = pkg,
                                                message = "$errorClass: $error",
                                                specificMessage = null,
                                            )
                                        }
                                    }
                                },
                            )
                        } catch (e: Exception) {
                            statuses = installFiles.keys.map { pkg ->
                                InstallResult(
                                    status = InstallStatus.FAILURE,
                                    packageName = pkg,
                                    message = e.extractErrorMessage() ?: e.toString(),
                                    specificMessage = null,
                                )
                            }
                        } finally {
                            openedDescriptors.forEach { fd ->
                                runCatching { fd.close() }
                            }
                        }
                    }
                }
            }
        },
        isInstalling = isInstalling,
        totalPackages = updatedFiles.size,
        completed = statuses.size,
    )
}
