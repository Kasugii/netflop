package be.kasugi.netflop.data

import java.io.File
import java.text.Normalizer
import java.util.Locale

enum class Kind { MOVIE, SERIES }

class Episode(
    val key: String,
    val file: File,
    val season: Int,
    val number: Int,
    val name: String,
) {
    val label: String get() = name.ifBlank { "Épisode $number" }
}

class Season(val number: Int, val name: String, val episodes: List<Episode>)

class Title(
    val id: String,
    val name: String,
    val year: Int?,
    val kind: Kind,
    val category: String,
    val licence: String?,
    val movieFile: File?,
    val movieKey: String?,
    val seasons: List<Season>,
    val cardArt: File?,
    val backdropArt: File?,
    val logoArt: File?,
    val addedAt: Long,
    val tmdbHint: Int? = null,
) {
    val episodes: List<Episode> by lazy { seasons.flatMap { it.episodes } }
    val firstVideo: File? get() = movieFile ?: episodes.firstOrNull()?.file
    val searchKey: String = normalize(name + " " + (licence ?: ""))
    val familyKey: String = normalize(licence ?: name)

    fun metaLine(): String {
        val parts = mutableListOf<String>()
        year?.let { parts += it.toString() }
        if (kind == Kind.MOVIE) {
            parts += "Film"
        } else {
            parts += "Série"
            val real = seasons.count { it.number > 0 }
            parts += if (real > 1) "$real saisons" else "${episodes.size} épisode" + if (episodes.size > 1) "s" else ""
        }
        parts += category
        return parts.joinToString("  •  ")
    }
}

class Category(val name: String, val titles: List<Title>)

class Library(val profile: String, val titles: List<Title>, val categories: List<Category>) {
    /** Copie ne contenant que les titres autorisés. */
    fun filtered(keep: (Title) -> Boolean): Library {
        val ok = titles.filter(keep)
        if (ok.size == titles.size) return this
        val ids = ok.map { it.id }.toSet()
        return Library(
            profile,
            ok,
            categories.map { c -> Category(c.name, c.titles.filter { it.id in ids }) }.filter { it.titles.isNotEmpty() },
        )
    }

    val byId: Map<String, Title> = titles.associateBy { it.id }

    /** Licences explicites (dossiers regroupant plusieurs titres) avec au moins 2 titres. */
    val licences: Map<String, List<Title>> = titles
        .filter { it.licence != null }
        .groupBy { it.licence!! }
        .filterValues { it.size >= 2 }

    /** Titres de la même licence : dossier licence commun, ou nom qui commence pareil (Naruto / Naruto Shippuden). */
    fun sameLicence(t: Title): List<Title> = titles.filter { o ->
        o.id != t.id && (
            (t.licence != null && o.licence == t.licence) ||
                sameFamily(t.familyKey, o.familyKey) ||
                sameFamily(normalize(t.name), normalize(o.name))
            )
    }

    fun sameCategory(t: Title): List<Title> {
        val lic = sameLicence(t).map { it.id }.toSet()
        return titles.filter { it.category == t.category && it.id != t.id && it.id !in lic }
    }

    private fun sameFamily(a: String, b: String): Boolean {
        if (a.length < 4 || b.length < 4) return false
        if (a == b) return true
        return b.startsWith("$a ") || a.startsWith("$b ")
    }
}

fun normalize(s: String): String {
    val n = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    return n.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
}

object Scanner {
    val VIDEO_EXT = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "wmv", "ts", "m2ts", "mts", "webm",
        "flv", "mpg", "mpeg", "3gp", "ogv", "vob", "divx"
    )
    val IMAGE_EXT = listOf("jpg", "jpeg", "png", "webp")
    val SUB_EXT = setOf("srt", "ass", "ssa", "vtt", "ttml")
    private val IGNORED_DIRS = setOf(
        "extras", "extra", "sample", "samples", "featurettes", "subs", "subtitles",
        "sous-titres", "soustitres", "trailers", "bandes-annonces", "behind the scenes"
    )

    private val CARD_NAMES = listOf("vignette", "poster", "cover", "folder", "thumb", "landscape", "affiche", "image")
    private val BACKDROP_NAMES = listOf("fond", "backdrop", "fanart", "background", "banniere", "banner")
    private val LOGO_NAMES = listOf("logo", "clearlogo", "titre")

    private val SEASON_RE = Regex("""^(?:saison|season|s|staffel|temporada|stagione|livre|book)\s*[._-]?\s*(\d{1,3})(?!\d).*""", RegexOption.IGNORE_CASE)
    private val SPECIAL_RE = Regex("""^(?:specials?|spéciaux|speciaux|sp[ée]cial|ova|oav|ovas|oavs|bonus|hors[- ]s[ée]ries?|films?)$""", RegexOption.IGNORE_CASE)

    private val SXE_RE = Regex("""[Ss](\d{1,2})\s*[._-]?\s*[EeÉé](?:[Pp])?\s*(\d{1,4})""")
    private val NXN_RE = Regex("""(?<![\d])(\d{1,2})x(\d{2,3})(?!\d)""")
    private val EP_RE = Regex("""(?i)(?<![a-z])(?:épisode|episode|ep|eps|e)\s*[._#-]?\s*(\d{1,4})(?!\d)""")
    private val NUM_RE = Regex("""(?<![\dxXhH])(\d{1,4})(?![\dpPiI])""")
    private val NOT_EP_NUMBERS = setOf(264, 265, 480, 576, 720, 1080, 2160, 4320)
    private val JUNK_RE = Regex(
        """(?i)(?<=[\s(._-])(?:2160p|1080p|1080i|720p|480p|4k|uhd|bluray|blu-ray|bdrip|brrip|webrip|web-dl|webdl|hdtv|dvdrip|x264|x265|h264|h265|h\.264|h\.265|hevc|avc|10bit|10-bit|multi|vff|vfq|vfi|vf2|vostfr|truefrench|subfrench|hdr10|hdr|remux|aac|ac3|eac3|dts|atmos|ddp5|dd5)(?![a-z0-9]).*"""
    )

    fun isVideo(f: File): Boolean =
        f.isFile && !f.name.startsWith(".") && f.extension.lowercase(Locale.ROOT) in VIDEO_EXT

    private val natural = Comparator<File> { a, b -> naturalCompare(a.name, b.name) }

    fun naturalCompare(a: String, b: String): Int {
        val ra = Regex("""\d+|\D+""").findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
        val rb = Regex("""\d+|\D+""").findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val bx = x.trimStart('0').ifEmpty { "0" }
                val by = y.trimStart('0').ifEmpty { "0" }
                if (bx.length != by.length) bx.length - by.length else bx.compareTo(by)
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size - rb.size
    }

    fun listSorted(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") && !it.name.startsWith("\$") && !it.isHidden }
            .sortedWith(natural)

    private fun containsVideo(dir: File, depth: Int): Boolean {
        if (depth < 0) return false
        val kids = dir.listFiles() ?: return false
        if (kids.any { isVideo(it) }) return true
        return kids.any { it.isDirectory && !it.name.startsWith(".") && containsVideo(it, depth - 1) }
    }

    private fun collectVideos(dir: File, depth: Int = 2): List<File> {
        val out = mutableListOf<File>()
        for (k in listSorted(dir)) {
            if (isVideo(k)) out += k
            else if (k.isDirectory && depth > 0 && k.name.lowercase(Locale.ROOT) !in IGNORED_DIRS) out += collectVideos(k, depth - 1)
        }
        return out
    }

    // ---------- Profils ----------

    private val CATEGORY_WORDS = setOf(
        "film", "films", "serie", "series", "anime", "animes", "animation", "dessins animes", "dessin anime",
        "documentaire", "documentaires", "spectacles", "concerts", "emissions", "divers", "movies", "tv", "shows",
    )

    private fun hasProfileMarker(dir: File): Boolean =
        avatarOf(dir) != null || Access.fileOf(dir) != null

    /**
     * Dossiers de profils à la racine de Netflop.
     * Nouveau rangement : profils = dossiers sans vidéo (avec avatar / acces.txt), catégories partagées à côté.
     * Ancien rangement : si aucun dossier ne ressemble à un profil, chaque dossier est un profil.
     */
    fun profiles(root: File): List<File> {
        val dirs = listSorted(root).filter { it.isDirectory && !it.name.startsWith("_") }
        val profiles = dirs.filter { d ->
            hasProfileMarker(d) || (!containsVideo(d, 4) && normalize(displayCategory(d.name)) !in CATEGORY_WORDS)
        }
        return profiles.ifEmpty { dirs }
    }

    /** Catégories communes à tous les profils (dossiers à la racine qui ne sont pas des profils). */
    fun sharedCategories(root: File): List<File> {
        val profs = profiles(root).toSet()
        return listSorted(root).filter { it.isDirectory && !it.name.startsWith("_") && it !in profs }
    }

    fun avatarOf(profileDir: File): File? = findImage(profileDir, listOf("avatar", "profil", "profile"))

    fun introOverride(root: File): File? {
        val dirs = listOf(root, File(root, "_sons"))
        for (d in dirs) {
            val f = (d.listFiles() ?: continue).firstOrNull {
                it.isFile && it.nameWithoutExtension.equals("intro", true) &&
                    it.extension.lowercase(Locale.ROOT) in setOf("mp3", "wav", "ogg", "m4a", "aac", "flac")
            }
            if (f != null) return f
        }
        return null
    }

    // ---------- Scan d'un profil ----------

    fun scanProfile(root: File, profileDir: File): Library {
        val titles = mutableListOf<Title>()
        val cats = mutableListOf<Category>()
        val loose = mutableListOf<Title>()
        for (child in sharedCategories(root) + listSorted(profileDir)) {
            if (child.isDirectory) {
                val catName = displayCategory(child.name)
                val list = mutableListOf<Title>()
                scanEntries(root, child, catName, null, list)
                if (list.isNotEmpty()) {
                    val existing = cats.indexOfFirst { normalize(it.name) == normalize(catName) }
                    if (existing >= 0) {
                        cats[existing] = Category(cats[existing].name, cats[existing].titles + list)
                    } else {
                        cats += Category(catName, list)
                    }
                    titles += list
                }
            } else if (isVideo(child)) {
                loose += movieFromFile(root, child, "Divers", null)
            }
        }
        if (loose.isNotEmpty()) {
            cats += Category("Divers", loose)
            titles += loose
        }
        return Library(profileDir.name, titles, cats)
    }

    private fun scanEntries(root: File, dir: File, cat: String, licence: String?, out: MutableList<Title>) {
        for (x in listSorted(dir)) {
            if (x.isFile) {
                if (isVideo(x)) out += movieFromFile(root, x, cat, licence)
                continue
            }
            if (!x.isDirectory || x.name.lowercase(Locale.ROOT) in IGNORED_DIRS) continue
            val kids = listSorted(x)
            val videos = kids.filter { isVideo(it) }
            val subdirs = kids.filter {
                it.isDirectory && it.name.lowercase(Locale.ROOT) !in IGNORED_DIRS && containsVideo(it, 4)
            }
            val seasonDirs = subdirs.filter { seasonOf(it.name) != null }
            when {
                seasonDirs.isNotEmpty() -> out += seriesFromFolder(root, x, videos, seasonDirs, cat, licence)
                subdirs.isNotEmpty() -> {
                    val lic = licence ?: cleanName(x.name).first
                    scanEntries(root, x, cat, lic, out)
                }
                videos.size == 1 -> out += movieFromFolder(root, x, videos[0], cat, licence)
                videos.size > 1 -> out += seriesFromFolder(root, x, videos, emptyList(), cat, licence)
            }
        }
    }

    private val TMDB_RE = Regex("""(?i)[\[{(]\s*tmdb(?:id)?\s*[-=:]\s*(\d+)\s*[\]})]""")

    /** Identifiant TMDB forcé dans le nom : "Naruto {tmdb-46260}" ou "[tmdbid-46260]". */
    fun tmdbHint(name: String): Int? = TMDB_RE.find(name)?.groupValues?.get(1)?.toIntOrNull()

    fun seasonOf(name: String): Int? {
        val n = name.trim()
        if (SPECIAL_RE.matches(n)) return 0
        return SEASON_RE.matchEntire(n)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun rel(root: File, f: File): String =
        f.absolutePath.removePrefix(root.absolutePath).trimStart('/')

    private fun movieFromFile(root: File, f: File, cat: String, licence: String?): Title {
        val (name, year) = cleanName(f.nameWithoutExtension)
        val dir = f.parentFile ?: root
        val base = f.nameWithoutExtension
        return Title(
            id = rel(root, f),
            name = name.ifBlank { f.nameWithoutExtension },
            year = year,
            kind = Kind.MOVIE,
            category = cat,
            licence = licence,
            movieFile = f,
            movieKey = rel(root, f),
            seasons = emptyList(),
            cardArt = findImage(dir, listOf(base)),
            backdropArt = findImage(dir, BACKDROP_NAMES.map { "$base-$it" }),
            logoArt = findImage(dir, LOGO_NAMES.map { "$base-$it" }),
            addedAt = f.lastModified(),
            tmdbHint = tmdbHint(f.name),
        )
    }

    private fun movieFromFolder(root: File, dir: File, video: File, cat: String, licence: String?): Title {
        val (name, year) = cleanName(dir.name)
        return Title(
            id = rel(root, dir),
            name = name.ifBlank { dir.name },
            year = year,
            kind = Kind.MOVIE,
            category = cat,
            licence = licence,
            movieFile = video,
            movieKey = rel(root, video),
            seasons = emptyList(),
            cardArt = findImage(dir, CARD_NAMES + video.nameWithoutExtension),
            backdropArt = findImage(dir, BACKDROP_NAMES),
            logoArt = findImage(dir, LOGO_NAMES),
            addedAt = maxOf(dir.lastModified(), video.lastModified()),
            tmdbHint = tmdbHint(dir.name) ?: tmdbHint(video.name),
        )
    }

    private fun seriesFromFolder(
        root: File, dir: File, looseVideos: List<File>, seasonDirs: List<File>, cat: String, licence: String?,
    ): Title {
        val bySeason = linkedMapOf<Int, MutableList<File>>()
        for (sd in seasonDirs) {
            val n = seasonOf(sd.name) ?: continue
            bySeason.getOrPut(n) { mutableListOf() } += collectVideos(sd)
        }
        for (v in looseVideos) {
            val s = parseEpisode(v.nameWithoutExtension).first ?: if (seasonDirs.isEmpty()) 1 else 0
            bySeason.getOrPut(s) { mutableListOf() } += v
        }
        val seasons = bySeason.entries
            .sortedWith(compareBy<Map.Entry<Int, MutableList<File>>> { if (it.key == 0) Int.MAX_VALUE else it.key })
            .map { (n, files) ->
                val name = if (n == 0) {
                    seasonDirs.firstOrNull { seasonOf(it.name) == 0 }?.name ?: "Spéciaux"
                } else "Saison $n"
                Season(n, name, buildEpisodes(root, files.distinct().sortedWith(natural), n))
            }
            .filter { it.episodes.isNotEmpty() }
        val (name, year) = cleanName(dir.name)
        val newest = (seasons.flatMap { it.episodes }.maxOfOrNull { it.file.lastModified() } ?: 0L)
        return Title(
            id = rel(root, dir),
            name = name.ifBlank { dir.name },
            year = year,
            kind = Kind.SERIES,
            category = cat,
            licence = licence,
            movieFile = null,
            movieKey = null,
            seasons = seasons,
            cardArt = findImage(dir, CARD_NAMES),
            backdropArt = findImage(dir, BACKDROP_NAMES),
            logoArt = findImage(dir, LOGO_NAMES),
            addedAt = maxOf(dir.lastModified(), newest),
            tmdbHint = tmdbHint(dir.name),
        )
    }

    private fun buildEpisodes(root: File, files: List<File>, season: Int): List<Episode> {
        val used = HashSet<Int>()
        val eps = files.mapIndexed { idx, f ->
            val (_, ep, title) = parseEpisode(f.nameWithoutExtension)
            var n = ep ?: (idx + 1)
            if (!used.add(n)) {
                n = (used.maxOrNull() ?: 0) + 1
                used.add(n)
            }
            Episode(rel(root, f), f, season, n, title)
        }
        return eps.sortedWith(compareBy<Episode> { it.number }.thenComparator { a, b -> naturalCompare(a.file.name, b.file.name) })
    }

    /** (saison, épisode, titre) depuis un nom de fichier. */
    fun parseEpisode(raw: String): Triple<Int?, Int?, String> {
        var s = raw.replace(Regex("""\[[^\]]*]"""), " ").replace(Regex("""\{[^}]*}"""), " ")
        if (!s.contains(' ')) s = s.replace('.', ' ')
        s = s.replace('_', ' ')
        var season: Int? = null
        var ep: Int? = null
        var rest = ""
        val m = SXE_RE.find(s) ?: NXN_RE.find(s)
        if (m != null) {
            season = m.groupValues[1].toIntOrNull()
            ep = m.groupValues[2].toIntOrNull()
            rest = s.substring(m.range.last + 1)
        } else {
            val e = EP_RE.find(s)
            if (e != null) {
                ep = e.groupValues[1].toIntOrNull()
                rest = s.substring(e.range.last + 1)
            } else {
                val nums = NUM_RE.findAll(s).filter {
                    val v = it.value.toInt()
                    v !in NOT_EP_NUMBERS && !(it.value.length == 4 && v in 1920..2039)
                }.toList()
                val last = nums.lastOrNull()
                if (last != null) {
                    ep = last.value.toInt()
                    rest = s.substring(last.range.last + 1)
                }
            }
        }
        val title = cleanEpisodeTitle(rest)
        return Triple(season, ep, title)
    }

    private fun cleanEpisodeTitle(rest: String): String {
        var t = " $rest "
        t = JUNK_RE.replace(t, " ")
        t = t.replace(Regex("""\(\s*\)"""), " ")
        t = t.replace(Regex("""\s+"""), " ").trim().trim('-', '–', '.', ' ', ':', '_', '|')
        return t.trim()
    }

    /** Nettoie un nom de dossier/fichier → (nom, année). */
    fun cleanName(raw: String): Pair<String, Int?> {
        var s = raw.replace(Regex("""\[[^\]]*]"""), " ").replace(Regex("""\{[^}]*}"""), " ")
            .replace(Regex("""(?i)\(\s*tmdb(?:id)?\s*[-=:]\s*\d+\s*\)"""), " ")
        s = if (!s.contains(' ')) s.replace('.', ' ').replace('_', ' ') else s.replace('_', ' ')
        var year: Int? = null
        val paren = Regex("""\((19\d{2}|20\d{2})\)""").find(s)
        if (paren != null) {
            year = paren.groupValues[1].toInt()
            s = s.removeRange(paren.range)
        } else {
            val bare = Regex("""(?<!\d)(19[2-9]\d|20[0-3]\d)(?!\d)""").find(s)
            if (bare != null && bare.range.first > 1) {
                year = bare.groupValues[1].toInt()
                s = s.substring(0, bare.range.first)
            }
        }
        s = JUNK_RE.replace(" $s ", " ")
        s = s.replace(Regex("""\(\s*\)"""), " ")
        s = s.replace(Regex("""\s+"""), " ").trim().trim('-', '–', '.', ' ', '_', '(')
        return s.trim() to year
    }

    fun displayCategory(raw: String): String =
        raw.replace(Regex("""^\d{1,3}\s*[-._)]\s*"""), "").trim().ifEmpty { raw }

    private fun findImage(dir: File, names: List<String>): File? {
        val images = (dir.listFiles() ?: return null).filter {
            it.isFile && it.extension.lowercase(Locale.ROOT) in IMAGE_EXT
        }
        if (images.isEmpty()) return null
        val byBase = images.associateBy { it.nameWithoutExtension.lowercase(Locale.ROOT) }
        for (n in names) byBase[n.lowercase(Locale.ROOT)]?.let { return it }
        return null
    }

    /** Image d'épisode posée à côté du fichier : "S01E01.jpg". */
    fun siblingImage(video: File): File? {
        val dir = video.parentFile ?: return null
        return findImage(dir, listOf(video.nameWithoutExtension, video.nameWithoutExtension + "-thumb"))
    }

    /** Sous-titres externes : "Episode.srt", "Episode.fr.srt", "Episode.french.forced.srt"… */
    fun externalSubs(video: File): List<Pair<File, String?>> {
        val dir = video.parentFile ?: return emptyList()
        val base = video.nameWithoutExtension
        return (dir.listFiles() ?: return emptyList())
            .filter {
                it.isFile && it.extension.lowercase(Locale.ROOT) in SUB_EXT &&
                    (it.nameWithoutExtension == base || it.nameWithoutExtension.startsWith("$base."))
            }
            .sortedWith(natural)
            .map { f ->
                val tags = f.nameWithoutExtension.removePrefix(base).split('.', '_', ' ').filter { it.isNotBlank() }
                f to tags.map { langCode(it) }.firstOrNull { it != null }
            }
    }

    private fun langCode(tag: String): String? = when (tag.lowercase(Locale.ROOT)) {
        "fr", "fre", "fra", "french", "francais", "français", "vf", "vff", "vfq" -> "fr"
        "en", "eng", "english", "anglais" -> "en"
        "ja", "jp", "jpn", "japanese", "japonais" -> "ja"
        "es", "spa", "spanish", "espagnol" -> "es"
        "de", "ger", "deu", "german", "allemand" -> "de"
        "it", "ita", "italian", "italien" -> "it"
        "nl", "dut", "nld", "dutch", "nederlands", "neerlandais" -> "nl"
        "pt", "por", "portuguese" -> "pt"
        "ar", "ara", "arabic", "arabe" -> "ar"
        "ko", "kor", "korean", "coreen" -> "ko"
        "zh", "chi", "zho", "chinese", "chinois" -> "zh"
        else -> null
    }
}
