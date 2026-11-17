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
        fileDescriptors.forEach { (_, fds) ->
            installPackagesInSession(fds.toTypedArray(), options, applier, installerPackageName, userId, fileDescriptors.size > 1)
        }
    }

    @SuppressLint("InlinedApi")
    private fun installPackagesInSession(
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

                // Correctly describe on-device APKs; this is not an app-store installation.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    setInstallerPackageName(installerPackageName)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setInstallScenario(
                        if (isBatch) PackageManager.INSTALL_SCENARIO_BULK
                        else PackageManager.INSTALL_SCENARIO_FAST,
                    )
                }
                // The package manager can reserve space before streaming when size is known.
                if (fileDescriptors.all { it.length >= 0 }) {
                    setSize(fileDescriptors.sumOf { it.length })
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
                    },
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT,
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

                    val output = writer ?: throw IOException("Unable to open install-session writer for split $index")
                    val bytesCopied = output.use { stream ->
                        fd.createInputStream().use { input ->
                            input.copyTo(stream, 64 * 1024)
                        }
                    }
                    if (fd.length >= 0 && bytesCopied != fd.length) {
                        throw IOException("Short APK write for split $index: $bytesCopied of ${fd.length} bytes")
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
                session::class.java.getMethod("abandon").invoke(session)
                throw e
            }
        } catch (e: Throwable) {
            Log.e("InstallWithOptions", "error", e)
            throw e
        }
    }
}