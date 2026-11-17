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
        statusToken: String?,
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
                installerPackageName.takeIf { !it.isNullOrBlank() } ?: "com.android.shell",
                actualUserId,
                requireNotNull(statusToken) { "Missing installation status token" },
            )
        } catch (e: Throwable) {
            errorCallback.onError(e.extractErrorMessage(), e::class.java.canonicalName ?: e::class.java.name)
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun getUserIds(): List<Int> {
        // Samsung firmware may change hidden IUserManager overloads between
        // platform releases. User enumeration is optional: failure should never
        // break the installer or prevent use of the current Android profile.
        val fallback = listOf(myUserId())
        return runCatching {
            val userInfoClass = Class.forName("android.content.pm.UserInfo")
            val userInfoIdField = userInfoClass.getField("id")
            val stub = Class.forName("android.os.IUserManager\\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, SystemServiceHelper.getSystemService(Context.USER_SERVICE))
            val manager = requireNotNull(stub)
            val getUsers = manager.javaClass.methods
                .filter { method ->
                    method.name == "getUsers" && method.parameterTypes.isNotEmpty() &&
                        method.parameterTypes.all { it == Boolean::class.javaPrimitiveType }
                }
                .maxByOrNull { it.parameterTypes.size }
                ?: return fallback
            val args: Array<Any> = Array(getUsers.parameterTypes.size) { false }
            val users = getUsers.invoke(manager, *args) as? List<*> ?: return fallback

            val getProfiles = manager.javaClass.methods.firstOrNull { method ->
                method.name == "getProfiles" && method.parameterTypes.isNotEmpty() &&
                    method.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    method.parameterTypes.drop(1).all { it == Boolean::class.javaPrimitiveType }
            }
            val ids = mutableSetOf(myUserId())
            users.forEach { user ->
                if (user == null) return@forEach
                val uid = userInfoIdField.getInt(user)
                ids += uid
                if (getProfiles != null) {
                    val profileArgs: Array<Any> = arrayOf(uid, *Array(getProfiles.parameterTypes.size - 1) { false })
                    val profiles = getProfiles.invoke(manager, *profileArgs) as? List<*>
                    profiles?.forEach { profile ->
                        if (profile != null) ids += userInfoIdField.getInt(profile)
                    }
                }
            }
            ids.sorted()
        }.getOrElse { fallback }
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
