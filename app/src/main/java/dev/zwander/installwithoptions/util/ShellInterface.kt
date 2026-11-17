package dev.zwander.installwithoptions.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import dev.zwander.installwithoptions.IErrorCallback
import dev.zwander.installwithoptions.IOptionsApplier
import dev.zwander.installwithoptions.IShellInterface
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.SystemServiceHelper
import kotlin.system.exitProcess

@SuppressLint("PrivateApi")
@Suppress("RedundantConstructorKeyword")
class ShellInterface constructor() : IShellInterface.Stub() {
    private val installer by lazy {
        InternalInstaller(createContext())
    }

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.setHiddenApiExemptions("")
        }
    }

    override fun install(
        fileDescriptors: Map<*, *>,
        options: IntArray,
        applier: IOptionsApplier,
        installerPackageName: String?,
        userId: Int,
        errorCallback: IErrorCallback,
    ) {
        if (Looper.myLooper() == null) {
            Looper.prepare()
        }

        val actualUserId = userId.takeIf { it != Int.MIN_VALUE } ?: myUserId()

        try {
            @Suppress("UNCHECKED_CAST")
            installer.installPackage(
                fileDescriptors as Map<String, List<AssetFileDescriptor>>,
                options,
                applier,
                installerPackageName.takeIf { !it.isNullOrBlank() } ?: "shell",
                actualUserId,
            )
        } catch (e: Throwable) {
            errorCallback.onError(e.extractErrorMessage(), e::class.java.canonicalName ?: e::class.java.name)
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    /**
     * IUserManager signatures change across Android versions. Probe available
     * overloads and fall back to the current user if OEM APIs differ.
     */
    override fun getUserIds(): List<Int> {
        val currentUser = myUserId()
        return runCatching {
            val info = Class.forName("android.content.pm.UserInfo").getField("id")
            val manager = Class.forName("android.os.IUserManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, SystemServiceHelper.getSystemService(Context.USER_SERVICE))

            val users = manager.javaClass.methods
                .filter { it.name == "getUsers" }
                .sortedByDescending { it.parameterTypes.size }
                .firstNotNullOfOrNull { method ->
                    runCatching {
                        val args = method.parameterTypes.map { type ->
                            when (type) {
                                java.lang.Boolean.TYPE -> false
                                java.lang.Integer.TYPE -> 0
                                else -> error("Unsupported getUsers argument")
                            }
                        }.toTypedArray()
                        method.invoke(manager, *args) as? List<*>
                    }.getOrNull()
                }.orEmpty()

            val ids = (users.mapNotNull { user ->
                runCatching { info.getInt(user) }.getOrNull()
            } + currentUser).distinct()

            val profiles = ids.flatMap { id ->
                manager.javaClass.methods
                    .filter {
                        it.name == "getProfiles" && it.parameterTypes.contentEquals(
                            arrayOf(java.lang.Integer.TYPE, java.lang.Boolean.TYPE)
                        )
                    }
                    .firstNotNullOfOrNull { method ->
                        runCatching {
                            (method.invoke(manager, id, false) as? List<*>)
                                .orEmpty().mapNotNull { user ->
                                    runCatching { info.getInt(user) }.getOrNull()
                                }
                        }.getOrNull()
                    }.orEmpty()
            }
            (ids + profiles).distinct().sorted()
        }.getOrElse { listOf(currentUser) }
    }

    override fun isRootOrSystem(): Boolean {
        val uid = Process.myUid()

        return uid == 1000 || uid == 0
    }

    private fun createContext(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val systemMain = activityThreadClass.getMethod("systemMain")
            .invoke(null)
        val systemContext = activityThreadClass.getMethod("getSystemContext")
            .invoke(systemMain) as Context

        val context = systemContext::class.java.getMethod(
            "createPackageContextAsUser",
            String::class.java,
            Int::class.java,
            UserHandle::class.java,
        ).invoke(
            systemContext,
            "com.android.shell",
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            UserHandle::class.java.getConstructor(Int::class.java).newInstance(myUserId()),
        ) as Context

        return context.createPackageContext("com.android.shell", 0)
    }
}

fun myUserId() = UserHandle::class.java.getMethod("myUserId").invoke(null) as Int
