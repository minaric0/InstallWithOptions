package dev.zwander.installwithoptions

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Build
import com.bugsnag.android.Bugsnag
import com.bugsnag.android.performance.BugsnagPerformance
import dev.zwander.installwithoptions.data.Settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.TimeUnit

@OptIn(DelicateCoroutinesApi::class)
class App : Application() {
    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var context: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        context = this

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.setHiddenApiExemptions("")
        }

        if (Settings.Keys.enableCrashReports.getValue()) {
            startBugsnag()
        }

        GlobalScope.launch(Dispatchers.IO) {
            pruneStaleImportCaches()
            Settings.Keys.enableCrashReports.asMutableStateFlow().collect {
                if (it && !Bugsnag.isStarted()) {
                    startBugsnag()
                }
            }
        }
    }

    private fun pruneStaleImportCaches() {
        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2)
        cacheDir.listFiles()?.forEach { entry ->
            val ownedImport = entry.name.startsWith("iwo_apks_") ||
                entry.name.startsWith("iwo_bundle_") ||
                entry.name.startsWith("iwo_parse_")
            if (ownedImport && entry.lastModified() < cutoff) {
                runCatching { entry.deleteRecursively() }
            }
        }
    }

    private fun startBugsnag() {
        Bugsnag.start(this)
        BugsnagPerformance.start(this)
    }
}
