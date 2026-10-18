package be.kasugi.netflop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import be.kasugi.netflop.data.Scanner
import be.kasugi.netflop.data.Role
import be.kasugi.netflop.data.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

val AvatarColors = listOf(Color(0xFF0071EB), Color(0xFFE50914), Color(0xFFF5C518), Color(0xFF2FB463), Color(0xFF8E44AD))

// ---------------- Intro ----------------

@Composable
fun NetflopWordmark(modifier: Modifier = Modifier, fontSizeSp: Int = 96, letterProgress: (Int) -> Float = { 1f }) {
    val word = "NETFLOP"
    Row(modifier, verticalAlignment = Alignment.Top) {
        word.forEachIndexed { i, c ->
            val u = (i - (word.length - 1) / 2f) / ((word.length - 1) / 2f)
            val p = letterProgress(i)
            Text(
                c.toString(),
                color = NetRed,
                fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = (-2).sp,
                modifier = Modifier.graphicsLayer {
                    // petite courbure façon "sourire"
                    translationY = u * u * fontSizeSp * 0.22f + (1f - p) * -260f
                    alpha = p.coerceIn(0f, 1f)
                    val s = 0.55f + 0.45f * p
                    scaleX = s
                    scaleY = s
                }
            )
        }
    }
}

@Composable
fun IntroScreen(state: AppState, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val letters = remember { List(7) { Animatable(0f) } }
    val zoom = remember { Animatable(1f) }
    val fade = remember { Animatable(1f) }
    val glow = remember { Animatable(0f) }
    var finished by remember { mutableStateOf(false) }
    val skip = remember { FocusRequester() }
    fun finish() {
        if (!finished) {
            finished = true
            onDone()
        }
    }
    LaunchedEffect(Unit) {
        Sounds.playIntro(ctx, state.root?.let { Scanner.introOverride(it) })
        letters.forEachIndexed { i, a ->
            launch {
                delay(120L + i * 75L)
                a.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f))
            }
        }
        launch {
            delay(450)
            glow.animateTo(1f, tween(900))
        }
        delay(1900)
        launch { zoom.animateTo(7f, tween(950, easing = FastOutSlowInEasing)) }
        fade.animateTo(0f, tween(900))
        finish()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(skip)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(700.dp, 260.dp)
                .graphicsLayer { alpha = glow.value * 0.55f * fade.value }
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        listOf(NetRed.copy(alpha = 0.45f), Color.Transparent)
                    )
                )
        )
        NetflopWordmark(
            Modifier.graphicsLayer {
                scaleX = zoom.value
                scaleY = zoom.value
                alpha = fade.value
            },
            fontSizeSp = 104,
            letterProgress = { letters[it].value },
        )
    }
    AutoFocus(skip)
}

// ---------------- Profils ----------------

@Composable
fun GeneratedAvatar(index: Int, modifier: Modifier = Modifier) {
    val color = AvatarColors[index % AvatarColors.size]
    Canvas(modifier.background(color)) {
        val w = size.width
        val h = size.height
        val eye = Size(w * 0.09f, h * 0.17f)
        drawRect(Color.White, Offset(w * 0.28f, h * 0.30f), eye)
        drawRect(Color.White, Offset(w * 0.63f, h * 0.30f), eye)
        drawArc(
            Color.White, startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(w * 0.22f, h * 0.30f), size = Size(w * 0.56f, h * 0.42f),
            style = Stroke(width = w * 0.06f, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun ProfileAvatar(dir: File, index: Int, modifier: Modifier = Modifier) {
    val img = remember(dir) { Scanner.avatarOf(dir) }
    if (img != null) FileImage(img, modifier) else GeneratedAvatar(index, modifier)
}

@Composable
fun ProfilesScreen(state: AppState, requestPermission: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        state.refreshRoot()
        if (!state.hasPermission) requestPermission()
    }
    Box(Modifier.fillMaxSize().background(Bg), contentAlignment = Alignment.Center) {
        AmbientBackground(state.profileIndex)
        NetflopWordmark(Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 18.dp), fontSizeSp = 30)
        when {
            !state.hasPermission -> MessageBlock(
                "Netflop a besoin d'accéder au disque",
                "Autorise l'accès aux fichiers pour lire le dossier Netflop de ton disque dur.",
                "Autoriser l'accès",
            ) { requestPermission() }

            state.root == null -> MessageBlock(
                "Dossier « Netflop » introuvable",
                "Crée un dossier nommé Netflop à la racine du disque dur branché à la Shield, avec un sous-dossier par profil.\n" +
                    "Emplacements vérifiés : " + state.volumes.joinToString("  ·  ") { it.absolutePath }.ifEmpty { "aucun" },
                "Réessayer",
            ) { state.refreshRoot() }

            state.profiles.isEmpty() -> MessageBlock(
                "Aucun profil",
                "Ajoute un dossier par profil dans ${state.root?.absolutePath} (ex : Parents, Moi, Petite sœur).",
                "Réessayer",
            ) { state.refreshRoot() }

            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val titleIn = remember { Animatable(0f) }
                LaunchedEffect(Unit) { titleIn.animateTo(1f, tween(500)) }
                Text(
                    "Qui est-ce ?",
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.graphicsLayer {
                        alpha = titleIn.value
                        translationY = (1f - titleIn.value) * -30f
                    },
                )
                Spacer(Modifier.height(40.dp))
                val first = remember { FocusRequester() }
                var chosen by remember { mutableStateOf(-1) }
                Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
                    state.profiles.forEachIndexed { i, dir ->
                        ProfileTile(
                            dir, i,
                            chosen = chosen,
                            focusRequester = if (i == state.profileIndex.coerceIn(0, state.profiles.lastIndex)) first else null,
                        ) {
                            if (state.loading || chosen >= 0) return@ProfileTile
                            Sounds.profile()
                            chosen = i
                            scope.launch {
                                delay(420)
                                try {
                                    state.openProfile(dir, i)
                                    state.push(Screen.Browse(Section.HOME))
                                } catch (e: Throwable) {
                                    state.loading = false
                                    chosen = -1
                                    state.crash = CrashReport.format(e)
                                }
                            }
                        }
                    }
                }
                AutoFocus(first)
            }
        }
        if (state.loading) {
            Box(Modifier.fillMaxSize().background(Color(0xAA000000)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = NetRed, strokeWidth = 4.dp, modifier = Modifier.size(56.dp))
            }
        }
    }
}

@Composable
private fun SoundToggle(ctx: android.content.Context) {
    var on by remember { mutableStateOf(Sounds.enabled) }
    var focused by remember { mutableStateOf(false) }
    Text(
        if (on) "Son d'ouverture : activé" else "Son d'ouverture : désactivé",
        color = if (focused) Color.White else Color(0xFF808080),
        fontSize = 15.sp,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                on = !on
                Sounds.setEnabled(ctx, on)
            }
            .thenIf(focused) { border(1.dp, Color.White, RoundedCornerShape(4.dp)) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Fond animé : halos de couleur qui dérivent lentement. */
@Composable
private fun AmbientBackground(index: Int) {
    val t = rememberInfiniteTransition(label = "ambient")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0f, 1f, infiniteRepeatable(tween(19_000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val accent = AvatarColors[index % AvatarColors.size]
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        drawCircle(
            Brush.radialGradient(
                listOf(NetRed.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(w * (0.15f + 0.25f * a), h * (0.2f + 0.3f * b)),
                radius = w * 0.45f,
            ),
            radius = w * 0.45f,
            center = Offset(w * (0.15f + 0.25f * a), h * (0.2f + 0.3f * b)),
        )
        drawCircle(
            Brush.radialGradient(
                listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(w * (0.85f - 0.3f * b), h * (0.75f - 0.25f * a)),
                radius = w * 0.5f,
            ),
            radius = w * 0.5f,
            center = Offset(w * (0.85f - 0.3f * b), h * (0.75f - 0.25f * a)),
        )
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000))))
    }
}

@Composable
private fun ProfileTile(dir: File, index: Int, chosen: Int, focusRequester: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val color = AvatarColors[index % AvatarColors.size]
    // Entrée en cascade
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(120L + index * 90L)
        enter.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 220f))
    }
    // Respiration du profil sélectionné
    val pulse = rememberInfiniteTransition(label = "pulse")
    val breath by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val isChosen = chosen == index
    val target = when {
        isChosen -> 1.35f
        focused -> 1.12f
        else -> 1f
    }
    val scale by animateFloatAsState(target, tween(if (isChosen) 400 else 170), label = "profile")
    val fadeOthers by animateFloatAsState(if (chosen >= 0 && !isChosen) 0f else 1f, tween(300), label = "fade")
    val glow by animateFloatAsState(if (focused || isChosen) 1f else 0f, tween(250), label = "glow")
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(150.dp)
            .graphicsLayer {
                alpha = enter.value.coerceIn(0f, 1f) * fadeOthers
                translationY = (1f - enter.value) * 60f
            },
    ) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            // Halo coloré derrière l'avatar
            Canvas(Modifier.requiredSize(240.dp)) {
                val r = size.minDimension / 2f * (0.85f + 0.08f * breath)
                drawCircle(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = 0.55f * glow), color.copy(alpha = 0.18f * glow), Color.Transparent),
                        center = center,
                        radius = r,
                    ),
                    radius = r,
                )
            }
            Box(
                Modifier
                    .graphicsLayer {
                        val s = scale * (if (focused && !isChosen) 1f + 0.015f * breath else 1f)
                        scaleX = s
                        scaleY = s
                        shadowElevation = if (focused) 24f else 6f
                        shape = RoundedCornerShape(10.dp)
                        clip = true
                    }
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .onFocusChanged {
                        if (it.isFocused != focused) {
                            focused = it.isFocused
                            if (it.isFocused) Sounds.nav()
                        }
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
                    .size(130.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .thenIf(focused) { border(4.dp, Color.White, RoundedCornerShape(10.dp)) }
            ) {
                ProfileAvatar(dir, index, Modifier.fillMaxSize())
                // Reflet qui passe sur l'avatar au focus
                if (focused) {
                    Canvas(Modifier.fillMaxSize()) {
                        val x = size.width * (-0.6f + 2.2f * breath)
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.18f), Color.Transparent),
                                start = Offset(x - size.width * 0.3f, 0f),
                                end = Offset(x + size.width * 0.3f, size.height),
                            )
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        val ps = SettingsStore.get(dir.name)
        Text(
            dir.name,
            color = if (focused) Color.White else Color(0xFF8C8C8C),
            fontSize = 19.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        if (ps.role == Role.KIDS) {
            Spacer(Modifier.height(4.dp))
            Text("ENFANT", color = Color(0xFFF5C518), fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        }
    }
}

@Composable
fun MessageBlock(title: String, text: String, button: String, onClick: () -> Unit) {
    val req = remember { FocusRequester() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth(0.7f)) {
        Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        Text(text, color = TextGray, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        NetButton(button, { }, onClick, focusRequester = req)
    }
    AutoFocus(req)
}

@Composable
fun MissingScreen(state: AppState) {
    BackHandler { state.pop() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MessageBlock("Titre introuvable", "Ce titre n'est plus sur le disque.", "Retour") { state.pop() }
    }
}
