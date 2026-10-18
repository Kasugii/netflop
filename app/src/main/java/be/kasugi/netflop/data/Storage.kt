package be.kasugi.netflop.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Trouve le dossier "Netflop" sur les disques branchés à la Shield. */
object StorageFinder {

    fun volumes(context: Context): List<File> {
        val set = linkedSetOf<File>()
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val sm = context.getSystemService(StorageManager::class.java)
                sm?.storageVolumes?.forEach { v -> v.directory?.let { set += it } }
            } catch (_: Throwable) {
            }
        }
        File("/storage").listFiles()?.forEach {
            if (it.isDirectory && it.name != "self" && it.name != "emulated") set += it
        }
        @Suppress("DEPRECATION")
        set += Environment.getExternalStorageDirectory()
        File("/mnt/media_rw").listFiles()?.forEach { if (it.isDirectory) set += it }
        File("/mnt/usb").listFiles()?.forEach { if (it.isDirectory) set += it }
        return set.toList()
    }

    fun findNetflop(context: Context): File? {
        val vols = volumes(context)
        for (v in vols) {
            val hit = v.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("Netflop", true) }
            if (hit != null) return hit
        }
        // Un niveau plus bas (ex : disque/Films/Netflop)
        for (v in vols) {
            val dirs = v.listFiles()?.filter { it.isDirectory } ?: continue
            for (d in dirs) {
                val hit = d.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("Netflop", true) }
                if (hit != null) return hit
            }
        }
        return null
    }
}

class Progress(val pos: Long, val dur: Long, val at: Long) {
    val fraction: Float get() = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val done: Boolean get() = dur > 0 && (pos >= dur * 0.92 || dur - pos < 60_000 && pos > dur * 0.6)
    val remainingMin: Long get() = ((dur - pos).coerceAtLeast(0) / 60_000)
}

/** Progression, historique et préférences de pistes d'un profil (stocké dans l'app). */
class ProfileStore(private val file: File) {
    private val progress = HashMap<String, Progress>()
    var audioLang: String? = null
    var subLang: String? = null
    var subOff: Boolean = false
    var version by mutableIntStateOf(0)
        private set

    init {
        load()
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            val p = o.optJSONObject("progress")
            if (p != null) {
                for (k in p.keys()) {
                    val e = p.getJSONObject(k)
                    progress[k] = Progress(e.optLong("pos"), e.optLong("dur"), e.optLong("at"))
                }
            }
            audioLang = o.optString("audioLang").ifBlank { null }
            subLang = o.optString("subLang").ifBlank { null }
            subOff = o.optBoolean("subOff", false)
        } catch (_: Throwable) {
        }
    }

    private fun save() {
        try {
            val p = JSONObject()
            for ((k, v) in progress) {
                p.put(k, JSONObject().put("pos", v.pos).put("dur", v.dur).put("at", v.at))
            }
            val o = JSONObject()
                .put("progress", p)
                .put("audioLang", audioLang ?: "")
                .put("subLang", subLang ?: "")
                .put("subOff", subOff)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(o.toString())
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }

    fun get(key: String?): Progress? = key?.let { progress[it] }

    fun update(key: String, pos: Long, dur: Long) {
        if (dur <= 0) return
        progress[key] = Progress(pos.coerceIn(0, dur), dur, System.currentTimeMillis())
        version++
        save()
    }

    fun clear(key: String) {
        if (progress.remove(key) != null) {
            version++
            save()
        }
    }

    fun setTrackPrefs(audio: String?, sub: String?, off: Boolean) {
        if (audio != null) audioLang = audio
        subLang = sub ?: subLang
        subOff = off
        save()
    }

    fun resumePos(key: String?): Long {
        val p = get(key) ?: return 0
        return if (!p.done && p.pos > 15_000) p.pos - 3_000 else 0
    }

    // ----- Logique "Netflix" au niveau d'un titre -----

    fun lastWatchedAt(t: Title): Long {
        if (t.kind == Kind.MOVIE) return get(t.movieKey)?.at ?: 0
        return t.episodes.maxOfOrNull { progress[it.key]?.at ?: 0L } ?: 0
    }

    /** Épisode à lancer quand on appuie sur "Lecture". */
    fun nextEpisode(t: Title): Episode? {
        val eps = t.episodes
        if (eps.isEmpty()) return null
        var best: Episode? = null
        var bestAt = 0L
        for (e in eps) {
            val p = progress[e.key] ?: continue
            if (p.at > bestAt) {
                bestAt = p.at
                best = e
            }
        }
        if (best == null) return eps.first()
        val p = progress[best.key]!!
        if (!p.done) return best
        val idx = eps.indexOf(best)
        return eps.getOrNull(idx + 1) ?: best
    }

    fun playKey(t: Title): String? = if (t.kind == Kind.MOVIE) t.movieKey else nextEpisode(t)?.key

    /** Progression affichée sur la vignette (si en cours). */
    fun cardProgress(t: Title): Float? {
        val p = get(playKey(t)) ?: return null
        if (p.done || p.pos < 10_000) return null
        return p.fraction
    }

    fun inProgress(t: Title): Boolean {
        if (lastWatchedAt(t) == 0L) return false
        if (t.kind == Kind.MOVIE) {
            val p = get(t.movieKey) ?: return false
            return !p.done && p.pos > 10_000
        }
        val next = nextEpisode(t) ?: return false
        val p = get(next.key)
        // en cours, ou épisode suivant pas encore commencé
        return p == null || !p.done
    }

    fun continueWatching(lib: Library): List<Title> =
        lib.titles.filter { inProgress(it) }.sortedByDescending { lastWatchedAt(it) }

    fun recentlyWatched(lib: Library): List<Title> =
        lib.titles.filter { lastWatchedAt(it) > 0 }.sortedByDescending { lastWatchedAt(it) }
}

/** Miniatures extraites des vidéos (mises en cache dans l'app). */
object Thumbs {
    private lateinit var dir: File
    private val sem = Semaphore(2)

    fun init(context: Context) {
        dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun frame(video: File, fraction: Float): File? = withContext(Dispatchers.IO) {
        Scanner.siblingImage(video)?.let { return@withContext it }
        val key = hash(video.absolutePath + ":" + video.length() + ":" + fraction)
        val out = File(dir, "$key.jpg")
        if (out.exists() && out.length() > 0) return@withContext out
        val bad = File(dir, "$key.bad")
        if (bad.exists()) return@withContext null
        sem.withPermit {
            if (out.exists() && out.length() > 0) return@withPermit out
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(video.absolutePath)
                val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (durMs > 0) File(dir, "${hash(video.absolutePath)}.dur").writeText(durMs.toString())
                val atUs = if (durMs > 0) (durMs * fraction).toLong() * 1000L else 30_000_000L
                val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 27) {
                    mmr.getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 960, 540)
                } else {
                    mmr.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                if (bmp == null) {
                    bad.createNewFile()
                    null
                } else {
                    FileOutputStream(out).use { o -> bmp.compress(Bitmap.CompressFormat.JPEG, 82, o) }
                    bmp.recycle()
                    out
                }
            } catch (_: Throwable) {
                try {
                    bad.createNewFile()
                } catch (_: Throwable) {
                }
                null
            } finally {
                try {
                    mmr.release()
                } catch (_: Throwable) {
                }
            }
        }
    }

    /** Durée connue (en ms) si une miniature a déjà été extraite. */
    fun knownDuration(video: File): Long? = try {
        File(dir, "${hash(video.absolutePath)}.dur").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull()
    } catch (_: Throwable) {
        null
    }
}
