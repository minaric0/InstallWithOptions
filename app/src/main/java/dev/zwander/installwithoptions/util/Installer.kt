package dev.zwander.installwithoptions.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetFileDescriptor
import android.content.pm.PackageInstaller
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
import java.util.UUID

const val INSTALL_STATUS_ACTION =
    "${BuildConfig.APPLICATION_ID}.intent.action.INSTALL_STATUS"
const val INSTALL_STATUS_TOKEN_EXTRA = "${BuildConfig.APPLICATION_ID}.extra.STATUS_TOKEN"
const val INSTALL_REQUESTED_PACKAGE_EXTRA = "${BuildConfig.APPLICATION_ID}.extra.REQUESTED_PACKAGE"

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

    var activePackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var activeToken by remember { mutableStateOf<String?>(null) }
    val options by DataModel.selectedOptions.collectAsState()

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
                if (
                    intent.action == INSTALL_STATUS_ACTION &&
                    activeToken != null &&
                    intent.getStringExtra(INSTALL_STATUS_TOKEN_EXTRA) == activeToken
                ) {
                    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
                    val specificMessage = context.getSpecificErrorMessage(
                        legacyStatus = intent.getIntExtra("android.content.pm.extra.LEGACY_STATUS", Int.MIN_VALUE),
                    )
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                    val packageName = intent.getStringExtra(INSTALL_REQUESTED_PACKAGE_EXTRA)
                        .orEmpty()
                        .ifBlank { intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME).orEmpty() }

                    if (packageName !in activePackages) return

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

    LaunchedEffect(statuses.size, activePackages) {
        if (activePackages.isNotEmpty() && statuses.size == activePackages.size) {
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

    statuses.takeIf { it.isNotEmpty() && activePackages.isNotEmpty() && it.size == activePackages.size }?.let { s ->
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
                val service = shellInterface
                if (service != null && !isInstalling && updatedFiles.isNotEmpty()) {
                    // Freeze this batch. Importing or editing the queue must not alter
                    // result accounting while PackageInstaller works asynchronously.
                    val batch = updatedFiles
                    val packages = batch.keys.toSet()
                    val token = UUID.randomUUID().toString()
                    val selectedFlags = options.map { it.value }.toIntArray()
                    val installerName = MutableOption.InstallerPackage.settingsKey.getValue()
                    val targetUser = MutableOption.TargetUser.settingsKey.getValue()

                    activeToken = token
                    activePackages = packages
                    statuses = emptyList()
                    isInstalling = true

                    scope.launch(Dispatchers.IO) {
                        val openDescriptors = mutableListOf<AssetFileDescriptor>()

                        fun failOutstanding(error: String) {
                            scope.launch(Dispatchers.Main.immediate) {
                                if (activeToken == token) {
                                    statuses = packages.map { name ->
                                        statuses.firstOrNull { it.packageName == name }
                                            ?: InstallResult(
                                                status = InstallStatus.FAILURE,
                                                packageName = name,
                                                message = error,
                                                specificMessage = null,
                                            )
                                    }
                                }
                            }
                        }

                        try {
                            val descriptors = batch.mapValues { (_, documents) ->
                                documents.map { document ->
                                    val descriptor = context.contentResolver.openAssetFileDescriptor(
                                        document.uri,
                                        "r",
                                    ) ?: throw IOException("Unable to open APK: ${document.name ?: document.uri}")
                                    openDescriptors += descriptor
                                    descriptor
                                }
                            }

                            service.install(
                                descriptors,
                                selectedFlags,
                                applier,
                                installerName,
                                targetUser,
                                token,
                                object : IErrorCallback.Stub() {
                                    override fun onError(error: String?, errorClass: String?) {
                                        SafeBugsnag.leaveBreadcrumb("$errorClass: $error")
                                        failOutstanding("$errorClass: $error")
                                    }
                                },
                            )
                        } catch (e: Exception) {
                            failOutstanding(e.extractErrorMessage() ?: e.toString())
                        } finally {
                            // Close sender-side FDs even when a split APK fails midway.
                            openDescriptors.forEach { fd -> runCatching { fd.close() } }
                        }
                    }
                }
            }
        },
        isInstalling = isInstalling,
        totalPackages = activePackages.size,
        completed = statuses.size,
    )
}
