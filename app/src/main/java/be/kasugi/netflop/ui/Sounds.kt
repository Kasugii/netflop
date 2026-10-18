package be.kasugi.netflop.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.os.SystemClock
import be.kasugi.netflop.R
import java.io.File

/** Bruitages de l'interface (navigation, validation, retour, intro). */
object Sounds {
    private var pool: SoundPool? = null
    private var navId = 0
    private var selectId = 0
    private var backId = 0
    private var profileId = 0
    private var lastNav = 0L
    private var intro: MediaPlayer? = null
    var enabled = true
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        enabled = prefs.getBoolean("sounds", true)
        if (pool != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
        navId = p.load(context, R.raw.nav, 1)
        selectId = p.load(context, R.raw.select, 1)
        backId = p.load(context, R.raw.back, 1)
        profileId = p.load(context, R.raw.profile, 1)
        pool = p
    }

    fun setEnabled(context: Context, on: Boolean) {
        enabled = on
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("sounds", on).apply()
    }

    private fun play(id: Int, vol: Float) {
        if (!enabled || id == 0) return
        pool?.play(id, vol, vol, 1, 0, 1f)
    }

    // Interface silencieuse : seul le son d'ouverture est joué.
    fun nav() {}
    fun select() {}
    fun back() {}
    fun profile() {}

    fun playIntro(context: Context, override: File?) {
        if (!enabled) return
        try {
            intro?.release()
            val mp = if (override != null) {
                MediaPlayer().apply {
                    setDataSource(override.absolutePath)
                    prepare()
                }
            } else {
                MediaPlayer.create(context, R.raw.intro)
            } ?: return
            mp.setOnCompletionListener {
                it.release()
                if (intro === it) intro = null
            }
            mp.start()
            intro = mp
        } catch (_: Throwable) {
        }
    }

    fun stopIntro() {
        try {
            intro?.release()
        } catch (_: Throwable) {
        }
        intro = null
    }
}
