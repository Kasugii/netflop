package be.kasugi.netflop.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections

/** Infos d'un film / d'une série récupérées sur TMDB (même source que Plex). */
class TitleMeta(
    val tmdbId: Int,
    val tv: Boolean,
    val name: String,
    val overview: String,
    val backdrop: String?,
    val poster: String?,
    val logo: String?,
    val genres: List<String>,
    val vote: Double,
    val voteCount: Int,
    val year: Int?,
    val certification: String?,
    val seasonCount: Int,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", tmdbId).put("tv", tv).put("name", name).put("overview", overview)
        .put("backdrop", backdrop ?: "").put("poster", poster ?: "").put("logo", logo ?: "")
        .put("genres", JSONArray(genres)).put("vote", vote).put("voteCount", voteCount)
        .put("year", year ?: 0).put("cert", certification ?: "").put("seasons", seasonCount)

    companion object {
        fun fromJson(o: JSONObject): TitleMeta {
            val g = o.optJSONArray("genres")
            return TitleMeta(
                tmdbId = o.optInt("id"),
                tv = o.optBoolean("tv"),
                name = o.optString("name"),
                overview = o.optString("overview"),
                backdrop = o.optString("backdrop").ifBlank { null },
                poster = o.optString("poster").ifBlank { null },
                logo = o.optString("logo").ifBlank { null },
                genres = if (g == null) emptyList() else (0 until g.length()).map { g.optString(it) },
                vote = o.optDouble("vote", 0.0),
                voteCount = o.optInt("voteCount"),
                year = o.optInt("year").takeIf { it > 0 },
                certification = o.optString("cert").ifBlank { null },
                seasonCount = o.optInt("seasons"),
            )
        }
    }
}

class EpisodeMeta(val name: String, val overview: String, val still: String?, val runtime: Int?)

object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    private const val DAY = 24L * 3600 * 1000
    private lateinit var dir: File
    private val sem = Semaphore(4)
    private val inFlight = Collections.synchronizedSet(HashSet<String>())
    private val episodesDone = Collections.synchronizedSet(HashSet<String>())

    var key: String? = null
        private set
    val enabled: Boolean get() = !key.isNullOrBlank()

    /** Infos par id de titre (observé par l'interface). */
    val metas = mutableStateMapOf<String, TitleMeta>()

    /** Ids dont la recherche est terminée (trouvé ou non). */
    val resolved = mutableStateMapOf<String, Boolean>()

    /** Séries dont les épisodes ont été cherchés. */
    val episodesLoaded = mutableStateMapOf<String, Boolean>()

    /** Infos par clé d'épisode. */
    val episodes = mutableStateMapOf<String, EpisodeMeta>()

    fun init(context: Context) {
        dir = File(context.filesDir, "tmdb").apply { mkdirs() }
    }

    /** Clé lue dans Netflop/tmdb.txt (clé API v3 ou jeton de lecture v4). */
    fun configure(root: File?) {
        key = root?.let { r ->
            val candidates = listOf(r, File(r, "_sons")).flatMap { d -> (d.listFiles() ?: emptyArray()).toList() }
                .filter { it.isFile && it.name.lowercase().let { n -> n.startsWith("tmdb") && (n.endsWith(".txt") || n.endsWith(".key")) } }
                .sortedBy { it.name.length }
            candidates.firstNotNullOfOrNull { f ->
                try {
                    f.readText().lineSequence()
                        .map { it.trim().removePrefix("\uFEFF").trim() }
                        .map { line -> line.substringAfter(':', line).substringAfter('=', line).trim().trim('"', '\'') }
                        .firstOrNull { it.length >= 20 }
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    fun image(path: String?, size: String): String? =
        path?.takeIf { it.isNotBlank() }?.let { "https://image.tmdb.org/t/p/$size$it" }

    fun isPending(id: String): Boolean = enabled && resolved[id] == null

    // ---------------- Réseau ----------------

    private fun get(path: String, params: Map<String, String>): JSONObject? {
        val k = key ?: return null
        val bearer = k.startsWith("eyJ")
        val q = params.toMutableMap()
        if (!bearer) q["api_key"] = k
        val url = API + path + "?" + q.entries.joinToString("&") {
            it.key + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        repeat(3) { attempt ->
            var c: HttpURLConnection? = null
            try {
                c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8_000
                c.readTimeout = 15_000
                c.setRequestProperty("Accept", "application/json")
                if (bearer) c.setRequestProperty("Authorization", "Bearer $k")
                val code = c.responseCode
                if (code == 429) {
                    Thread.sleep(1_200L * (attempt + 1))
                } else if (code in 200..299) {
                    return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                } else {
                    return null
                }
            } catch (_: IOException) {
                Thread.sleep(600L * (attempt + 1))
            } catch (_: Throwable) {
                return null
            } finally {
                c?.disconnect()
            }
        }
        return null
    }

    private fun str(o: JSONObject?, k: String): String? =
        if (o == null || !o.has(k) || o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }

    private fun hash(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    // ---------------- Titres ----------------

    suspend fun ensure(t: Title) {
        if (!enabled || metas.containsKey(t.id) || resolved[t.id] != null) return
        if (!inFlight.add(t.id)) return
        try {
            val m = withContext(Dispatchers.IO) { loadOrFetch(t) }
            withContext(Dispatchers.Main) {
                if (m != null) metas[t.id] = m
                resolved[t.id] = m != null
            }
        } finally {
            inFlight.remove(t.id)
        }
    }

    private suspend fun loadOrFetch(t: Title): TitleMeta? {
        val cache = File(dir, "t-" + hash(t.id + "|" + (t.tmdbHint ?: 0)) + ".json")
        try {
            if (cache.exists()) {
                val o = JSONObject(cache.readText())
                val age = System.currentTimeMillis() - o.optLong("at")
                val found = o.optBoolean("found")
                if (found && age < 30 * DAY) return TitleMeta.fromJson(o.getJSONObject("data"))
                if (!found && age < 3 * DAY) return null
            }
        } catch (_: Throwable) {
        }
        val m = sem.withPermit { fetch(t) }
        try {
            val o = JSONObject().put("at", System.currentTimeMillis()).put("found", m != null)
            if (m != null) o.put("data", m.toJson())
            cache.writeText(o.toString())
        } catch (_: Throwable) {
        }
        return m
    }

    private fun fetch(t: Title): TitleMeta? {
        var tv = t.kind == Kind.SERIES
        var id = t.tmdbHint
        if (id == null) {
            id = search(t.name, t.year, tv)
            if (id == null) {
                id = search(t.name, t.year, !tv)
                if (id != null) tv = !tv
            }
        }
        if (id == null) return null
        return details(id, tv)
    }

    private fun search(query: String, year: Int?, tv: Boolean): Int? {
        if (query.isBlank()) return null
        val path = if (tv) "/search/tv" else "/search/movie"
        val params = mutableMapOf("query" to query, "language" to "fr-FR", "include_adult" to "false")
        val yearKey = if (tv) "first_air_date_year" else "year"
        if (year != null) params[yearKey] = year.toString()
        var res = get(path, params)?.optJSONArray("results")
        if ((res == null || res.length() == 0) && year != null) {
            params.remove(yearKey)
            res = get(path, params)?.optJSONArray("results")
        }
        if (res == null || res.length() == 0) return null
        val q = normalize(query)
        val n = minOf(res.length(), 8)
        // Titre identique en priorité, sinon le plus pertinent selon TMDB
        for (i in 0 until n) {
            val o = res.optJSONObject(i) ?: continue
            val names = listOfNotNull(str(o, "name"), str(o, "original_name"), str(o, "title"), str(o, "original_title"))
            if (names.any { normalize(it) == q }) return o.optInt("id").takeIf { it > 0 }
        }
        return res.optJSONObject(0)?.optInt("id")?.takeIf { it > 0 }
    }

    private fun pick(arr: JSONArray?, prefs: List<String?>): String? {
        if (arr == null || arr.length() == 0) return null
        val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
        fun langOf(o: JSONObject): String? = str(o, "iso_639_1")?.takeIf { it != "xx" }
        for (p in prefs) {
            val best = all.filter { langOf(it) == p }
                .maxWithOrNull(compareBy<JSONObject> { it.optDouble("vote_average", 0.0) }.thenBy { it.optInt("width") })
            if (best != null) return str(best, "file_path")
        }
        return str(all.first(), "file_path")
    }

    private fun details(id: Int, tv: Boolean): TitleMeta? {
        val path = (if (tv) "/tv/" else "/movie/") + id
        val d = get(
            path,
            mapOf(
                "language" to "fr-FR",
                "append_to_response" to if (tv) "images,content_ratings" else "images,release_dates",
                "include_image_language" to "fr,en,null",
            )
        ) ?: return null
        var overview = str(d, "overview") ?: ""
        if (overview.isBlank()) overview = str(get(path, mapOf("language" to "en-US")), "overview") ?: ""
        val images = d.optJSONObject("images")
        val backdrop = pick(images?.optJSONArray("backdrops"), listOf(null, "fr", "en")) ?: str(d, "backdrop_path")
        val logo = pick(images?.optJSONArray("logos"), listOf("fr", "en", null))
        val poster = pick(images?.optJSONArray("posters"), listOf("fr", "en", null)) ?: str(d, "poster_path")
        val genresArr = d.optJSONArray("genres")
        val genres = if (genresArr == null) emptyList() else
            (0 until genresArr.length()).mapNotNull { str(genresArr.optJSONObject(it), "name") }
        val date = str(d, if (tv) "first_air_date" else "release_date")
        val year = date?.take(4)?.toIntOrNull()
        val cert = if (tv) {
            val r = d.optJSONObject("content_ratings")?.optJSONArray("results")
            val list = if (r == null) emptyList() else (0 until r.length()).mapNotNull { r.optJSONObject(it) }
            listOf("FR", "BE", "US").firstNotNullOfOrNull { c ->
                list.firstOrNull { str(it, "iso_3166_1") == c }?.let { str(it, "rating") }
            }
        } else {
            val r = d.optJSONObject("release_dates")?.optJSONArray("results")
            val list = if (r == null) emptyList() else (0 until r.length()).mapNotNull { r.optJSONObject(it) }
            listOf("FR", "BE", "US").firstNotNullOfOrNull { c ->
                val entry = list.firstOrNull { str(it, "iso_3166_1") == c } ?: return@firstNotNullOfOrNull null
                val rd = entry.optJSONArray("release_dates") ?: return@firstNotNullOfOrNull null
                (0 until rd.length()).mapNotNull { str(rd.optJSONObject(it), "certification") }.firstOrNull()
            }
        }
        return TitleMeta(
            tmdbId = id,
            tv = tv,
            name = str(d, if (tv) "name" else "title") ?: "",
            overview = overview,
            backdrop = backdrop,
            poster = poster,
            logo = logo,
            genres = genres,
            vote = d.optDouble("vote_average", 0.0),
            voteCount = d.optInt("vote_count"),
            year = year,
            certification = cert,
            seasonCount = if (tv) d.optInt("number_of_seasons") else 0,
        )
    }

    // ---------------- Épisodes ----------------

    suspend fun ensureEpisodes(t: Title) {
        if (!enabled || t.kind != Kind.SERIES) return
        ensure(t)
        val m = metas[t.id]
        if (m == null || !m.tv) {
            withContext(Dispatchers.Main) { episodesLoaded[t.id] = false }
            return
        }
        if (!episodesDone.add(t.id)) return
        val result = try {
            fetchEpisodes(t, m)
        } catch (e: Throwable) {
            episodesDone.remove(t.id)
            throw e
        }
        withContext(Dispatchers.Main) {
            episodes.putAll(result)
            episodesLoaded[t.id] = true
        }
    }

    private suspend fun fetchEpisodes(t: Title, m: TitleMeta): Map<String, EpisodeMeta> =
        withContext(Dispatchers.IO) {
            val out = HashMap<String, EpisodeMeta>()
            val seasons = HashMap<Int, List<Pair<Int, EpisodeMeta>>>()
            suspend fun season(n: Int): List<Pair<Int, EpisodeMeta>> {
                seasons[n]?.let { return it }
                val v = loadSeason(m.tmdbId, n)
                seasons[n] = v
                return v
            }
            val unmatched = mutableListOf<Episode>()
            for (s in t.seasons) {
                for (e in s.episodes) {
                    val hit = season(s.number).firstOrNull { it.first == e.number }
                    if (hit != null) out[e.key] = hit.second else unmatched += e
                }
            }
            // Numérotation "absolue" (One Piece 1085…) : on compte à travers toutes les saisons TMDB
            if (unmatched.isNotEmpty() && m.seasonCount > 0) {
                val all = mutableListOf<EpisodeMeta>()
                for (n in 1..minOf(m.seasonCount, 80)) all += season(n).map { it.second }
                for (e in unmatched) {
                    if (e.season == 0) continue
                    all.getOrNull(e.number - 1)?.let { out[e.key] = it }
                }
            }
            out
        }

    private suspend fun loadSeason(tvId: Int, n: Int): List<Pair<Int, EpisodeMeta>> {
        val cache = File(dir, "s-$tvId-$n.json")
        var raw: JSONObject? = null
        try {
            if (cache.exists() && System.currentTimeMillis() - cache.lastModified() < 20 * DAY) {
                raw = JSONObject(cache.readText())
            }
        } catch (_: Throwable) {
        }
        if (raw == null) {
            raw = sem.withPermit { get("/tv/$tvId/season/$n", mapOf("language" to "fr-FR")) }
            if (raw != null) {
                try {
                    cache.writeText(raw.toString())
                } catch (_: Throwable) {
                }
            }
        }
        val eps = raw?.optJSONArray("episodes") ?: return emptyList()
        return (0 until eps.length()).mapNotNull { i ->
            val e = eps.optJSONObject(i) ?: return@mapNotNull null
            val num = e.optInt("episode_number")
            if (num <= 0) return@mapNotNull null
            num to EpisodeMeta(
                name = str(e, "name") ?: "",
                overview = str(e, "overview") ?: "",
                still = str(e, "still_path"),
                runtime = e.optInt("runtime").takeIf { it > 0 },
            )
        }
    }
}
