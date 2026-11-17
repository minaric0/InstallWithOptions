@file:Suppress("PrivateApi")

package dev.zwander.installwithoptions.util

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.res.AssetFileDescriptor
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.zwander.installwithoptions.BuildConfig
import dev.zwander.installwithoptions.IOptionsApplier
import rikka.shizuku.SystemServiceHelper
import java.io.IOException
import java.io.OutputStream

class InternalInstaller(private val context: Context) {
    private val packageInstaller by lazy {
        val pmInstance = Class.forName("android.content.pm.IPackageManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, SystemServiceHelper.getSystemService("package"))

        Class.forName("android.content.pm.IPackageManager")
            .getMethod("getPackageInstaller")
            .invoke(pmInstance)
    }

    fun installPackage(
        fileDescriptors: Map<String, List<AssetFileDescriptor>>,
        options: IntArray,
        applier: IOptionsApplier,
        installerPackageName: String,
        userId: Int,
    ) {
        fileDescriptors.forEach { (expectedPackageName, fds) ->
            installPackagesInSession(
                expectedPackageName = expectedPackageName,
                fileDescriptors = fds.toTypedArray(),
                options = options,
                applier = applier,
                installerPackageName = installerPackageName,
                userId = userId,
                isBatch = fileDescriptors.size > 1,
            )
        }
    }

    @SuppressLint("InlinedApi")
    private fun installPackagesInSession(
        expectedPackageName: String,
        fileDescriptors: Array<AssetFileDescriptor>,
        options: IntArray,
        applier: IOptionsApplier,
        installerPackageName: String,
        userId: Int,
        isBatch: Boolean,
    ) {
        try {
            val params: PackageInstaller.SessionParams = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).run {
                options.reduceOrNull { acc, i -> acc or i }?.let { flags ->
                    PackageInstaller.SessionParams::class.java.getField("installFlags")
                        .set(this, flags)
                }

                // Allow PackageInstaller to allocate space and associate callbacks
                // with the right package on Android 17 / One UI 9.
                setAppPackageName(expectedPackageName)
                val lengths = fileDescriptors.map { it.length }
                if (lengths.all { it >= 0L }) {
                    setSize(lengths.sum())
                }
                if (isBatch && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setInstallScenario(PackageManager.INSTALL_SCENARIO_BULK)
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PackageInstaller.SessionParams::class.java
                        .getMethod("setPackageSource", Int::class.java)
                        .invoke(this, PackageInstaller.PACKAGE_SOURCE_STORE)
                    this.setInstallerPackageName(installerPackageName)
                }

                applier.applyOptions(this)
            }
            val sessionId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                packageInstaller::class.java
                    .getMethod(
                        "createSession",
                        PackageInstaller.SessionParams::class.java,
                        String::class.java, String::class.java, Int::class.java,
                    )
                    .invoke(
                        packageInstaller,
                        params,
                        installerPackageName,
                        installerPackageName,
                        userId,
                    ) as Int
            } else {
                packageInstaller::class.java
                    .getMethod(
                        "createSession",
                        PackageInstaller.SessionParams::class.java,
                        String::class.java,
                        Int::class.java,
                    )
                    .invoke(
                        packageInstaller,
                        params,
                        installerPackageName,
                        userId,
                    ) as Int
            }
            val session = packageInstaller::class.java.getMethod("openSession", Int::class.java)
                .invoke(packageInstaller, sessionId) as Any

            try {
                val statusIntent = PendingIntent.getBroadcast(
                    context, sessionId,
                    Intent(INSTALL_STATUS_ACTION).apply {
                        `package` = BuildConfig.APPLICATION_ID
                        putExtra(REQUESTED_PACKAGE_EXTRA, expectedPackageName)
                    },
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT,
                )

                fileDescriptors.forEachIndexed { index, fd ->
                    val writer = (session::class.java.getMethod(
                        "openWrite",
                        String::class.java,
                        Long::class.java,
                        Long::class.java,
                    ).invoke(
                        session,
                        "file_${index}",
                        0,
                        fd.length,
                    ) as ParcelFileDescriptor?)?.run {
                        if (Class.forName("android.os.SystemProperties")
                                .getMethod("getBoolean", String::class.java, Boolean::class.java)
                                .invoke(null, "fw.revocable_fd", false) as Boolean
                        ) {
                            ParcelFileDescriptor.AutoCloseOutputStream(this)
                        } else {
                            Class.forName("android.os.FileBridge\$FileBridgeOutputStream")
                                .getConstructor(ParcelFileDescriptor::class.java)
                                .newInstance(this) as OutputStream
                        }
                    }

                    try {
                        val output = writer ?: throw IOException("Could not open installer write stream")
                        output.use { stream ->
                            val input = fd.createInputStream()
                                ?: throw IOException("Could not open APK input stream")
                            input.use {
                                val copied = it.copyTo(stream, bufferSize = 256 * 1024)
                                if (fd.length >= 0L && copied != fd.length) {
                                    throw IOException(
                                        "Incomplete APK: copied $copied of ${fd.length} bytes",
                                    )
                                }
                            }
                        }
                    } finally {
                        fd.close()
                    }
                }

                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
                    session::class.java.getMethod(
                        "commit",
                        IntentSender::class.java,
                        Boolean::class.java,
                    ).invoke(session, statusIntent.intentSender, false)
                } else {
                    session::class.java.getMethod(
                        "commit",
                        IntentSender::class.java,
                    ).invoke(session, statusIntent.intentSender)
                }
            } catch (e: Throwable) {
                Log.e("InstallWithOptions", "error", e)
                runCatching { session::class.java.getMethod("abandon").invoke(session) }
                throw e
            } finally {
                runCatching { session::class.java.getMethod("close").invoke(session) }
            }
        } catch (e: Throwable) {
            Log.e("InstallWithOptions", "error", e)
            throw e
        }
    }
}