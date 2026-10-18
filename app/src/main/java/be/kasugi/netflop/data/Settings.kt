package be.kasugi.netflop.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class Role(val label: String) {
    ADMIN("Administrateur"),
    STANDARD("Standard"),
    KIDS("Enfant"),
}

/** Réglages d'un profil, gardés dans la mémoire de l'appli. */
data class ProfileSettings(
    val role: Role = Role.STANDARD,
    val ageMax: Int? = null,
    /** true = un nouveau titre reste caché tant qu'il n'est pas coché. */
    val newHidden: Boolean = false,
    val hidden: Set<String> = emptySet(),
    val allowed: Set<String> = emptySet(),
    val pin: String? = null,
) {
    val isOpen: Boolean get() = ageMax == null && !newHidden && hidden.isEmpty()

    fun isVisible(t: Title, certification: String?): Boolean {
        if (t.id in allowed) return true
        if (newHidden || t.id in hidden) return false
        val max = ageMax ?: return true
        val age = Access.certAge(certification) ?: return true
        return age <= max
    }

    fun withVisible(id: String, visible: Boolean): ProfileSettings =
        if (visible) copy(allowed = allowed + id, hidden = hidden - id)
        else copy(hidden = hidden + id, allowed = allowed - id)

    fun withVisible(ids: Collection<String>, visible: Boolean): ProfileSettings =
        if (visible) copy(allowed = allowed + ids, hidden = hidden - ids.toSet())
        else copy(hidden = hidden + ids, allowed = allowed - ids.toSet())

    fun toJson(): JSONObject = JSONObject()
        .put("role", role.name)
        .put("ageMax", ageMax ?: 0)
        .put("newHidden", newHidden)
        .put("hidden", JSONArray(hidden.toList()))
        .put("allowed", JSONArray(allowed.toList()))
        .put("pin", pin ?: "")

    companion object {
        fun fromJson(o: JSONObject): ProfileSettings {
            fun set(k: String): Set<String> {
                val a = o.optJSONArray(k) ?: return emptySet()
                return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
            }
            return ProfileSettings(
                role = runCatching { Role.valueOf(o.optString("role")) }.getOrDefault(Role.STANDARD),
                ageMax = o.optInt("ageMax").takeIf { it > 0 },
                newHidden = o.optBoolean("newHidden"),
                hidden = set("hidden"),
                allowed = set("allowed"),
                pin = o.optString("pin").takeIf { it.length == 4 },
            )
        }
    }
}

object SettingsStore {
    private lateinit var file: File
    private val map = mutableStateMapOf<String, ProfileSettings>()

    fun init(context: Context) {
        file = File(context.filesDir, "profiles-settings.json")
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                for (k in o.keys()) map[k] = ProfileSettings.fromJson(o.getJSONObject(k))
            }
        } catch (_: Throwable) {
        }
    }

    private fun save() {
        try {
            val o = JSONObject()
            for ((k, v) in map) o.put(k, v.toJson())
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(o.toString())
                tmp.delete()
            }
        } catch (_: Throwable) {
        }
    }

    /** Par défaut, un profil dont le nom contient "parent" est administrateur. */
    fun get(profile: String): ProfileSettings =
        map[profile] ?: ProfileSettings(role = if (normalize(profile).contains("parent")) Role.ADMIN else Role.STANDARD)

    fun set(profile: String, s: ProfileSettings) {
        map[profile] = s
        save()
    }

    /** Peut ouvrir les paramètres. S'il n'y a aucun admin, tout profil non-enfant le peut (pour démarrer). */
    fun canManage(profile: String, all: List<String>): Boolean {
        val r = get(profile).role
        if (r == Role.ADMIN) return true
        return r != Role.KIDS && all.none { get(it).role == Role.ADMIN }
    }

    fun adminCount(all: List<String>): Int = all.count { get(it).role == Role.ADMIN }
}
