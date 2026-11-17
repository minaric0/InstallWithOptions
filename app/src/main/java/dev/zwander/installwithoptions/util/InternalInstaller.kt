@file:Suppress("PrivateApi")

package dev.zwander.installwithoptions.util

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.res.AssetFileDescriptor
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.zwander.installwithoptions.BuildConfig
import dev.zwander.installwithoptions.IOptionsApplier
import rikka.shizuku.SystemServiceHelper
import java.io.OutputStream
import kotlin.random.Random

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
        fileDescriptors.forEach { (packageKey, fds) ->
            try {
                installPackagesInSession(packageKey, fds.toTypedArray(), options, applier, installerPackageName, userId)
            } finally {
                fds.forEach { descriptor -> runCatching { descriptor.close() } }
            }
        }
    }

    @SuppressLint("InlinedApi")
    private fun installPackagesInSession(
        packageKey: String,
        fileDescriptors: Array<AssetFileDescriptor>,
        options: IntArray,
        applier: IOptionsApplier,
        installerPackageName: String,
        userId: Int,
    ) {
        try {
            val params: PackageInstaller.SessionParams = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).run {
                options.reduceOrNull { acc, i -> acc or i }?.let { flags ->
                    PackageInstaller.SessionParams::class.java.getField("installFlags")
                        .set(this, flags)
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
                    context, Random.nextInt(),
                    Intent(INSTALL_STATUS_ACTION).apply {
                        `package` = BuildConfig.APPLICATION_ID
                        putExtra(INSTALL_PACKAGE_KEY_EXTRA, packageKey)
                    },
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )

                fileDescriptors.forEachIndexed { index, fd ->
                    val parcel = session::class.java.getMethod(
                        "openWrite",
                        String::class.java,
                        Long::class.java,
                        Long::class.java,
                    ).invoke(session, "file_" + index + ".apk", 0L, fd.length) as? ParcelFileDescriptor
                        ?: error("Installer did not provide a writable descriptor")

                    val output: OutputStream = if (
                        Class.forName("android.os.SystemProperties")
                            .getMethod("getBoolean", String::class.java, Boolean::class.java)
                            .invoke(null, "fw.revocable_fd", false) as Boolean
                    ) {
                        ParcelFileDescriptor.AutoCloseOutputStream(parcel)
                    } else {
                        Class.forName("android.os.FileBridge\$FileBridgeOutputStream")
                            .getConstructor(ParcelFileDescriptor::class.java)
                            .newInstance(parcel) as OutputStream
                    }

                    output.use { writer ->
                        fd.createInputStream().use { input ->
                            val bytesWritten = input.copyTo(writer, bufferSize = 128 * 1024)
                            if (fd.length >= 0 && bytesWritten != fd.length) {
                                error("Incomplete APK transfer: " + bytesWritten + " / " + fd.length + " bytes")
                            }
                        }
                        session::class.java.getMethod("fsync", OutputStream::class.java)
                            .invoke(session, writer)
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
            }
        } catch (e: Throwable) {
            Log.e("InstallWithOptions", "error", e)
            throw e
        }
    }
}