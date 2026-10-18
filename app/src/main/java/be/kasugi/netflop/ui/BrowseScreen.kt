package be.kasugi.netflop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.kasugi.netflop.data.Kind
import be.kasugi.netflop.data.Library
import be.kasugi.netflop.data.ProfileStore
import be.kasugi.netflop.data.Title
import be.kasugi.netflop.data.Tmdb
import be.kasugi.netflop.data.normalize
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RowData(val key: String, val title: String, val titles: List<Title>)

private val RailCollapsed = 76.dp
private val RailExpanded = 250.dp

fun buildRows(lib: Library, store: ProfileStore, section: Section): List<RowData> {
    val filter: (Title) -> Boolean = when (section) {
        Section.SERIES -> { t -> t.kind == Kind.SERIES }
        Section.MOVIES -> { t -> t.kind == Kind.MOVIE }
        else -> { _ -> true }
    }
    val rows = mutableListOf<RowData>()
    val cont = store.continueWatching(lib).filter(filter)
    if (cont.isNotEmpty()) rows += RowData("continue", "Reprendre avec ${lib.profile}", cont)
    val newest = lib.titles.filter(filter).sortedByDescending { it.addedAt }.take(20)
    if (newest.size >= 4 && section == Section.HOME) rows += RowData("new", "Nouveautés", newest)
    val recent = store.recentlyWatched(lib).filter(filter)
    if (recent.isNotEmpty()) rows += RowData("recent", "Regardés récemment", recent)
    for (c in lib.categories) {
        val list = c.titles.filter(filter)
        if (list.isNotEmpty()) rows += RowData("cat:" + c.name, c.name, list)
    }
    if (section == Section.HOME || section == Section.MOVIES || section == Section.SERIES) {
        val byGenre = linkedMapOf<String, MutableList<Title>>()
        for (t in lib.titles.filter(filter)) {
            Tmdb.metas[t.id]?.genres?.forEach { g -> byGenre.getOrPut(g) { mutableListOf() } += t }
        }
        byGenre.entries.filter { it.value.size >= 3 }.sortedByDescending { it.value.size }.take(8).forEach { (g, list) ->
            rows += RowData("genre:$g", g, list)
        }
    }
    for ((lic, list) in lib.licences) {
        val f = list.filter(filter)
        if (f.size >= 2) rows += RowData("lic:$lic", "L'univers $lic", f)
    }
    if (section != Section.HOME) {
        val all = lib.titles.filter(filter).sortedBy { normalize(it.name) }
        if (all.isNotEmpty()) rows += RowData("all", if (section == Section.SERIES) "Toutes les séries" else "Tous les films", all)
    }
    return rows
}

@Composable
fun BrowseScreen(state: AppState, section: Section) {
    val scope = rememberCoroutineScope()
    BackHandler {
        Sounds.back()
        if (section != Section.HOME) {
            state.wantContentFocus = true
            state.replaceTop(Screen.Browse(Section.HOME))
        } else {
            state.backToProfiles()
        }
    }
    Box(Modifier.fillMaxSize().background(Bg)) {
        Box(Modifier.fillMaxSize().padding(start = RailCollapsed)) {
            Crossfade(section, animationSpec = tween(220), label = "section") { sec ->
                when (sec) {
                    Section.SEARCH -> SearchContent(state)
                    Section.MYLIST -> MyListContent(state)
                    else -> RowsContent(state, sec)
                }
            }
        }
        NavRail(state, section, onSelect = { s ->
            if (s != section) {
                state.wantContentFocus = false
                state.replaceTop(Screen.Browse(s))
            }
        }, onRefresh = { scope.launch { state.rescan() } })

        val toast = state.toast
        LaunchedEffect(toast) {
            if (toast != null) {
                delay(2500)
                state.toast = null
            }
        }
        AnimatedVisibility(
            visible = toast != null,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 30.dp),
        ) {
            Text(
                toast ?: "",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xE6262626))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }
}

// ---------------- Menu latéral ----------------

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NavRail(state: AppState, section: Section, onSelect: (Section) -> Unit, onRefresh: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (hasFocus) RailExpanded else RailCollapsed, tween(200), label = "rail")
    val selectedReq = remember { FocusRequester() }
    Column(
        Modifier
            .fillMaxHeight()
            .width(width)
            .background(
                if (hasFocus) Brush.horizontalGradient(listOf(Color(0xF2000000), Color(0xCC000000), Color.Transparent))
                else Brush.horizontalGradient(listOf(Color(0xCC000000), Color.Transparent))
            )
            .onFocusChanged { hasFocus = it.hasFocus }
            .focusRestorer { selectedReq }
            .padding(start = 18.dp, top = 26.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Profil
        RailItem(
            label = state.profileDir?.name ?: "Profil",
            expanded = hasFocus,
            selected = false,
            icon = {
                val dir = state.profileDir
                if (dir != null) ProfileAvatar(dir, state.profileIndex, Modifier.size(28.dp).clip(RoundedCornerShape(4.dp)))
            },
            onClick = {
                Sounds.back()
                state.backToProfiles()
            },
        )
        Spacer(Modifier.height(28.dp))
        val items = listOf(
            Section.SEARCH to Icons.Filled.Search,
            Section.HOME to Icons.Filled.Home,
            Section.SERIES to Icons.Filled.Star,
            Section.MOVIES to Icons.Filled.PlayArrow,
            Section.MYLIST to Icons.Filled.List,
        )
        for ((s, ic) in items) {
            RailItem(
                label = s.label,
                expanded = hasFocus,
                selected = s == section,
                icon = { c -> RailIcon(ic, c) },
                focusRequester = if (s == section) selectedReq else null,
                onClick = { onSelect(s) },
            )
        }
        Spacer(Modifier.height(28.dp))
        RailItem(
            label = "Actualiser",
            expanded = hasFocus,
            selected = false,
            icon = { c -> RailIcon(Icons.Filled.Refresh, c) },
            onClick = onRefresh,
        )
    }
}

@Composable
private fun RailIcon(ic: ImageVector, color: Color) {
    Icon(ic, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
}

@Composable
private fun RailItem(
    label: String,
    expanded: Boolean,
    selected: Boolean,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val color = when {
        focused -> Color.White
        selected -> Color.White
        else -> Color(0xFF8C8C8C)
    }
    Row(
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (it.isFocused != focused) {
                    focused = it.isFocused
                    if (it.isFocused) {
                        Sounds.nav()
                        onFocused()
                    }
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                Sounds.select()
                onClick()
            }
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color(0x33FFFFFF) else Color.Transparent)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { icon(color) }
        if (expanded) {
            Spacer(Modifier.width(18.dp))
            Text(
                label,
                color = color,
                fontSize = if (focused) 19.sp else 17.sp,
                fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(150.dp),
            )
        }
        if (selected && !expanded) {
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(4.dp).clip(RoundedCornerShape(2.dp)).background(NetRed))
        }
    }
}

// ---------------- Accueil / Séries / Films ----------------

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun RowsContent(state: AppState, section: Section) {
    val lib = state.library ?: return
    val store = state.store ?: return
    val version = store.version
    val metaCount = Tmdb.metas.size
    val rows = remember(lib, section, version, metaCount) { buildRows(lib, store, section) }
    if (rows.isEmpty()) {
        EmptyMessage("Rien ici pour l'instant", "Ajoute des vidéos dans le dossier ${lib.profile} du disque.")
        return
    }
    val heroId = state.hero[section]
    var hero by remember(section) { mutableStateOf(heroId?.let { lib.byId[it] } ?: rows.first().titles.first()) }

    // Clé de la vignette qui doit recevoir le focus
    val restoreKey = remember(rows) {
        val saved = state.lastFocus[section.name]
        val exists = saved != null && rows.any { r -> r.titles.any { "${r.key}/${it.id}" == saved } }
        if (exists) saved else "${rows.first().key}/${rows.first().titles.first().id}"
    }
    val density = LocalDensity.current
    val vSpec = remember(density) { PivotSpec(with(density) { 44.dp.toPx() }) }
    val hSpec = remember(density) { PivotSpec(with(density) { 44.dp.toPx() }) }

    Box(Modifier.fillMaxSize()) {
        // Image de fond du titre sélectionné
        Crossfade(hero, animationSpec = tween(450), label = "heroBg", modifier = Modifier.align(Alignment.TopEnd)) { t ->
            val img = rememberTitleImage(t, backdrop = true)
            FileImage(
                img,
                Modifier
                    .fillMaxWidth(0.74f)
                    .fillMaxHeight(0.7f)
                    .heroFade(),
                alignment = Alignment.TopCenter,
            )
        }
        Column(Modifier.fillMaxSize()) {
            HeroInfo(hero, store, Modifier.fillMaxWidth().weight(0.46f))
            CompositionLocalProvider(LocalBringIntoViewSpec provides vSpec) {
                LazyColumn(
                    state = state.listState("rows-${section.name}"),
                    modifier = Modifier.fillMaxWidth().weight(0.54f),
                    contentPadding = PaddingValues(bottom = 400.dp),
                ) {
                    items(rows, key = { it.key }) { row ->
                        Column(Modifier.padding(top = 4.dp)) {
                            SectionTitle(row.title, Modifier.padding(start = 44.dp, bottom = 2.dp))
                            CompositionLocalProvider(LocalBringIntoViewSpec provides hSpec) {
                                LazyRow(
                                    state = state.listState("row-${section.name}-${row.key}"),
                                    contentPadding = PaddingValues(start = 44.dp, end = 300.dp, top = 12.dp, bottom = 14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    modifier = Modifier.focusRestorer(),
                                ) {
                                    items(row.titles, key = { it.id }) { t ->
                                        val cardKey = "${row.key}/${t.id}"
                                        val req = if (cardKey == restoreKey) remember { FocusRequester() } else null
                                        TitleCard(
                                            t = t,
                                            progress = store.cardProgress(t),
                                            focusRequester = req,
                                            onFocused = {
                                                hero = t
                                                state.hero[section] = t.id
                                                state.lastFocus[section.name] = cardKey
                                            },
                                            onClick = {
                                                state.wantContentFocus = true
                                                state.push(Screen.Details(t.id))
                                            },
                                        )
                                        if (req != null) {
                                            AutoFocus(req, enabled = state.wantContentFocus) { state.wantContentFocus = false }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroInfo(t: Title, store: ProfileStore, modifier: Modifier) {
    AnimatedContent(
        targetState = t,
        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) },
        label = "heroInfo",
        modifier = modifier,
    ) { title ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 44.dp, top = 34.dp, end = 40.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            TitleHeading(title, maxWidthFraction = 0.42f, fontSizeSp = 38)
            Spacer(Modifier.height(10.dp))
            MetaRow(title)
            Spacer(Modifier.height(8.dp))
            ResumeLine(title, store)
            Spacer(Modifier.height(6.dp))
            Overview(title, maxLines = 3, modifier = Modifier.fillMaxWidth(0.5f))
        }
    }
}

@Composable
fun TitleHeading(t: Title, maxWidthFraction: Float, fontSizeSp: Int) {
    val logo = logoModel(t)
    if (logo != null) {
        FileImage(
            logo,
            Modifier
                .fillMaxWidth(maxWidthFraction)
                .height((fontSizeSp * 1.9f).dp),
            alignment = Alignment.BottomStart,
            contentScale = ContentScale.Fit,
        )
    } else {
        Text(
            t.name,
            color = Color.White,
            fontSize = fontSizeSp.sp,
            lineHeight = (fontSizeSp * 1.08f).sp,
            fontWeight = FontWeight.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(maxWidthFraction + 0.1f),
        )
    }
}

@Composable
fun ResumeLine(t: Title, store: ProfileStore) {
    store.version
    val key = store.playKey(t)
    val p = store.get(key)
    if (p == null || p.done || p.pos < 10_000) {
        if (t.kind == Kind.SERIES && store.lastWatchedAt(t) > 0) {
            val ep = store.nextEpisode(t)
            if (ep != null) MetaText("À suivre : S${ep.season}:É${ep.number} « ${ep.label} »")
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProgressLine(p.fraction, Modifier.width(160.dp))
        Spacer(Modifier.width(12.dp))
        val prefix = if (t.kind == Kind.SERIES) store.nextEpisode(t)?.let { "S${it.season}:É${it.number}  ·  " } ?: "" else ""
        MetaText(prefix + "Il reste ${p.remainingMin} min")
    }
}

@Composable
fun EmptyMessage(title: String, text: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(text, color = TextGray, fontSize = 16.sp)
    }
}

// ---------------- Ma liste (tout) ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MyListContent(state: AppState) {
    val lib = state.library ?: return
    val store = state.store ?: return
    store.version
    val all = remember(lib) { lib.titles.sortedBy { normalize(it.name) } }
    val density = LocalDensity.current
    val spec = remember(density) { PivotSpec(with(density) { 90.dp.toPx() }) }
    if (all.isEmpty()) {
        EmptyMessage("Ta liste est vide", "Ajoute des vidéos dans le dossier ${lib.profile} du disque.")
        return
    }
    val restore = state.lastFocus[Section.MYLIST.name] ?: all.first().id
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = state.gridState("mylist"),
            contentPadding = PaddingValues(start = 44.dp, end = 44.dp, top = 30.dp, bottom = 300.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Ma liste", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(14.dp))
                    MetaText("${all.size} titres")
                }
            }
            items(all, key = { it.id }) { t ->
                val req = if (t.id == restore) remember { FocusRequester() } else null
                Column {
                    TitleCard(
                        t = t,
                        progress = store.cardProgress(t),
                        width = 200.dp,
                        focusRequester = req,
                        onFocused = { state.lastFocus[Section.MYLIST.name] = t.id },
                        onClick = {
                            state.wantContentFocus = true
                            state.push(Screen.Details(t.id))
                        },
                    )
                }
                if (req != null) AutoFocus(req, enabled = state.wantContentFocus) { state.wantContentFocus = false }
            }
        }
    }
}

// ---------------- Recherche ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchContent(state: AppState) {
    val lib = state.library ?: return
    val store = state.store ?: return
    val q = state.searchQuery
    val metaCount = Tmdb.metas.size
    val results = remember(lib, q, metaCount) {
        val words = normalize(q).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) {
            (store.recentlyWatched(lib) + lib.titles.sortedByDescending { it.addedAt }).distinctBy { it.id }
        } else {
            lib.titles.filter { t ->
                val alt = Tmdb.metas[t.id]?.let { normalize(it.name + " " + it.genres.joinToString(" ")) } ?: ""
                words.all { w -> t.searchKey.contains(w) || alt.contains(w) }
            }
                .sortedBy { if (it.searchKey.startsWith(words.first())) 0 else 1 }
        }
    }
    val density = LocalDensity.current
    val spec = remember(density) { PivotSpec(with(density) { 60.dp.toPx() }) }
    Row(Modifier.fillMaxSize().padding(start = 30.dp, top = 30.dp)) {
        SearchKeyboard(state, Modifier.width(300.dp))
        Spacer(Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (q.isBlank()) "Suggestions pour toi" else "Résultats pour « $q »",
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            if (results.isEmpty()) {
                MetaText("Aucun titre ne correspond. Essaie un autre mot.")
            } else {
                CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        state = state.gridState("search"),
                        contentPadding = PaddingValues(top = 10.dp, end = 40.dp, bottom = 300.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(results, key = { it.id }) { t ->
                            TitleCard(
                                t = t,
                                progress = store.cardProgress(t),
                                width = 180.dp,
                                onFocused = {},
                                onClick = {
                                    state.wantContentFocus = true
                                    state.push(Screen.Details(t.id))
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchKeyboard(state: AppState, modifier: Modifier) {
    val keys = "abcdefghijklmnopqrstuvwxyz1234567890".chunked(6)
    val first = remember { FocusRequester() }
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF262626))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RailIcon(Icons.Filled.Search, Color(0xFF8C8C8C))
            Spacer(Modifier.width(10.dp))
            Text(
                state.searchQuery.ifEmpty { "Rechercher…" },
                color = if (state.searchQuery.isEmpty()) Color(0xFF8C8C8C) else Color.White,
                fontSize = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyButton("ESPACE", Modifier.weight(1f)) { state.searchQuery += " " }
            KeyButton("⌫", Modifier.weight(1f)) { state.searchQuery = state.searchQuery.dropLast(1) }
            KeyButton("EFFACER", Modifier.weight(1f)) { state.searchQuery = "" }
        }
        Spacer(Modifier.height(6.dp))
        keys.forEachIndexed { r, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                line.forEachIndexed { c, ch ->
                    KeyButton(
                        ch.uppercase(),
                        Modifier.weight(1f),
                        focusRequester = if (r == 0 && c == 0) first else null,
                    ) { state.searchQuery += ch }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
    AutoFocus(first, enabled = state.wantContentFocus) { state.wantContentFocus = false }
}

@Composable
private fun KeyButton(label: String, modifier: Modifier, focusRequester: FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .height(40.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (it.isFocused != focused) {
                    focused = it.isFocused
                    if (it.isFocused) Sounds.nav()
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                Sounds.select()
                onClick()
            }
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused) Color.White else Color(0xFF1F1F1F)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (focused) Color.Black else Color(0xFFD0D0D0),
            fontSize = if (label.length > 1) 12.sp else 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
