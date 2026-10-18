package be.kasugi.netflop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import be.kasugi.netflop.data.Episode
import be.kasugi.netflop.data.Kind
import be.kasugi.netflop.data.ProfileStore
import be.kasugi.netflop.data.Thumbs
import be.kasugi.netflop.data.Title
import be.kasugi.netflop.data.Tmdb
import be.kasugi.netflop.data.Scanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun DetailsScreen(state: AppState, t: Title) {
    val lib = state.library ?: return
    val store = state.store ?: return
    store.version
    val scope = rememberCoroutineScope()
    BackHandler {
        Sounds.back()
        state.pop()
    }

    val screenH = LocalConfiguration.current.screenHeightDp
    val infoHeight = (screenH * 0.74f).dp
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val vSpec = remember(density) {
        LazySpec(
            startPx = with(density) { 40.dp.toPx() },
            scrollValue = { scroll.value },
            topZonePx = with(density) { infoHeight.toPx() },
        )
    }
    val hSpec = remember(density) { PivotSpec(with(density) { 44.dp.toPx() }) }

    val sameLicence = remember(t.id, lib) { lib.sameLicence(t) }
    val sameCategory = remember(t.id, lib) { lib.sameCategory(t).take(30) }

    val next = store.nextEpisode(t)
    val playKey = store.playKey(t)
    val p = store.get(playKey)
    val started = p != null && !p.done && p.pos > 10_000
    val playReq = remember { FocusRequester() }

    Box(Modifier.fillMaxSize().background(Bg)) {
        val bg = rememberTitleImage(t, backdrop = true)
        FileImage(
            bg,
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(0.78f)
                .height(infoHeight + 40.dp)
                .heroFade(),
            alignment = Alignment.TopCenter,
        )
        CompositionLocalProvider(LocalBringIntoViewSpec provides vSpec) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
            ) {
                // ---- Bloc d'infos ----
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(infoHeight)
                        .padding(start = 56.dp, top = 56.dp, end = 40.dp)
                ) {
                    TitleHeading(t, maxWidthFraction = 0.45f, fontSizeSp = 50)
                    Spacer(Modifier.height(14.dp))
                    MetaRow(t, extra = t.licence?.let { "Licence $it" })
                    Spacer(Modifier.height(10.dp))
                    ResumeLine(t, store)
                    Spacer(Modifier.height(12.dp))
                    Overview(t, maxLines = 5, modifier = Modifier.fillMaxWidth(0.52f))
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        val label = when {
                            started && t.kind == Kind.SERIES && next != null -> "Reprendre S${next.season}:É${next.number}"
                            started -> "Reprendre"
                            t.kind == Kind.SERIES && next != null && store.lastWatchedAt(t) > 0 -> "Lecture S${next.season}:É${next.number}"
                            else -> "Lecture"
                        }
                        NetButton(
                            label,
                            { c -> PlayIcon(c) },
                            onClick = { state.push(Screen.Player(t.id, playKey, fromStart = false)) },
                            focusRequester = playReq,
                        )
                        if (started) {
                            NetButton(
                                "Reprendre au début",
                                { c -> Icon(Icons.Filled.Refresh, null, tint = c, modifier = Modifier.size(20.dp)) },
                                onClick = { state.push(Screen.Player(t.id, playKey, fromStart = true)) },
                            )
                        }
                        if (t.kind == Kind.SERIES) {
                            NetButton(
                                "Épisodes",
                                { c -> Icon(Icons.Filled.KeyboardArrowDown, null, tint = c, modifier = Modifier.size(22.dp)) },
                                onClick = { scope.launch { scroll.animateScrollTo(with(density) { (infoHeight - 30.dp).toPx() }.toInt()) } },
                            )
                        }
                    }
                    Spacer(Modifier.height(30.dp))
                }

                // ---- Épisodes ----
                if (t.kind == Kind.SERIES && t.seasons.isNotEmpty()) {
                    EpisodesBlock(state, t, store, next, hSpec)
                }

                // ---- Même licence / même catégorie ----
                if (sameLicence.isNotEmpty()) {
                    RelatedRow(state, "De la même licence", sameLicence, store, hSpec)
                }
                if (sameCategory.isNotEmpty()) {
                    RelatedRow(state, "Plus de titres : ${t.category}", sameCategory, store, hSpec)
                }
                Spacer(Modifier.height(320.dp))
            }
        }
    }
    AutoFocus(playReq)
    LaunchedEffect(t.id) {
        Tmdb.ensure(t)
        Tmdb.ensureEpisodes(t)
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun RelatedRow(
    state: AppState,
    title: String,
    titles: List<Title>,
    store: ProfileStore,
    hSpec: PivotSpec,
) {
    Column(Modifier.padding(top = 10.dp)) {
        SectionTitle(title, Modifier.padding(start = 44.dp))
        CompositionLocalProvider(LocalBringIntoViewSpec provides hSpec) {
            LazyRow(
                contentPadding = PaddingValues(start = 44.dp, end = 300.dp, top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(titles, key = { it.id }) { o ->
                    TitleCard(
                        t = o,
                        progress = store.cardProgress(o),
                        onFocused = {},
                        onClick = { state.push(Screen.Details(o.id)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun EpisodesBlock(state: AppState, t: Title, store: ProfileStore, next: Episode?, hSpec: PivotSpec) {
    val startSeason = t.seasons.indexOfFirst { s -> s.episodes.any { it.key == next?.key } }.coerceAtLeast(0)
    var seasonIdx by remember(t.id) { mutableIntStateOf(startSeason) }
    val season = t.seasons[seasonIdx.coerceIn(0, t.seasons.lastIndex)]
    val rowState = rememberLazyListState()
    LaunchedEffect(seasonIdx) {
        val i = season.episodes.indexOfFirst { it.key == next?.key }
        rowState.scrollToItem(if (i >= 0) i else 0)
    }
    Column(Modifier.padding(top = 4.dp)) {
        if (t.seasons.size > 1) {
            Row(
                Modifier
                    .padding(start = 44.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                t.seasons.forEachIndexed { i, s ->
                    SeasonChip(s.name, selected = i == seasonIdx) { seasonIdx = i }
                }
            }
        } else {
            SectionTitle("Épisodes", Modifier.padding(start = 44.dp, bottom = 4.dp))
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides hSpec) {
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(start = 44.dp, end = 300.dp, top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(season.episodes, key = { it.key }) { ep ->
                    EpisodeCard(ep, t.id, store, isNext = ep.key == next?.key) {
                        state.push(Screen.Player(t.id, ep.key, fromStart = false))
                    }
                }
            }
        }
    }
}

@Composable
private fun SeasonChip(label: String, selected: Boolean, onSelect: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .onFocusChanged {
                if (it.isFocused != focused) {
                    focused = it.isFocused
                    if (it.isFocused) {
                        Sounds.nav()
                        onSelect()
                    }
                }
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                Sounds.select()
                onSelect()
            }
            .clip(RoundedCornerShape(20.dp))
            .background(
                when {
                    focused -> Color.White
                    selected -> Color(0x40FFFFFF)
                    else -> Color.Transparent
                }
            )
            .thenIf(!focused && !selected) { border(1.dp, Color(0x66FFFFFF), RoundedCornerShape(20.dp)) }
            .padding(horizontal = 18.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            color = if (focused) Color.Black else Color.White,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Normal,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun EpisodeCard(ep: Episode, titleId: String, store: ProfileStore, isNext: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "ep")
    val meta = Tmdb.episodes[ep.key]
    val still = Tmdb.image(meta?.still, "w500")
    val waiting = still == null && Tmdb.enabled && Tmdb.episodesLoaded[titleId] == null && Tmdb.resolved[titleId] != false
    val thumb by produceState<Any?>(null, ep.key, still) {
        value = withContext(Dispatchers.IO) { Scanner.siblingImage(ep.file) } ?: still
            ?: if (waiting) null else Thumbs.frame(ep.file, 0.3f)
    }
    val p = store.get(ep.key)
    val duration = p?.dur?.takeIf { it > 0 } ?: meta?.runtime?.let { it * 60_000L } ?: Thumbs.knownDuration(ep.file)
    val label = meta?.name?.takeIf { it.isNotBlank() && !it.matches(Regex("""(?i)^(é|e)pisode\s*\d+$""")) } ?: ep.label
    val shape = RoundedCornerShape(6.dp)
    Column(Modifier.width(250.dp).zIndex(if (focused) 1f else 0f)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
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
                .clip(shape)
                .background(Color(0xFF222222))
                .thenIf(focused) { border(3.dp, Color.White, shape) }
        ) {
            FileImage(thumb, Modifier.fillMaxSize())
            if (focused) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0x99000000))
                        .border(2.dp, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { PlayIcon(Color.White, 18.dp) }
            }
            if (p != null && p.pos > 10_000) {
                ProgressLine(
                    if (p.done) 1f else p.fraction,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
            if (isNext) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(NetRed)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) { Text("À SUIVRE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${ep.number}. $label",
            color = if (focused) Color.White else Color(0xFFD9D9D9),
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (duration != null) {
            Text("${(duration / 60_000).coerceAtLeast(1)} min", color = TextGray, fontSize = 13.sp)
        }
        val desc = meta?.overview
        if (!desc.isNullOrBlank()) {
            Spacer(Modifier.height(3.dp))
            Text(
                desc,
                color = Color(0xFF9A9A9A),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = if (focused) 4 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
