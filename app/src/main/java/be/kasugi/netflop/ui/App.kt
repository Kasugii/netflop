package be.kasugi.netflop.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import be.kasugi.netflop.data.ProfileSettings
import be.kasugi.netflop.data.SettingsStore
import be.kasugi.netflop.data.Library
import be.kasugi.netflop.data.ProfileStore
import be.kasugi.netflop.data.Scanner
import be.kasugi.netflop.data.StorageFinder
import be.kasugi.netflop.data.Title
import be.kasugi.netflop.data.Tmdb
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class Section(val label: String) {
    SEARCH("Recherche"),
    HOME("Accueil"),
    SERIES("Séries"),
    MOVIES("Films"),
    MYLIST("Ma liste"),
}

sealed interface Screen {
    data object Intro : Screen
    data object Profiles : Screen
    data object Settings : Screen
    data class Browse(val section: Section) : Screen
    data class Details(val titleId: String) : Screen
    data class Player(val titleId: String, val episodeKey: String?, val fromStart: Boolean) : Screen
}

class AppState(private val context: Context) {
    val stack = mutableStateListOf<Screen>(Screen.Intro)
    val top: Screen get() = stack.last()

    var hasPermission by mutableStateOf(false)
    var root by mutableStateOf<File?>(null)
    var profiles by mutableStateOf<List<File>>(emptyList())
    var volumes by mutableStateOf<List<File>>(emptyList())

    var profileDir by mutableStateOf<File?>(null)
    var profileIndex by mutableStateOf(0)
    /** Bibliothèque complète (tout le disque) et autorisations du profil. */
    var fullLibrary by mutableStateOf<Library?>(null)
    private var viewKey: Any? = null
    private var viewLib: Library? = null

    /** Réglages du profil ouvert. */
    val settings: ProfileSettings
        get() = profileDir?.let { SettingsStore.get(it.name) } ?: ProfileSettings()

    val profileNames: List<String> get() = profiles.map { it.name }

    /** Le profil ouvert peut-il gérer les paramètres ? */
    val canManage: Boolean
        get() = profileDir?.let { SettingsStore.canManage(it.name, profileNames) } ?: false

    /** Ce que le profil a le droit de voir. */
    val library: Library?
        get() {
            val full = fullLibrary ?: return null
            val acc = settings
            val metaSig = if (acc.ageMax != null) Tmdb.metas.size else -1
            val key = Triple(full, acc, metaSig)
            if (key != viewKey) {
                viewLib = if (acc.isOpen && acc.allowed.isEmpty()) full
                else full.filtered { acc.isVisible(it, Tmdb.metas[it.id]?.certification) }
                viewKey = key
            }
            return viewLib
        }
    var store by mutableStateOf<ProfileStore?>(null)
    var loading by mutableStateOf(false)
    var toast by mutableStateOf<String?>(null)

    var searchQuery by mutableStateOf("")

    /** Dernier plantage à afficher. */
    var crash by mutableStateOf<String?>(null)

    /** Le contenu (et non le menu) doit reprendre le focus à l'arrivée sur l'écran. */
    var wantContentFocus = true

    private val listStates = HashMap<String, LazyListState>()
    private val gridStates = HashMap<String, LazyGridState>()
    val lastFocus = HashMap<String, String>()
    val hero = HashMap<Section, String>()

    fun listState(key: String): LazyListState = listStates.getOrPut(key) { LazyListState() }
    fun gridState(key: String): LazyGridState = gridStates.getOrPut(key) { LazyGridState() }

    fun refreshRoot() {
        hasPermission = ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        volumes = try {
            StorageFinder.volumes(context)
        } catch (_: Throwable) {
            emptyList()
        }
        root = if (hasPermission) StorageFinder.findNetflop(context) else null
        profiles = root?.let { Scanner.profiles(it) } ?: emptyList()
        Tmdb.configure(root)
    }

    fun title(id: String): Title? = library?.byId?.get(id)

    suspend fun openProfile(dir: File, index: Int) {
        val r = root ?: return
        loading = true
        val lib = try {
            withContext(Dispatchers.IO) { Scanner.scanProfile(r, dir) }
        } finally {
            loading = false
        }
        loading = true
        val st = withContext(Dispatchers.IO) {
            ProfileStore(File(context.filesDir, "profiles/" + dir.name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json"))
        }
        listStates.clear()
        gridStates.clear()
        lastFocus.clear()
        hero.clear()
        searchQuery = ""
        profileDir = dir
        profileIndex = index
        fullLibrary = lib
        store = st
        loading = false
        wantContentFocus = true
        prefetch(lib)
    }

    private val scope = MainScope()
    private var prefetchJob: Job? = null

    /** Récupère en arrière-plan images et résumés TMDB de tous les titres. */
    private fun prefetch(lib: Library) {
        if (!Tmdb.enabled) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            // D'abord ce qui est en cours / récent, puis le reste
            val order = (store?.recentlyWatched(lib).orEmpty() + lib.titles).distinctBy { it.id }
            try {
                order.chunked(8).forEach { chunk ->
                    chunk.map { t -> launch { runCatching { Tmdb.ensure(t) } } }.joinAll()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
    }

    suspend fun rescan() {
        val r = root ?: return
        val dir = profileDir ?: return
        loading = true
        Tmdb.configure(r)
        val lib = withContext(Dispatchers.IO) { Scanner.scanProfile(r, dir) }
        fullLibrary = lib
        loading = false
        prefetch(lib)
        toast = "Bibliothèque actualisée : ${lib.titles.size} titres" +
            if (Tmdb.enabled) " · infos TMDB activées" else ""
    }

    fun push(s: Screen) {
        stack.add(s)
    }

    fun replaceTop(s: Screen) {
        stack[stack.lastIndex] = s
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        wantContentFocus = true
    }

    fun backToProfiles() {
        while (stack.size > 1 && stack.last() != Screen.Profiles) stack.removeAt(stack.lastIndex)
        if (stack.last() != Screen.Profiles) stack.add(Screen.Profiles)
    }
}

@Composable
fun NetflopApp(state: AppState, requestPermission: () -> Unit) {
    val crash = state.crash
    if (crash != null) {
        CrashScreen(crash) { state.crash = null }
        return
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        AnimatedContent(
            targetState = state.top,
            contentKey = { if (it is Screen.Browse) "browse" else it },
            transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
            label = "screens",
        ) { screen ->
            when (screen) {
                Screen.Intro -> IntroScreen(state) { state.replaceTop(Screen.Profiles) }
                Screen.Profiles -> ProfilesScreen(state, requestPermission)
                Screen.Settings -> SettingsScreen(state)
                is Screen.Browse -> BrowseScreen(state, screen.section)
                is Screen.Details -> {
                    val t = state.title(screen.titleId)
                    if (t != null) DetailsScreen(state, t) else MissingScreen(state)
                }
                is Screen.Player -> {
                    val t = state.title(screen.titleId)
                    if (t != null) PlayerScreen(state, t, screen.episodeKey, screen.fromStart) else MissingScreen(state)
                }
            }
        }
    }
}
