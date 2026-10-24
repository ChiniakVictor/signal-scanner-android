package com.victor.signalscanner

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.cos
import kotlin.math.sin

fun rsrpColor(v: Int?): Color = when {
    v == null -> Muted; v >= -80 -> Green; v >= -90 -> Color(0xFFA5DE6A); v >= -100 -> Amber; v >= -110 -> Orange; else -> Red
}

private fun CellEntry.samples(sel: (CellSample) -> Number?) = history.mapNotNull { s -> sel(s)?.let { Sample(s.t, it.toInt()) } }

@Composable
fun CellTab(cells: List<CellEntry>, wide: Boolean, now: Long) {
    val st by CellRepo.state.collectAsStateWithLifecycle()
    var sel by rememberSaveable { mutableStateOf<String?>(null) }
    val testSel by TestHooks.select.collectAsStateWithLifecycle()
    LaunchedEffect(testSel, cells) { testSel?.let { t -> cells.firstOrNull { it.role.contains(t, true) || it.key.contains(t, true) }?.let { sel = it.key; TestHooks.select.value = null } } }
    val selected = cells.firstOrNull { it.key == sel }
    ListDetail(wide, selected != null, { sel = null }, list = {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
                    if (!st.supported) { Text("No cellular radio on this device", color = Muted); return@Column }
                    Text(st.operator ?: "No network", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    KV("Data network", listOfNotNull(st.dataType, st.displayType?.let { "shown as: $it" }).joinToString(" · "))
                    KV("Voice network", st.voiceType)
                    KV("5G (NR) status", st.nrState)
                    KV("Carrier aggregation", st.carrierAggregation)
                    KV("Carrier bandwidths", st.bandwidths)
                    KV("SIM operator", st.simOperator)
                    st.sims.forEach { KV(it.first, it.second) }
                    if (st.roaming) KV("Roaming", "yes", color = Amber)
                    st.error?.let { Text(it, color = Amber, fontSize = 12.sp) }
                    Text("Updated every 5 s · ${if (st.lastUpdate > 0) "${Fmt.ago(now - st.lastUpdate)} ago" else "waiting…"}", color = Muted, fontSize = 12.sp)
                }
            }
            if (cells.isEmpty()) item { Text("No cells reported yet…", color = Muted, modifier = Modifier.padding(20.dp)) }
            items(cells, key = { it.key }) { c ->
                Row(Modifier.fillMaxWidth().background(if (c.key == sel) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background)
                    .clickable { sel = c.key }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(c.main?.toString() ?: "—", color = rsrpColor(c.main), fontWeight = FontWeight.Bold)
                        Text(c.mainName, color = Muted, fontSize = 10.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(listOfNotNull(c.tech, c.bands, c.pci?.let { "PCI $it" }).joinToString(" · "), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(c.arfcn?.let { "${c.arfcnName} $it" }, c.freqMhz?.let { "%.1f MHz".format(it) }, c.cellId, c.area).joinToString(" · "), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row {
                            Pill(c.role, when (c.role) { "Serving" -> Green; "Secondary" -> Cyan; else -> Muted })
                            c.q?.let { Pill("${c.qName} $it") }; c.sinr?.let { Pill("${c.sinrName} ${it.toInt()}") }
                        }
                    }
                    Sparkline(c.samples { it.main }, Modifier.width(70.dp).height(28.dp), color = rsrpColor(c.main), low = -140, high = -44, gapMs = 20_000)
                }
            }
        }
    }, detail = {
        selected?.let { c ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                DetailHeader("${c.tech} · ${c.role}", listOfNotNull(c.operator, c.mcc?.let { "MCC $it" }, c.mnc?.let { "MNC $it" }).joinToString(" · "), { sel = null })
                Text("${c.mainName} ${c.main ?: "—"} dBm", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = rsrpColor(c.main))
                Sparkline(c.samples { it.main }, Modifier.fillMaxWidth().height(130.dp).padding(vertical = 4.dp), color = rsrpColor(c.main), axes = true, low = -140, high = -44, gapMs = 20_000)
                if (c.q != null) { Text("${c.qName} (dB)", color = Muted, fontSize = 12.sp); Sparkline(c.samples { it.q }, Modifier.fillMaxWidth().height(90.dp), color = Purple, axes = true, low = -25, high = 0, gapMs = 20_000) }
                if (c.sinr != null) { Text("${c.sinrName} (dB)", color = Muted, fontSize = 12.sp); Sparkline(c.samples { it.sinr }, Modifier.fillMaxWidth().height(90.dp), color = Orange, axes = true, low = -10, high = 35, gapMs = 20_000) }
                Section("Cell") {
                    KV("Technology", c.tech); KV("Role", c.role + if (c.registered) " (registered)" else "")
                    KV("MCC / MNC", listOfNotNull(c.mcc, c.mnc).joinToString(" / ").ifEmpty { null }); KV("Operator", c.operator)
                    KV("Area", c.area); KV("Cell ID", c.cellId); KV("Site", c.enb); KV("PCI / PSC", c.pci?.toString())
                    KV(c.arfcnName, c.arfcn?.toString()); KV("Band", c.bands); KV("Downlink", c.freqMhz?.let { "%.1f MHz".format(it) }); KV("Bandwidth", c.bandwidthMhz?.let { "$it MHz" })
                }
                Section("Signal") {
                    KV(c.mainName, c.main?.let { "$it dBm" }); if (c.qName.isNotEmpty()) KV(c.qName, c.q?.let { "$it dB" }); if (c.sinrName.isNotEmpty()) KV(c.sinrName, c.sinr?.let { "%.0f dB".format(it) })
                    KV("RSSI", c.rssi?.let { "$it dBm" }); KV("CQI", c.cqi?.toString()); KV("Timing advance", c.ta?.let { if (c.tech == "LTE") "$it (≈ ${"%.0f".format(it * 78.12)} m to the tower)" else "$it" })
                    c.extra.forEach { KV(it.first, it.second) }
                    val h = c.history.mapNotNull { it.main }
                    if (h.size > 1) KV("History", "${h.size} samples · min ${h.min()} · avg ${h.average().toInt()} · max ${h.max()} dBm")
                }
                Text("Android only reports neighbor cells the modem chooses to share; many show just PCI, channel and signal.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
            }
        }
    }, empty = "Tap a cell to see its signal history")
}

val constColors = mapOf("GPS" to Color(0xFF4DD0E1), "GLONASS" to Color(0xFFFF6B6B), "Galileo" to Color(0xFF64B5F6), "BeiDou" to Color(0xFFFFC857),
    "QZSS" to Color(0xFFB39DFF), "SBAS" to Color(0xFF8B97A6), "NavIC" to Color(0xFFFFA94D))
private val flags = mapOf("GPS" to "🇺🇸", "GLONASS" to "🇷🇺", "Galileo" to "🇪🇺", "BeiDou" to "🇨🇳", "QZSS" to "🇯🇵", "NavIC" to "🇮🇳", "SBAS" to "🛰")

@Composable
fun GnssTab(sats: List<Sat>, wide: Boolean, now: Long) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val listener = remember { GnssListener(ctx.applicationContext) }
    // listen only while this tab is shown and the app is in front
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> when (e) { Lifecycle.Event.ON_START -> listener.start(); Lifecycle.Event.ON_STOP -> listener.stop(); else -> {} } }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) listener.start()
        onDispose { owner.lifecycle.removeObserver(obs); listener.stop() }
    }
    val st by GnssRepo.state.collectAsStateWithLifecycle()
    var sort by rememberSaveable { mutableStateOf("Signal") }
    val list = remember(sats, sort) { when (sort) {
        "Constellation" -> sats.sortedWith(compareBy({ it.constellation }, { it.svid }))
        "Elevation" -> sats.sortedByDescending { it.elev }
        else -> sats.sortedWith(compareBy({ !it.used }, { -it.cn0 }))
    } }
    val info: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
            val used = sats.count { it.used }
            val dual = sats.filter { it.signal != null && it.signal !in setOf("L1", "E1", "B1C", "B1I", "G1") }.map { "${it.constellation}-${it.svid}" }.toSet()
            Text(if (st.error != null) st.error!! else if (st.fix != null) "Fix · $used used of ${sats.size} signals" else "Searching… ${sats.size} signals, $used used", fontWeight = FontWeight.SemiBold,
                color = if (st.error != null) Amber else if (st.fix != null) Green else Amber)
            st.fix?.let { f ->
                KV("Position", "%.5f, %.5f".format(f.lat, f.lon))
                KV("Accuracy", listOfNotNull(f.acc?.let { "±%.0f m horizontal".format(it) }, f.vAcc?.let { "±%.0f m vertical".format(it) }).joinToString(" · "))
                KV("Altitude", f.alt?.let { "%.0f m".format(it) }); KV("Speed", f.speed?.let { "%.1f km/h".format(it * 3.6) })
                KV("Fix time", "${Fmt.time(f.time)} (${Fmt.ago(now - f.time)} ago)")
            }
            KV("Time to first fix", st.ttffMs?.let { "%.1f s".format(it / 1000.0) } ?: if (st.listening) "waiting (listening ${Fmt.ago(now - st.listenSince)})" else "—")
            KV("Satellites", sats.groupBy { it.constellation }.entries.sortedByDescending { it.value.size }.joinToString(", ") { (k, v) -> "$k ${v.map { it.svid }.distinct().size}" }.ifEmpty { "none" })
            KV("Dual-frequency", if (dual.isNotEmpty()) "yes – ${dual.size} satellite${if (dual.size == 1) "" else "s"} also on L5/E5a/B2a" else "no second-band signals seen")
            KV("Receiver", st.gnssHw); KV("Raw measurements", st.capabilities)
            Text("GNSS listens only while this tab is open. Indoors expect few or no satellites.", color = Muted, fontSize = 12.sp)
        }
    }
    val sky: @Composable (Modifier) -> Unit = { m -> SkyPlot(sats, m) }
    val satList: @Composable () -> Unit = {
        ChipRow { SortMenu(listOf("Signal", "Constellation", "Elevation"), sort) { sort = it }
            constColors.filterKeys { k -> sats.any { it.constellation == k } }.forEach { (k, c) -> Legend(c, k) } }
        list.forEach { s -> SatRow(s) }
    }
    if (wide) Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) {
            info(); Spacer(Modifier.height(10.dp)); sky(Modifier.fillMaxWidth().aspectRatio(1f))
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { satList(); Spacer(Modifier.height(24.dp)) }
    } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        info(); Spacer(Modifier.height(10.dp)); sky(Modifier.fillMaxWidth().aspectRatio(1f)); satList(); Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SatRow(s: Sat) {
    val c = constColors[s.constellation] ?: Muted
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${flags[s.constellation] ?: ""} ${s.constellation} ${s.svid}", color = c, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.width(130.dp))
        Text(s.signal ?: "", fontSize = 12.sp, color = if (s.signal in setOf("L5", "E5a", "B2a", "E5b", "B2b")) Orange else Muted, modifier = Modifier.width(44.dp))
        Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x22FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((s.cn0 / 50f).coerceIn(0.02f, 1f)).background(if (s.used) Green else if (s.cn0 >= 20) Amber else Red))
        }
        Text(" %.0f".format(s.cn0), fontSize = 12.sp, modifier = Modifier.width(30.dp))
        Text("%.0f°/%.0f°".format(s.elev, s.azim), fontSize = 11.sp, color = Muted, modifier = Modifier.width(72.dp))
        Text(listOfNotNull("FIX".takeIf { s.used }, "E".takeIf { s.ephemeris }, "A".takeIf { s.almanac }).joinToString(" "), fontSize = 11.sp, color = if (s.used) Green else Muted, modifier = Modifier.width(52.dp))
    }
}

/** Polar sky plot: center = overhead, edge = horizon, north up. Filled dots are used in the fix. */
@Composable
fun SkyPlot(sats: List<Sat>, modifier: Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)) {
        val cx = size.width / 2; val cy = size.height / 2; val r = minOf(cx, cy) - 40f
        val grid = Color(0x33FFFFFF)
        for (e in listOf(0, 30, 60)) drawCircle(grid, r * (90 - e) / 90f, Offset(cx, cy), style = Stroke(2f))
        drawLine(grid, Offset(cx - r, cy), Offset(cx + r, cy)); drawLine(grid, Offset(cx, cy - r), Offset(cx, cy + r))
        val p = Paint().apply { color = Color(0xAAE6EDF3).toArgb(); textSize = 28f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        drawContext.canvas.nativeCanvas.apply { drawText("N", cx, cy - r - 8f, p); drawText("S", cx, cy + r + 30f, p); drawText("E", cx + r + 20f, cy + 10f, p); drawText("W", cx - r - 20f, cy + 10f, p) }
        val lp = Paint().apply { textSize = 20f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        sats.groupBy { "${it.constellation}-${it.svid}" }.values.forEach { g ->
            val s = g.maxBy { it.cn0 }
            if (s.elev <= 0 && s.azim == 0f) return@forEach
            val rr = r * (90 - s.elev.coerceIn(0f, 90f)) / 90f
            val a = Math.toRadians(s.azim.toDouble())
            val x = cx + (rr * sin(a)).toFloat(); val y = cy - (rr * cos(a)).toFloat()
            val c = constColors[s.constellation] ?: Muted
            val rad = 10f + (s.cn0 / 50f).coerceIn(0f, 1f) * 10f
            if (g.any { it.used }) drawCircle(c, rad, Offset(x, y)) else drawCircle(c, rad, Offset(x, y), style = Stroke(3f))
            if (g.size > 1) drawCircle(Orange, rad + 5f, Offset(x, y), style = Stroke(2f))   // dual-frequency ring
            lp.color = c.toArgb(); drawContext.canvas.nativeCanvas.drawText("${s.svid}", x, y - rad - 4f, lp)
        }
    }
}
