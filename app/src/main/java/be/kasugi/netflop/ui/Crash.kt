package be.kasugi.netflop.ui

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.kasugi.netflop.BuildConfigInfo
import java.io.File

/** Enregistre les plantages pour les afficher au lancement suivant. */
object CrashReport {
    private fun file(ctx: Context) = File(ctx.filesDir, "crash.txt")

    fun format(e: Throwable): String {
        val trace = Log.getStackTraceString(e).lineSequence()
            .filter { it.isNotBlank() }
            .take(28)
            .joinToString("\n")
        return "Netflop ${BuildConfigInfo.version} · Android ${Build.VERSION.RELEASE} · ${Build.MODEL}\n$trace"
    }

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                file(app).writeText(format(e))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
    }

    /** Rapport du dernier plantage (puis effacé). */
    fun consume(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        return try {
            f.readText().also { f.delete() }
        } catch (_: Throwable) {
            null
        }
    }
}

@Composable
fun CrashScreen(text: String, onClose: () -> Unit) {
    val req = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color(0xFF101010)).padding(36.dp)) {
        Column {
            Text("Oups, Netflop a planté la dernière fois", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Text(
                "Prends cet écran en photo et envoie-le pour qu'on corrige.",
                color = TextGray, fontSize = 15.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(text, color = Color(0xFFFF8A80), fontSize = 11.sp, lineHeight = 14.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(16.dp))
            NetButton("Continuer", { }, onClose, focusRequester = req)
        }
    }
    AutoFocus(req)
}
