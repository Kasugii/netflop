package be.kasugi.netflop

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import be.kasugi.netflop.data.Thumbs
import be.kasugi.netflop.data.Tmdb
import be.kasugi.netflop.data.SettingsStore
import be.kasugi.netflop.ui.AppState
import be.kasugi.netflop.ui.CrashReport
import be.kasugi.netflop.ui.NetflopApp
import be.kasugi.netflop.ui.NetflopTheme
import be.kasugi.netflop.ui.Sounds

class MainActivity : ComponentActivity() {

    private lateinit var appState: AppState

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        appState.refreshRoot()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReport.install(this)
        BuildConfigInfo.version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        } catch (_: Throwable) {
            "?"
        }
        Sounds.init(this)
        Thumbs.init(this)
        Tmdb.init(this)
        SettingsStore.init(this)
        appState = AppState(applicationContext)
        appState.crash = CrashReport.consume(this)
        try {
            appState.refreshRoot()
        } catch (e: Throwable) {
            appState.crash = CrashReport.format(e)
        }
        setContent {
            NetflopTheme {
                NetflopApp(appState) { permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Disque rebranché pendant que l'app était en arrière-plan
        if (::appState.isInitialized && appState.root?.exists() != true) appState.refreshRoot()
    }
}

object BuildConfigInfo {
    var version: String = "?"
}
