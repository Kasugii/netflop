package be.kasugi.netflop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import be.kasugi.netflop.data.Kind
import be.kasugi.netflop.data.Thumbs
import be.kasugi.netflop.data.Title
import be.kasugi.netflop.data.Tmdb
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import java.io.File

val NetRed = Color(0xFFE50914)
val Bg = Color(0xFF141414)
val TextGray = Color(0xFFB3B3B3)
val CardW = 184.dp

@Composable
fun NetflopTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NetRed,
            background = Bg,
            surface = Bg,
            onBackground = Color.White,
            onSurface = Color.White,
        ),
        content = content,
    )
}

/** Fait défiler pour que l'élément focus soit toujours aligné au même endroit (comme Netflix). */
@OptIn(ExperimentalFoundationApi::class)
class PivotSpec(private val startPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - startPx
}

/** Ne défile que si l'élément n'est pas entièrement visible. */
@OptIn(ExperimentalFoundationApi::class)
class LazySpec(private val startPx: Float, private val scrollValue: () -> Int, private val topZonePx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val absolute = offset + scrollValue()
        if (absolute < topZonePx) return -scrollValue().toFloat()
        if (offset >= 0f && offset + size <= containerSize) return 0f
        return offset - startPx
    }
}

fun Modifier.thenIf(cond: Boolean, block: Modifier.() -> Modifier): Modifier = if (cond) this.block() else this

/** Demande le focus une fois que l'élément est composé. */
@Composable
fun AutoFocus(requester: FocusRequester, enabled: Boolean = true, onDone: () -> Unit = {}) {
    LaunchedEffect(requester, enabled) {
        if (!enabled) return@LaunchedEffect
        repeat(6) {
            delay(60)
            val ok = try {
                requester.requestFocus()
                true
            } catch (_: Throwable) {
                false
            }
            if (ok) {
                onDone()
                return@LaunchedEffect
            }
        }
    }
}

/** Image d'un titre : image locale > image TMDB > capture de la vidéo. */
@Composable
fun rememberTitleImage(t: Title?, backdrop: Boolean): Any? {
    val local: File? = if (backdrop) t?.backdropArt ?: t?.cardArt else t?.cardArt
    val meta = t?.let { Tmdb.metas[it.id] }
    val remote = meta?.backdrop?.let { Tmdb.image(it, if (backdrop) "w1280" else "w780") }
    val needFrame = t != null && local == null && remote == null && !Tmdb.isPending(t.id)
    val frame by produceState<File?>(null, t?.id, needFrame) {
        if (t != null && needFrame) {
            val video = t.firstVideo
            if (video != null) value = Thumbs.frame(video, if (t.kind == Kind.SERIES) 0.32f else 0.22f)
        }
    }
    return local ?: remote ?: frame
}

/** Logo du titre (local ou TMDB). */
fun logoModel(t: Title): Any? = t.logoArt ?: Tmdb.metas[t.id]?.logo?.let { Tmdb.image(it, "w500") }

@Composable
fun FileImage(file: Any?, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center, contentScale: ContentScale = ContentScale.Crop) {
    if (file == null) {
        Box(
            modifier.background(
                Brush.linearGradient(listOf(Color(0xFF2B2B2B), Color(0xFF1A1A1A), Color(0xFF3A1214)))
            )
        )
        return
    }
    val ctx = LocalContext.current
    AsyncImage(
        model = remember(file) { ImageRequest.Builder(ctx).data(file).crossfade(250).build() },
        contentDescription = null,
        contentScale = contentScale,
        alignment = alignment,
        modifier = modifier,
    )
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0x99808080))
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .background(NetRed)
        )
    }
}

/** Petit "F" rouge façon logo, en coin de vignette. */
@Composable
fun BrandBadge(kind: Kind, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("F", color = NetRed, fontWeight = FontWeight.Black, fontSize = 15.sp)
        if (kind == Kind.SERIES) {
            Spacer(Modifier.width(4.dp))
            Text("SÉRIE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 8.sp, letterSpacing = 2.sp)
        }
    }
}

/** Vignette 16:9 d'un titre, avec zoom et cadre au focus. */
@Composable
fun TitleCard(
    t: Title,
    progress: Float?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = CardW,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, tween(160), label = "cardScale")
    val shape = RoundedCornerShape(6.dp)
    val image = rememberTitleImage(t, backdrop = false)
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .width(width)
            .aspectRatio(16f / 9f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
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
            .clip(shape)
            .background(Color(0xFF222222))
            .thenIf(focused) { border(3.dp, Color.White, shape) }
    ) {
        FileImage(image, Modifier.fillMaxSize())
        val showTitle = t.cardArt == null
        if (showTitle) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xE6000000)))
            )
            val logo = logoModel(t)
            if (logo != null) {
                FileImage(
                    logo,
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .fillMaxWidth(0.6f)
                        .height(34.dp),
                    alignment = Alignment.BottomStart,
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    t.name,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 9.dp, end = 9.dp, bottom = if (progress != null) 14.dp else 8.dp),
                )
            }
        }
        BrandBadge(t.kind, Modifier.align(Alignment.TopStart).padding(start = 7.dp, top = 4.dp))
        if (progress != null) {
            ProgressLine(
                progress,
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

/** Bouton blanc/gris façon Netflix. */
@Composable
fun NetButton(
    text: String,
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(140), label = "btn")
    val fg = if (focused) Color.Black else Color.White
    Row(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
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
            .clip(RoundedCornerShape(5.dp))
            .background(if (focused) Color.White else Color(0x59808080))
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon(fg)
        Spacer(Modifier.width(10.dp))
        Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 17.sp)
    }
}

/** Dégradés posés sur une image de fond (gauche + bas) pour lire le texte. */
fun Modifier.heroFade(bg: Color = Bg): Modifier = this.drawWithContent {
    drawContent()
    drawRect(Brush.horizontalGradient(0f to bg, 0.38f to bg.copy(alpha = 0.55f), 0.7f to Color.Transparent))
    drawRect(Brush.verticalGradient(0.55f to Color.Transparent, 1f to bg))
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color(0xFFE5E5E5), fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = modifier)
}

@Composable
fun PlayIcon(color: Color, size: Dp = 18.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val p = androidx.compose.ui.graphics.Path()
        p.moveTo(w * 0.15f, 0f)
        p.lineTo(w, h / 2f)
        p.lineTo(w * 0.15f, h)
        p.close()
        drawPath(p, color)
    }
}

@Composable
fun PauseIcon(color: Color, size: Dp = 18.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.12f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.28f, h))
        drawRect(color, topLeft = androidx.compose.ui.geometry.Offset(w * 0.6f, 0f), size = androidx.compose.ui.geometry.Size(w * 0.28f, h))
    }
}

@Composable
fun MetaText(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text, color = TextGray, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = modifier,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

fun certLabel(c: String): String = when (c.trim().uppercase()) {
    "U", "TP", "0", "ALL", "G", "TV-G", "TV-Y" -> "Tous publics"
    else -> if (c.trim().all { it.isDigit() }) "${c.trim()}+" else c.trim()
}

fun kindLine(t: Title): String {
    if (t.kind == Kind.MOVIE) return "Film"
    val real = t.seasons.count { it.number > 0 }
    val n = t.episodes.size
    return "Série  •  " + if (real > 1) "$real saisons" else "$n épisode" + if (n > 1) "s" else ""
}

/** Ligne d'infos façon Netflix : % de match, année, âge, type, genres. */
@Composable
fun MetaRow(t: Title, extra: String? = null) {
    val m = Tmdb.metas[t.id]
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (m != null && m.voteCount >= 20 && m.vote > 0) {
            Text(
                "${kotlin.math.round(m.vote * 10).toInt()} % de match",
                color = Color(0xFF46D369),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
            Spacer(Modifier.width(12.dp))
        }
        val year = t.year ?: m?.year
        if (year != null) {
            MetaText(year.toString())
            Spacer(Modifier.width(12.dp))
        }
        val cert = m?.certification
        if (cert != null) {
            Text(
                certLabel(cert),
                color = Color(0xFFDDDDDD),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(3.dp))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        val genres = m?.genres?.take(3).orEmpty()
        val tail = listOfNotNull(
            kindLine(t),
            genres.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            extra,
        ).joinToString("  •  ")
        MetaText(tail, maxLines = 1)
    }
}

/** Résumé TMDB (si disponible). */
@Composable
fun Overview(t: Title, maxLines: Int, modifier: Modifier = Modifier) {
    val text = Tmdb.metas[t.id]?.overview
    if (!text.isNullOrBlank()) {
        Text(
            text,
            color = Color(0xFFE0E0E0),
            fontSize = 15.sp,
            lineHeight = 21.sp,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
    }
}
