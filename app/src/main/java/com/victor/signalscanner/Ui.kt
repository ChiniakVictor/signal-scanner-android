package com.victor.signalscanner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Sat.usedInFixSafe() = used

object Fmt {
    fun ago(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when { s < 60 -> "${s}s"; s < 3600 -> "${s / 60}m ${s % 60}s"; else -> "${s / 3600}h ${(s % 3600) / 60}m" }
    }
    fun time(t: Long): String = if (t <= 0) "—" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t))
    fun dateTime(t: Long): String = if (t <= 0) "—" else SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(Date(t))
    fun dist(m: Double?): String = when { m == null -> "—"; m < 1 -> "< 1 m"; m < 10 -> "≈ %.1f m".format(m); m < 1000 -> "≈ %.0f m".format(m); else -> "> 1 km" }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title.uppercase(), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 6.dp), content = content)
}

@Composable
fun KV(k: String, v: String?, mono: Boolean = false, color: Color = Color.Unspecified) {
    if (v.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(k, color = Muted, fontSize = 13.sp, modifier = Modifier.width(150.dp))
        SelectionContainer { Text(v, fontSize = 13.sp, color = color, fontFamily = if (mono) FontFamily.Monospace else null) }
    }
}

@Composable
fun Pill(text: String, color: Color = Muted) {
    Text(text, fontSize = 11.sp, color = color, maxLines = 1,
        modifier = Modifier.padding(end = 4.dp, top = 2.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp))
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onChange, modifier = modifier.heightIn(min = 48.dp), singleLine = true, placeholder = { Text(hint, fontSize = 14.sp) },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Default.Clear, "Clear") } },
        shape = RoundedCornerShape(12.dp), textStyle = LocalTextStyle.current.copy(fontSize = 14.sp))
}

@Composable
fun ChipRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, content = content)

@Composable
fun FChip(label: String, selected: Boolean, onClick: () -> Unit) =
    FilterChip(selected = selected, onClick = onClick, label = { Text(label, fontSize = 13.sp) })

@Composable
fun SortMenu(options: List<String>, current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("Sort: $current", fontSize = 13.sp) })
        DropdownMenu(open, { open = false }) { options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(o) }) } }
    }
}

@Composable
fun DetailHeader(title: String, sub: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SelectionContainer { Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(sub, color = Muted, fontSize = 13.sp)
        }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close") }
    }
}

/** Generic list/detail layout: side by side on wide screens (unfolded Fold), stacked otherwise. */
@Composable
fun ListDetail(wide: Boolean, hasSelection: Boolean, onBack: () -> Unit, list: @Composable () -> Unit, detail: @Composable () -> Unit, empty: String) {
    if (!wide && hasSelection) androidx.activity.compose.BackHandler { onBack() }
    if (wide) Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxHeight()) { list() }
        VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (hasSelection) detail() else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(empty, color = Muted) }
        }
    } else if (hasSelection) detail() else list()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    scanner: Scanner,
    missingPerms: List<String>,
    onGrant: () -> Unit,
    onExport: (String) -> Unit,
    onSettingsChanged: () -> Unit,
    onPauseToggle: () -> Unit,
    paused: Boolean,
) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val radios by Repo.radios.collectAsStateWithLifecycle()
    val wifiState by Repo.wifiState.collectAsStateWithLifecycle()
    val btState by Repo.btState.collectAsStateWithLifecycle()
    val nfcState by Repo.nfcState.collectAsStateWithLifecycle()
    val aps by Repo.wifiAps.collectAsStateWithLifecycle()
    val devs by Repo.bt.collectAsStateWithLifecycle()
    val taps by Repo.taps.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    LaunchedEffect(taps.size) { if (taps.isNotEmpty() && taps.first().time > System.currentTimeMillis() - 3000) tab = 7 }
    val testTab by TestHooks.tab.collectAsStateWithLifecycle()
    LaunchedEffect(testTab) { testTab?.let { tab = it; TestHooks.tab.value = null } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Signal Scanner", fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = onPauseToggle) { Text(if (paused) "Resume" else "Pause", color = if (paused) Green else Amber) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Export Wi-Fi (CSV)") }, onClick = { menu = false; onExport("wifi") })
                        DropdownMenuItem(text = { Text("Export Bluetooth (CSV)") }, onClick = { menu = false; onExport("bt") })
                        DropdownMenuItem(text = { Text("Export NFC tap log (CSV)") }, onClick = { menu = false; onExport("nfc") })
                        DropdownMenuItem(text = { Text("Export cells (CSV)") }, onClick = { menu = false; onExport("cell") })
                        DropdownMenuItem(text = { Text("Export everything (JSON)") }, onClick = { menu = false; onExport("json") })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { menu = false; showSettings = true })
                        DropdownMenuItem(text = { Text("About") }, onClick = { menu = false; showAbout = true })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize()) {
            val wide = maxWidth >= 600.dp
            Column(Modifier.fillMaxSize()) {
                RadioStrip(radios, wifiState, btState, nfcState, paused, now, settings)
                if (missingPerms.isNotEmpty()) PermissionCard(missingPerms, onGrant)
                val locOn by Repo.locationOn.collectAsStateWithLifecycle()
                val autoOk by AutoLocation.permitted.collectAsStateWithLifecycle()
                if (!locOn) WarnCard("Location is off. Android only gives apps Wi-Fi scan results and nearby Bluetooth devices when Location is on." +
                    if (!autoOk && AutoLocation.enabled) "\n\nAuto Location (on while open, off when you leave) needs a one-time grant from a computer:\n${AutoLocation.ADB_GRANT}" else "", "Turn on") {
                    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                }
                if (tab == 7 && nfcState.supported && !nfcState.enabled) WarnCard("NFC is off.", "Open NFC settings") {
                    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NFC_SETTINGS)) }
                }
                val cells by CellRepo.cells.collectAsStateWithLifecycle()
                val sats by GnssRepo.sats.collectAsStateWithLifecycle()
                ScrollableTabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background, edgePadding = 8.dp) {
                    Tab(tab == 0, { tab = 0 }, text = { Text("Wi-Fi (${aps.count { it.inRange }})") })
                    Tab(tab == 1, { tab = 1 }, text = { Text("Bluetooth (${devs.count { it.inRange }})") })
                    val trk by TrackerWatch.trackers.collectAsStateWithLifecycle()
                    val following = trk.count { it.verdict.following }
                    Tab(tab == 2, { tab = 2 }, text = { Text(if (following > 0) "Trackers ⚠$following" else "Trackers (${trk.size})", color = if (following > 0) Red else Color.Unspecified) })
                    Tab(tab == 3, { tab = 3 }, text = { Text("Cell (${cells.size})") })
                    Tab(tab == 4, { tab = 4 }, text = { Text("GNSS (${sats.count { it.usedInFixSafe() }}/${sats.size})") })
                    Tab(tab == 5, { tab = 5 }, text = { Text("Magnet") })
                    Tab(tab == 6, { tab = 6 }, text = { Text("Ultrasonic") })
                    Tab(tab == 7, { tab = 7 }, text = { Text("NFC (${taps.size})") })
                    Tab(tab == 8, { tab = 8 }, text = { Text("Radios") })
                }
                when (tab) {
                    0 -> WifiTab(aps, wifiState, wide, now, scanner)
                    1 -> BtTab(devs, btState, wide, now)
                    2 -> TrackersTab(wide, now)
                    3 -> CellTab(cells, wide, now)
                    4 -> GnssTab(sats, wide, now)
                    5 -> MagnetTab()
                    6 -> UltrasonicTab()
                    7 -> NfcTab(taps, nfcState, wide, now)
                    else -> RadiosTab(radios, wifiState, btState, nfcState, now)
                }
            }
        }
    }
    if (showSettings) SettingsDialog(settings) { changed -> showSettings = false; if (changed) onSettingsChanged() }
    if (showAbout) AlertDialog(onDismissRequest = { showAbout = false }, confirmButton = { TextButton(onClick = { showAbout = false }) { Text("OK") } },
        title = { Text("Signal Scanner 1.0") },
        text = { Text(ABOUT, fontSize = 13.sp, modifier = Modifier.verticalScroll(rememberScrollState())) })
}

const val ABOUT = """Scans Wi-Fi, Bluetooth (LE + classic) and NFC while the app is open. Scanning stops as soon as you leave the app – there is no background service.

Wi-Fi: Android lets an app request a scan 4 times per 2 minutes (Developer options › Wi-Fi scan throttling). The default 30 s interval stays inside that limit; when Android refuses a scan the list shows its cached results and their age.

Bluetooth: a continuous LE scan runs while the app is open; signal history is sampled every 5 s. Classic discovery repeats in the background of the scan when enabled.

NFC: while the app is on screen it uses reader mode, so tapping a card shows its details instead of opening another app (and tap-to-pay won't work while Signal Scanner is in front).

Distances are rough estimates from signal strength (free-space / log-distance models). Walls and bodies make them unreliable; use Wi-Fi RTT (802.11mc) for real measurements.

Nothing is sent anywhere. Exports are saved to Download/SignalScanner on this phone.

Data: IEEE OUI registry (public), Bluetooth SIG assigned numbers (company IDs, UUIDs)."""

@Composable
fun RadioStrip(r: Radios?, w: WifiState, b: BtState, n: NfcState, paused: Boolean, now: Long, s: Settings) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (paused) "● PAUSED" else "● SCANNING", color = if (paused) Amber else Green, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            Text("Wi-Fi every ${s.wifiIntervalSec}s · Bluetooth every ${s.btIntervalSec}s", color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            fun st(avail: Boolean?, on: Boolean, active: Boolean): Pair<String, Color> = when {
                avail == false -> "not available" to Muted
                !on -> "off" to Red
                active -> "scanning" to Green
                else -> "on" to Cyan
            }
            val wf = st(r?.wifi, w.enabled || w.scanAlwaysAvailable, !paused && w.lastRequest > 0 && w.error == null)
            val bc = st(r?.bt, b.enabled, !paused && b.discovering)
            val bl = st(r?.ble, b.enabled, !paused && b.bleScanning)
            val nf = st(r?.nfc, n.enabled, n.readerActive)
            Pill("Wi-Fi: ${wf.first}", wf.second); Pill("BT classic: ${bc.first}", bc.second); Pill("BLE: ${bl.first}", bl.second)
            Pill("NFC: ${if (nf.second == Green) "reader on" else nf.first}", nf.second)
            if (r?.rtt == true) Pill("Wi-Fi RTT", Purple)
            if (r?.uwb == true) Pill("UWB present", Purple)
        }
    }
}

@Composable
fun PermissionCard(missing: List<String>, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33FFC857)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Warning, null, tint = Amber)
        Spacer(Modifier.width(8.dp))
        Text("Needs permission: ${missing.joinToString(", ")}. Without it Android hides nearby Wi-Fi/Bluetooth devices.", fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onGrant) { Text("Grant") }
    }
}

@Composable
fun WarnCard(text: String, action: String, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33FFC857)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Warning, null, tint = Amber)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun RadiosTab(r: Radios?, w: WifiState, b: BtState, n: NfcState, now: Long) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        fun yn(x: Boolean?) = when (x) { true -> "present"; false -> "not present"; null -> "?" }
        Section("Hardware on this phone") {
            KV("Wi-Fi", yn(r?.wifi)); KV("Bluetooth classic", yn(r?.bt)); KV("Bluetooth LE", yn(r?.ble)); KV("NFC", yn(r?.nfc))
            KV("Wi-Fi RTT (802.11mc)", yn(r?.rtt)); KV("Wi-Fi Aware (NAN)", yn(r?.wifiAware)); KV("Ultra-wideband", yn(r?.uwb) + " (no public scanning API)")
        }
        Section("Wi-Fi scanning") {
            KV("Wi-Fi", if (w.enabled) "on" else if (w.scanAlwaysAvailable) "off (but 'Wi-Fi scanning' location setting is on)" else "off")
            KV("Location services", if (w.locationOn) "on" else "OFF – Android returns no scan results")
            KV("Throttling setting", w.throttleSetting)
            KV("Throttling observed", w.throttleInferred)
            KV("Last scan request", if (w.lastRequest > 0) "${Fmt.time(w.lastRequest)} (${Fmt.ago(now - w.lastRequest)} ago) – " + when (w.lastRequestAccepted) { true -> "accepted"; false -> "refused by Android (throttled or busy)"; null -> "" } else "—")
            KV("Freshest result", if (w.newestResultMs > 0) "${Fmt.ago(now - w.newestResultMs)} old" else "—")
            w.error?.let { KV("Problem", it, color = Amber) }
        }
        if (w.events.isNotEmpty()) Section("Recent scan requests") {
            w.events.takeLast(12).reversed().forEach { e ->
                KV(Fmt.time(e.time), (if (e.accepted) "accepted" else "refused – cached results shown") + (e.updated?.let { if (it) ", new results (${e.count} APs)" else ", no new results" } ?: ""),
                    color = if (e.accepted) Color.Unspecified else Amber)
            }
        }
        if (w.caps.isNotEmpty()) Section("Wi-Fi chip capabilities") { w.caps.forEach { KV(it.first, it.second) } }
        RadiosExtra()
        Section("Bluetooth") {
            KV("Adapter", if (!b.supported) "not present" else if (b.enabled) "on" else "off")
            KV("Name", b.adapterName)
            KV("LE scan", if (b.bleScanning) "running (low-latency, all PHYs)" else "stopped")
            KV("Classic discovery", if (b.discovering) "running" else "idle")
            b.error?.let { KV("Problem", it, color = Amber) }
            b.caps.forEach { KV(it.first, it.second) }
        }
        Section("NFC") {
            KV("Adapter", if (!n.supported) "not present" else if (n.enabled) "on" else "off – turn on in Settings › Connections › NFC")
            if (n.supported) {
                KV("Reader mode", if (n.readerActive) "active while this app is open" else "inactive")
                KV("Secure NFC", if (!n.secureSupported) "not supported" else if (n.secureEnabled) "on (NFC only with screen unlocked)" else "off")
                KV("Host card emulation", if (n.hce) "supported (tap-to-pay etc.)" else "no")
            }
            n.note?.let { KV("Note", it) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SettingsDialog(s: Settings, onDone: (Boolean) -> Unit) {
    var wifi by remember { mutableFloatStateOf(s.wifiIntervalSec.toFloat()) }
    var bt by remember { mutableFloatStateOf(s.btIntervalSec.toFloat()) }
    var classic by remember { mutableStateOf(s.classicDiscovery) }
    var nfc by remember { mutableStateOf(s.nfcReader) }
    var screen by remember { mutableStateOf(s.keepScreenOn) }
    var forget by remember { mutableFloatStateOf(s.forgetMinutes.toFloat()) }
    var autoLoc by remember { mutableStateOf(AutoLocation.enabled) }
    AlertDialog(onDismissRequest = { onDone(false) },
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Wi-Fi scan every ${wifi.toInt()} s", fontWeight = FontWeight.SemiBold)
                Slider(wifi, { wifi = it }, valueRange = 5f..120f, steps = 22)
                Text(if (wifi < 30) "Below 30 s Android will refuse some scans (4 per 2 min) unless Wi-Fi scan throttling is off in Developer options; cached results are shown then." else "Fits Android's 4-scans-per-2-minutes limit.", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Text("Bluetooth sample every ${bt.toInt()} s", fontWeight = FontWeight.SemiBold)
                Slider(bt, { bt = it }, valueRange = 1f..30f, steps = 28)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(classic, { classic = it }); Text("Bluetooth classic discovery (finds non-LE devices; slows Wi-Fi a little while it runs)", fontSize = 13.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(nfc, { nfc = it }); Text("NFC reader mode while open (blocks tap-to-pay while the app is in front)", fontSize = 13.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(screen, { screen = it }); Text("Keep the screen on while scanning", fontSize = 13.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(autoLoc, { autoLoc = it }); Text("Auto Location (on while open, off when closed)", fontSize = 13.sp) }
                Text(if (!AutoLocation.canWrite()) "Not granted yet – run once from a computer: ${AutoLocation.ADB_GRANT}" else "Only turns Location off if Signal Scanner turned it on.", color = Muted, fontSize = 12.sp)
                Text("Forget devices not seen for ${forget.toInt()} min", fontWeight = FontWeight.SemiBold)
                Slider(forget, { forget = it }, valueRange = 1f..60f)
                Text("Scanning only runs while the app is open.", color = Muted, fontSize = 12.sp)
            }
        },
        confirmButton = { TextButton(onClick = {
            s.wifiIntervalSec = wifi.toInt(); s.btIntervalSec = bt.toInt(); s.classicDiscovery = classic; s.nfcReader = nfc; s.keepScreenOn = screen; s.forgetMinutes = forget.toInt()
            if (autoLoc != AutoLocation.enabled) AutoLocation.enabled = autoLoc
            onDone(true)
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text("Cancel") } })
}
