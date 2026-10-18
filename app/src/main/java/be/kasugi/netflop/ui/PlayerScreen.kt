package be.kasugi.netflop.ui

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import be.kasugi.netflop.data.Episode
import be.kasugi.netflop.data.Kind
import be.kasugi.netflop.data.ProfileStore
import be.kasugi.netflop.data.Scanner
import be.kasugi.netflop.data.Title
import be.kasugi.netflop.data.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class PlayItem(val key: String, val file: File, val heading: String, val sub: String, val episode: Episode?)

class TrackOption(
    val label: String,
    val group: Tracks.Group,
    val index: Int,
    val selected: Boolean,
    val supported: Boolean,
    val lang: String?,
)

// ---------------- Lecteur ----------------

@androidx.annotation.OptIn(UnstableApi::class)
fun createPlayer(ctx: Context, store: ProfileStore): ExoPlayer {
    val renderers = DefaultRenderersFactory(ctx)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
    val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
    val player = ExoPlayer.Builder(ctx, renderers)
        .setMediaSourceFactory(DefaultMediaSourceFactory(ctx, extractors))
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()
    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
        .setPreferredAudioLanguage(store.audioLang)
        .setPreferredTextLanguage(if (store.subOff) null else store.subLang)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, store.subOff)
        .build()
    return player
}

private fun subMime(f: File): String = when (f.extension.lowercase(Locale.ROOT)) {
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "vtt" -> MimeTypes.TEXT_VTT
    "ttml" -> MimeTypes.APPLICATION_TTML
    else -> MimeTypes.APPLICATION_SUBRIP
}

private fun mediaItemFor(item: PlayItem): MediaItem {
    val subs = Scanner.externalSubs(item.file).map { (f, lang) ->
        MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(f))
            .setMimeType(subMime(f))
            .setLanguage(lang)
            .setLabel((langName(lang) ?: "Sous-titres") + " (fichier)")
            .build()
    }
    return MediaItem.Builder()
        .setUri(Uri.fromFile(item.file))
        .setMediaId(item.key)
        .setSubtitleConfigurations(subs)
        .build()
}

fun langName(code: String?): String? {
    if (code.isNullOrBlank() || code == "und" || code == C.LANGUAGE_UNDETERMINED) return null
    val fixed = when (code.lowercase(Locale.ROOT)) {
        "fre", "fra" -> "fr"
        "ger", "deu" -> "de"
        "chi", "zho" -> "zh"
        "jpn" -> "ja"
        "eng" -> "en"
        "spa" -> "es"
        "ita" -> "it"
        "dut", "nld" -> "nl"
        "por" -> "pt"
        "kor" -> "ko"
        "ara" -> "ar"
        "rus" -> "ru"
        else -> code
    }
    val n = Locale.forLanguageTag(fixed).getDisplayName(Locale.FRENCH)
    return if (n.isBlank()) code else n.replaceFirstChar { it.titlecase(Locale.FRENCH) }
}

private fun codecName(mime: String?): String? = when (mime) {
    MimeTypes.AUDIO_AC3 -> "Dolby Digital"
    MimeTypes.AUDIO_E_AC3 -> "Dolby Digital+"
    MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
    MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    else -> null
}

private fun trackLabel(f: Format, type: Int, n: Int): String {
    val lang = langName(f.language)
    val label = f.label?.takeIf { it.isNotBlank() }
    val base = label ?: lang ?: if (type == C.TRACK_TYPE_AUDIO) "Piste $n" else "Sous-titres $n"
    val extras = mutableListOf<String>()
    if (label != null && lang != null && !label.contains(lang, ignoreCase = true)) extras += lang
    if (type == C.TRACK_TYPE_AUDIO) {
        when (f.channelCount) {
            1 -> extras += "mono"
            6 -> extras += "5.1"
            8 -> extras += "7.1"
        }
        codecName(f.sampleMimeType)?.let { extras += it }
        if (f.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0) extras += "audiodescription"
    } else {
        if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) extras += "forcés"
        if (f.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) extras += "SME"
    }
    return if (extras.isEmpty()) base else "$base · ${extras.joinToString(" · ")}"
}

private fun trackOptions(tracks: Tracks, type: Int): List<TrackOption> {
    val out = mutableListOf<TrackOption>()
    for (g in tracks.groups) {
        if (g.type != type) continue
        for (i in 0 until g.length) {
            val f = g.getTrackFormat(i)
            out += TrackOption(trackLabel(f, type, out.size + 1), g, i, g.isTrackSelected(i), g.isTrackSupported(i), f.language)
        }
    }
    return out
}

fun fmtTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(state: AppState, t: Title, startKey: String?, fromStart: Boolean) {
    val ctx = LocalContext.current
    val store = state.store ?: return
    val items = remember(t.id) {
        if (t.kind == Kind.MOVIE) {
            listOf(PlayItem(t.movieKey ?: t.id, t.movieFile!!, t.name, t.year?.toString() ?: "", null))
        } else {
            t.episodes.map { e ->
                val name = Tmdb.episodes[e.key]?.name?.takeIf { it.isNotBlank() } ?: e.label
                PlayItem(e.key, e.file, t.name, "S${e.season}:É${e.number}  « $name »", e)
            }
        }
    }
    val startIndex = remember(t.id) { items.indexOfFirst { it.key == startKey }.coerceAtLeast(0) }
    val player = remember { createPlayer(ctx, store) }

    var index by remember { mutableIntStateOf(startIndex) }
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var error by remember { mutableStateOf<String?>(null) }
    var controls by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(false) }
    var interaction by remember { mutableLongStateOf(0L) }
    var flash by remember { mutableStateOf<String?>(null) }
    var flashTick by remember { mutableIntStateOf(0) }
    val rootReq = remember { FocusRequester() }
    val seekReq = remember { FocusRequester() }
    val panelReq = remember { FocusRequester() }

    fun save(markDone: Boolean = false) {
        val it = items.getOrNull(player.currentMediaItemIndex) ?: return
        val d = player.duration
        if (d == C.TIME_UNSET || d <= 0) return
        store.update(it.key, if (markDone) d else player.currentPosition, d)
    }

    fun exit() {
        save()
        state.pop()
    }

    fun seekBy(ms: Long) {
        val d = player.duration
        val max = if (d != C.TIME_UNSET && d > 0) d - 2_000 else Long.MAX_VALUE
        val target = (player.currentPosition + ms).coerceIn(0L, max.coerceAtLeast(0L))
        player.seekTo(target)
        position = target
        flash = (if (ms > 0) "+" else "−") + "${kotlin.math.abs(ms) / 1000} s"
        flashTick++
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            save()
        } else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    val hasNext = index < items.lastIndex
    fun playNext() {
        if (!player.hasNextMediaItem()) return
        save()
        player.seekToNextMediaItem()
        player.play()
    }

    // ---- Branchement du lecteur ----
    DisposableEffect(player) {
        var lastIndex = startIndex
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(s: Int) {
                buffering = s == Player.STATE_BUFFERING || s == Player.STATE_IDLE
                if (s == Player.STATE_ENDED) {
                    save(markDone = true)
                    state.pop()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
                val prev = items.getOrNull(lastIndex)
                if (prev != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && duration > 0) {
                    store.update(prev.key, duration, duration)
                }
                lastIndex = player.currentMediaItemIndex
                index = lastIndex
                val now = items.getOrNull(lastIndex)
                if (now != null) {
                    val r = store.resumePos(now.key)
                    if (r > 0) player.seekTo(r)
                }
            }

            override fun onTracksChanged(tr: Tracks) {
                tracks = tr
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "Lecture impossible (${e.errorCodeName})\n${e.cause?.message ?: e.message ?: ""}"
            }
        }
        player.addListener(listener)
        onDispose {
            save()
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        val media = withContext(Dispatchers.IO) { items.map { mediaItemFor(it) } }
        val pos = if (fromStart) 0L else store.resumePos(items[startIndex].key)
        player.setMediaItems(media, startIndex, pos)
        player.prepare()
        player.playWhenReady = true
        var tick = 0
        while (true) {
            position = player.currentPosition.coerceAtLeast(0L)
            val d = player.duration
            duration = if (d == C.TIME_UNSET || d < 0) 0L else d
            tick++
            if (tick % 20 == 0 && player.isPlaying) save()
            delay(250)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                player.pause()
                save()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Masquage automatique des commandes
    LaunchedEffect(controls, interaction, isPlaying, panel) {
        if (controls && isPlaying && !panel) {
            delay(5_000)
            controls = false
        }
    }
    // Où va le focus
    LaunchedEffect(controls, panel) {
        delay(60)
        try {
            when {
                panel -> panelReq.requestFocus()
                controls -> seekReq.requestFocus()
                else -> rootReq.requestFocus()
            }
        } catch (_: Throwable) {
        }
    }
    LaunchedEffect(flashTick) {
        if (flash != null) {
            delay(800)
            flash = null
        }
    }

    BackHandler {
        when {
            panel -> {
                Sounds.back()
                panel = false
            }
            else -> {
                Sounds.back()
                exit()
            }
        }
    }

    val remaining = (duration - position).coerceAtLeast(0L)
    val nextPrompt = hasNext && duration > 60_000 && remaining in 1_500L..maxOf(40_000L, (duration * 0.035).toLong())
    val current = items.getOrNull(index) ?: items.first()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val code = ev.nativeKeyEvent.keyCode
                val repeat = ev.nativeKeyEvent.repeatCount
                val step = when {
                    repeat > 25 -> 60_000L
                    repeat > 8 -> 30_000L
                    else -> 10_000L
                }
                interaction = SystemClock.uptimeMillis()
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                        togglePlay(); controls = true; return@onPreviewKeyEvent true
                    }
                    KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        player.play(); return@onPreviewKeyEvent true
                    }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        player.pause(); controls = true; return@onPreviewKeyEvent true
                    }
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        seekBy(step); return@onPreviewKeyEvent true
                    }
                    KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        seekBy(-step); return@onPreviewKeyEvent true
                    }
                    KeyEvent.KEYCODE_MEDIA_NEXT -> {
                        playNext(); return@onPreviewKeyEvent true
                    }
                }
                if (error != null) return@onPreviewKeyEvent false
                if (!controls && !panel) {
                    when (code) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            seekBy(-step); true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            seekBy(step); true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (nextPrompt) {
                                Sounds.select(); playNext()
                            } else {
                                togglePlay(); controls = true
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                            controls = true; true
                        }
                        else -> false
                    }
                } else false
            }
            .focusRequester(rootReq)
            .focusable()
    ) {
        AndroidView(
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    this.player = player
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    subtitleView?.apply {
                        setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                android.graphics.Color.BLACK,
                                null,
                            )
                        )
                        setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.12f)
                        setBottomPaddingFraction(0.07f)
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering && error == null) {
            CircularProgressIndicator(
                color = NetRed,
                strokeWidth = 4.dp,
                modifier = Modifier.align(Alignment.Center).size(64.dp),
            )
        }

        // Indicateur +10 / -10 s
        AnimatedVisibility(
            visible = flash != null && !controls,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                Modifier
                    .size(110.dp)
                    .clip(CircleShape)
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.Center,
            ) {
                Text(flash ?: "", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ---- Commandes ----
        AnimatedVisibility(
            visible = controls && !panel && error == null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                ) {
                    Column(Modifier.padding(start = 48.dp, top = 30.dp)) {
                        Text(current.heading, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
                        if (current.sub.isNotBlank()) {
                            Text(current.sub, color = TextGray, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (!isPlaying && !buffering) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(Color(0x66000000)),
                        contentAlignment = Alignment.Center,
                    ) { PauseIcon(Color.White, 34.dp) }
                }
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
                        .padding(start = 48.dp, end = 48.dp, top = 60.dp, bottom = 28.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SeekBar(
                            position = position,
                            duration = duration,
                            focusRequester = seekReq,
                            onSeek = { seekBy(it) },
                            onToggle = { togglePlay() },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(18.dp))
                        Text(fmtTime(remaining), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayerButton(if (isPlaying) "Pause" else "Lecture", onClick = { togglePlay() }) { c ->
                            if (isPlaying) PauseIcon(c, 20.dp) else PlayIcon(c, 20.dp)
                        }
                        PlayerButton("−10 s", onClick = { seekBy(-10_000) }) { c -> Text("−10", color = c, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                        PlayerButton("+10 s", onClick = { seekBy(10_000) }) { c -> Text("+10", color = c, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                        PlayerButton("Audio et sous-titres", onClick = { panel = true }) { c ->
                            Icon(Icons.Filled.List, null, tint = c, modifier = Modifier.size(22.dp))
                        }
                        if (hasNext) {
                            PlayerButton("Épisode suivant", onClick = { playNext() }) { c ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    PlayIcon(c, 14.dp)
                                    Box(Modifier.width(3.dp).height(14.dp).background(c))
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- Épisode suivant (générique de fin) ----
        AnimatedVisibility(
            visible = nextPrompt && !controls && !panel,
            enter = fadeIn() + slideInHorizontally { it / 2 },
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 60.dp),
        ) {
            val nextItem = items.getOrNull(index + 1)
            Column(horizontalAlignment = Alignment.End) {
                if (nextItem != null) {
                    Text(nextItem.sub, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(320.dp))
                    Spacer(Modifier.height(8.dp))
                }
                Row(
                    Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color.White)
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayIcon(Color.Black, 18.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Épisode suivant  (OK)", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                }
            }
        }

        // ---- Audio et sous-titres ----
        if (panel) {
            TrackPanel(
                tracks = tracks,
                textDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT),
                firstRequester = panelReq,
                onAudio = { o ->
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
                        .setPreferredAudioLanguage(o.lang)
                        .build()
                    store.setTrackPrefs(audio = o.lang, sub = null, off = store.subOff)
                },
                onText = { o ->
                    var b = player.trackSelectionParameters.buildUpon()
                    if (o == null) {
                        b = b.clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        store.setTrackPrefs(audio = null, sub = null, off = true)
                    } else {
                        b = b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
                            .setPreferredTextLanguage(o.lang)
                        store.setTrackPrefs(audio = null, sub = o.lang, off = false)
                    }
                    player.trackSelectionParameters = b.build()
                },
            )
        }

        error?.let { msg ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xE6181818))
                    .padding(30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Oups, un flop !", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(10.dp))
                Text(msg, color = TextGray, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Text(current.file.name, color = Color(0xFF808080), fontSize = 13.sp)
                Spacer(Modifier.height(18.dp))
                Text("Appuie sur Retour pour revenir.", color = Color.White, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    focusRequester: FocusRequester,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val frac = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        modifier
            .height(30.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val repeat = ev.nativeKeyEvent.repeatCount
                val step = when {
                    repeat > 25 -> 60_000L
                    repeat > 8 -> 30_000L
                    else -> 10_000L
                }
                when (ev.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        onSeek(-step); true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        onSeek(step); true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onToggle(); true
                    }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Canvas(Modifier.fillMaxWidth().height(30.dp)) {
            val h = if (focused) 7.dp.toPx() else 4.dp.toPx()
            val y = size.height / 2f - h / 2f
            drawRoundRect(Color(0x66FFFFFF), Offset(0f, y), Size(size.width, h), CornerRadius(h / 2))
            drawRoundRect(NetRed, Offset(0f, y), Size(size.width * frac, h), CornerRadius(h / 2))
            val r = if (focused) 11.dp.toPx() else 7.dp.toPx()
            drawCircle(NetRed, r, Offset(size.width * frac, size.height / 2f))
        }
    }
}

@Composable
private fun PlayerButton(label: String, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val fg = if (focused) Color.Black else Color.White
    Row(
        Modifier
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
            .clip(RoundedCornerShape(24.dp))
            .background(if (focused) Color.White else Color(0x33FFFFFF))
            .padding(horizontal = if (focused) 18.dp else 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon(fg) }
        if (focused) {
            Spacer(Modifier.width(8.dp))
            Text(label, color = fg, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun TrackPanel(
    tracks: Tracks,
    textDisabled: Boolean,
    firstRequester: FocusRequester,
    onAudio: (TrackOption) -> Unit,
    onText: (TrackOption?) -> Unit,
) {
    val audio = trackOptions(tracks, C.TRACK_TYPE_AUDIO)
    val text = trackOptions(tracks, C.TRACK_TYPE_TEXT)
    val noSubs = textDisabled || text.none { it.selected }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xD9000000)),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.fillMaxWidth(0.8f).fillMaxHeight(0.8f), horizontalArrangement = Arrangement.spacedBy(60.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("Audio", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                if (audio.isEmpty()) TrackItem("Aucune piste audio", false, false, null) {}
                audio.forEachIndexed { i, o ->
                    TrackItem(
                        o.label + if (!o.supported) "  (non pris en charge)" else "",
                        selected = o.selected,
                        enabled = o.supported,
                        focusRequester = if (i == 0) firstRequester else null,
                    ) { onAudio(o) }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("Sous-titres", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                TrackItem(
                    "Désactivés",
                    selected = noSubs,
                    enabled = true,
                    focusRequester = if (audio.isEmpty()) firstRequester else null,
                ) { onText(null) }
                text.forEach { o ->
                    TrackItem(
                        o.label + if (!o.supported) "  (non pris en charge)" else "",
                        selected = o.selected && !textDisabled,
                        enabled = o.supported,
                        focusRequester = null,
                    ) { onText(o) }
                }
            }
        }
    }
}

@Composable
private fun TrackItem(label: String, selected: Boolean, enabled: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (it.isFocused != focused) {
                    focused = it.isFocused
                    if (it.isFocused) Sounds.nav()
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                if (enabled) {
                    Sounds.select()
                    onClick()
                }
            }
            .clip(RoundedCornerShape(4.dp))
            .background(if (focused) Color(0x26FFFFFF) else Color.Transparent)
            .thenIf(focused) { border(1.dp, Color(0x80FFFFFF), RoundedCornerShape(4.dp)) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            color = when {
                !enabled -> Color(0xFF666666)
                selected || focused -> Color.White
                else -> Color(0xFFB3B3B3)
            },
            fontSize = 19.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
