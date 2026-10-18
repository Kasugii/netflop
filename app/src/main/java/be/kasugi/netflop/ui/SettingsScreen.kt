package be.kasugi.netflop.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import be.kasugi.netflop.data.Access
import be.kasugi.netflop.data.Kind
import be.kasugi.netflop.data.Library
import be.kasugi.netflop.data.Role
import be.kasugi.netflop.data.Scanner
import be.kasugi.netflop.data.SettingsStore
import be.kasugi.netflop.data.Title
import be.kasugi.netflop.data.Tmdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val AGE_STEPS = listOf<Int?>(null, 6, 10, 12, 14, 16)

// ---------------- Pavé de code PIN ----------------

/** Pavé numérique plein écran. onDone renvoie false si le code est faux. */
@Composable
fun PinPad(title: String, onCancel: () -> Unit, onDone: (String) -> Boolean) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(code) {
        if (code.length == 4) {
            delay(150)
            if (!onDone(code)) {
                wrong = true
                Sounds.back()
                code = ""
            }
        }
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xF2000000)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(4) { i ->
                        Box(
                            Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(if (i < code.length) Color.White else Color.Transparent)
                                .border(2.dp, Color.White, CircleShape)
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (wrong) "Code incorrect, réessaie." else " ",
                    color = NetRed, fontSize = 15.sp,
                )
                Spacer(Modifier.height(14.dp))
                val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "✕", "0", "⌫")
                keys.chunked(3).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        line.forEach { k ->
                            PadKey(k, if (k == "1") first else null) {
                                when (k) {
                                    "✕" -> onCancel()
                                    "⌫" -> code = code.dropLast(1)
                                    else -> if (code.length < 4) {
                                        wrong = false
                                        code += k
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        AutoFocus(first)
    }
}

@Composable
private fun PadKey(label: String, req: FocusRequester?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(70.dp, 54.dp)
            .then(if (req != null) Modifier.focusRequester(req) else Modifier)
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
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color.White else Color(0xFF262626)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (focused) Color.Black else Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------- Paramètres ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(state: AppState) {
    val root = state.root
    val names = state.profileNames
    var selected by remember { mutableIntStateOf(state.profileIndex.coerceIn(0, (names.size - 1).coerceAtLeast(0))) }
    var message by remember { mutableStateOf<String?>(null) }
    BackHandler {
        Sounds.back()
        state.pop()
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2500)
            message = null
        }
    }
    if (root == null || names.isEmpty()) {
        EmptyMessage("Aucun profil", "Branche le disque Netflop.")
        return
    }
    val profileDir = state.profiles[selected.coerceIn(0, names.lastIndex)]
    val name = profileDir.name
    val ps = SettingsStore.get(name)

    // Bibliothèque complète vue par ce profil (catégories partagées + son propre dossier)
    val lib by produceState<Library?>(null, profileDir) {
        value = withContext(Dispatchers.IO) { Scanner.scanProfile(root, profileDir) }
    }
    val profileReq = remember { FocusRequester() }
    val density = LocalDensity.current
    val spec = remember(density) { LazySpec(with(density) { 60.dp.toPx() }, { 0 }, -1f) }

    Row(Modifier.fillMaxSize().background(Bg)) {
        // ---- Liste des profils ----
        Column(
            Modifier
                .width(250.dp)
                .fillMaxHeight()
                .background(Color(0xFF0C0C0C))
                .padding(top = 36.dp, start = 24.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Paramètres", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(16.dp))
            state.profiles.forEachIndexed { i, dir ->
                val s = SettingsStore.get(dir.name)
                SettingsRow(
                    title = dir.name,
                    value = s.role.label,
                    selected = i == selected,
                    focusRequester = if (i == selected) profileReq else null,
                    leading = { ProfileAvatar(dir, i, Modifier.size(34.dp).clip(RoundedCornerShape(4.dp))) },
                    onFocused = { selected = i },
                    onClick = { selected = i },
                )
            }
        }

        // ---- Réglages du profil sélectionné ----
        val l = lib
        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(start = 40.dp, end = 60.dp, top = 36.dp, bottom = 200.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Column {
                    Text(name, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.height(4.dp))
                    MetaText(
                        when (ps.role) {
                            Role.ADMIN -> "Peut ouvrir les paramètres et gérer tous les profils."
                            Role.STANDARD -> "Profil normal, sans accès aux paramètres."
                            Role.KIDS -> "Profil enfant, sans accès aux paramètres."
                        }
                    )
                    Spacer(Modifier.height(14.dp))
                    }
                }
                item {
                    SettingsRow("Type de compte", ps.role.label) {
                        val next = Role.entries[(ps.role.ordinal + 1) % Role.entries.size]
                        if (ps.role == Role.ADMIN && next != Role.ADMIN && SettingsStore.adminCount(names) <= 1) {
                            message = "Il faut garder au moins un administrateur."
                        } else {
                            SettingsStore.set(name, ps.copy(role = next))
                        }
                    }
                }
                item {
                    SettingsRow("Limite d'âge", ps.ageMax?.let { "Jusqu'à $it ans" } ?: "Aucune limite") {
                        val i = AGE_STEPS.indexOf(ps.ageMax).coerceAtLeast(0)
                        SettingsStore.set(name, ps.copy(ageMax = AGE_STEPS[(i + 1) % AGE_STEPS.size]))
                    }
                }
                item {
                    SettingsRow(
                        "Nouveaux titres ajoutés au disque",
                        if (ps.newHidden) "Cachés jusqu'à ce que tu les coches" else "Visibles automatiquement",
                    ) { SettingsStore.set(name, ps.copy(newHidden = !ps.newHidden)) }
                }
                item {
                    Column {
                    Spacer(Modifier.height(18.dp))
                    SectionTitle("Ce que $name peut regarder")
                    Spacer(Modifier.height(4.dp))
                    MetaText("Coche ou décoche un titre, ou une catégorie entière.")
                    Spacer(Modifier.height(10.dp))
                    }
                }
                if (l == null) {
                    item {
                        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = NetRed)
                        }
                    }
                } else {
                    item {
                        Column {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SmallButton("Tout afficher") { SettingsStore.set(name, ps.withVisible(l.titles.map { it.id }, true)) }
                            SmallButton("Tout cacher") { SettingsStore.set(name, ps.withVisible(l.titles.map { it.id }, false)) }
                        }
                        Spacer(Modifier.height(10.dp))
                        }
                    }
                    for (c in l.categories) {
                        item(key = "cat:" + c.name) {
                            val visibleCount = c.titles.count { ps.isVisible(it, Tmdb.metas[it.id]?.certification) }
                            CheckRow(
                                label = c.name,
                                sub = "$visibleCount / ${c.titles.size} visibles",
                                checked = visibleCount == c.titles.size,
                                partial = visibleCount in 1 until c.titles.size,
                                header = true,
                            ) {
                                SettingsStore.set(name, ps.withVisible(c.titles.map { it.id }, visibleCount < c.titles.size))
                            }
                        }
                        items(c.titles, key = { "t:" + it.id }) { t ->
                            val cert = Tmdb.metas[t.id]?.certification
                            val visible = ps.isVisible(t, cert)
                            val age = Access.certAge(cert)
                            CheckRow(
                                label = t.name,
                                sub = listOfNotNull(
                                    if (t.kind == Kind.SERIES) "Série" else "Film",
                                    t.licence,
                                    age?.let { if (it == 0) "Tous publics" else "$it+" },
                                    if (!visible && t.id !in ps.hidden && !ps.newHidden) "caché par la limite d'âge" else null,
                                ).joinToString("  ·  "),
                                checked = visible,
                                partial = false,
                                header = false,
                                title = t,
                            ) { SettingsStore.set(name, ps.withVisible(t.id, !visible)) }
                        }
                    }
                }
            }
        }
    }
    message?.let {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Text(
                it,
                color = Color.White,
                modifier = Modifier
                    .padding(bottom = 30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xE6262626))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
    AutoFocus(profileReq)
}

@Composable
private fun SettingsRow(
    title: String,
    value: String,
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    leading: (@Composable () -> Unit)? = null,
    onFocused: () -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
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
            .background(
                when {
                    focused -> Color.White
                    selected -> Color(0x33FFFFFF)
                    else -> Color(0xFF1A1A1A)
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = if (focused) Color.Black else Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (value.isNotEmpty()) {
                Text(value, color = if (focused) Color(0xFF444444) else TextGray, fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun CheckRow(
    label: String,
    sub: String,
    checked: Boolean,
    partial: Boolean,
    header: Boolean,
    title: Title? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (header) 0.dp else 24.dp, top = if (header) 12.dp else 0.dp)
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
            .clip(RoundedCornerShape(6.dp))
            .background(if (focused) Color(0x33FFFFFF) else Color.Transparent)
            .thenIf(focused) { border(1.dp, Color.White, RoundedCornerShape(6.dp)) }
            .padding(horizontal = 12.dp, vertical = if (header) 10.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (checked) NetRed else Color.Transparent)
                .border(2.dp, if (checked) NetRed else Color(0xFF8C8C8C), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                checked -> Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                partial -> Box(Modifier.size(10.dp, 3.dp).background(Color.White))
            }
        }
        Spacer(Modifier.width(14.dp))
        if (title != null) {
            val img = rememberTitleImage(title, backdrop = false)
            FileImage(img, Modifier.size(80.dp, 45.dp).clip(RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = if (checked || focused) Color.White else Color(0xFF9A9A9A),
                fontSize = if (header) 19.sp else 16.sp,
                fontWeight = if (header) FontWeight.Black else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (sub.isNotEmpty()) Text(sub, color = TextGray, fontSize = 12.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        label,
        color = if (focused) Color.Black else Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
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
            .clip(RoundedCornerShape(5.dp))
            .background(if (focused) Color.White else Color(0x40808080))
            .padding(horizontal = 18.dp, vertical = 9.dp),
    )
}
