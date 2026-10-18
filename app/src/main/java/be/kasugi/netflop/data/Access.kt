package be.kasugi.netflop.data

import java.io.File

/**
 * Autorisations d'un profil, lues dans un fichier texte posé dans son dossier (acces.txt).
 *
 *   age max: 12
 *   tout bloquer: non
 *   bloquer: FILM
 *   bloquer: Animés/One Piece
 *   autoriser: FILM/La Reine des neiges
 */
data class Access(
    val ageMax: Int? = null,
    val blockAll: Boolean = false,
    val block: List<String> = emptyList(),
    val allow: List<String> = emptyList(),
) {
    val isOpen: Boolean get() = ageMax == null && !blockAll && block.isEmpty()

    fun allows(t: Title, certification: String?): Boolean {
        val allowed = matchesAny(t, allow)
        val visible = when {
            blockAll -> allowed
            matchesAny(t, block) -> allowed
            else -> true
        }
        if (!visible) return false
        if (allowed) return true // une autorisation explicite passe outre l'âge
        val max = ageMax ?: return true
        val age = certAge(certification) ?: return true
        return age <= max
    }

    private fun matchesAny(t: Title, rules: List<String>): Boolean {
        if (rules.isEmpty()) return false
        val cat = normalize(t.category)
        val name = normalize(t.name)
        val lic = t.licence?.let { normalize(it) }
        val path = normalize(t.id)
        return rules.any { r ->
            r == cat || r == name || r == lic ||
                r == "$cat $name" || (lic != null && r == "$cat $lic") ||
                path == r || path.startsWith("$r ")
        }
    }

    companion object {
        private val FILE_NAMES = listOf("acces.txt", "accès.txt", "access.txt", "profil.txt", "config.txt")

        fun fileOf(profileDir: File): File? {
            val files = profileDir.listFiles() ?: return null
            return FILE_NAMES.firstNotNullOfOrNull { n -> files.firstOrNull { it.isFile && it.name.equals(n, true) } }
        }

        fun load(profileDir: File?): Access {
            val f = profileDir?.let { fileOf(it) } ?: return Access()
            return try {
                parse(f.readText())
            } catch (_: Throwable) {
                Access()
            }
        }

        fun parse(text: String): Access {
            var age: Int? = null
            var all = false
            val block = mutableListOf<String>()
            val allow = mutableListOf<String>()
            for (raw in text.lineSequence()) {
                val line = raw.trim().removePrefix("﻿")
                if (line.isEmpty() || line.startsWith("#")) continue
                val i = line.indexOf(':')
                if (i <= 0) continue
                val key = normalize(line.substring(0, i))
                val value = line.substring(i + 1).trim()
                when (key) {
                    "age max", "age maximum", "age" -> age = Regex("\\d+").find(value)?.value?.toIntOrNull()
                    "tout bloquer", "bloquer tout" -> all = normalize(value) in setOf("oui", "yes", "vrai", "true", "1")
                    "bloquer", "cacher", "interdire" -> normalize(value).takeIf { it.isNotEmpty() }?.let { block += it }
                    "autoriser", "permettre", "montrer" -> normalize(value).takeIf { it.isNotEmpty() }?.let { allow += it }
                }
            }
            return Access(age, all, block, allow)
        }

        /** Âge minimum d'une classification TMDB (FR, BE ou US). */
        fun certAge(c: String?): Int? {
            val v = c?.trim()?.uppercase() ?: return null
            if (v.isEmpty() || v == "NR") return null
            if (!v.startsWith("TV-")) Regex("\\d+").find(v)?.value?.toIntOrNull()?.let { return it }
            return when (v) {
                "U", "TP", "AL", "KT", "G", "TV-Y", "TV-G" -> 0
                "TV-Y7", "TV-Y7-FV" -> 7
                "PG", "TV-PG" -> 10
                "PG-13" -> 13
                "TV-14" -> 14
                "R", "TV-MA" -> 17
                "NC-17" -> 18
                else -> null
            }
        }
    }
}
