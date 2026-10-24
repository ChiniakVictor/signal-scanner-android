package com.victor.signalscanner

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private fun km(m: Double) = if (m < 1000) "${m.toInt()} m" else "%.1f km".format(m / 1000)

fun findHint(family: String): String = when (family) {
    "apple" -> "Signal Scanner can't ring an AirTag (only Apple's protocol can). To make it beep: on this Samsung/Android phone use Settings → Safety and emergency → Unknown tracker alerts → Scan now, then 'Play sound'. On an iPhone, Find My → Items → 'Identify Found Item', or Apple's Tracker Detect app. Separated AirTags also beep on their own after a while."
    "samsung" -> "Open SmartThings → Life → SmartThings Find → 'Unknown tag search' (menu). It can make the SmartTag ring if it is separated from its owner."
    "google" -> "Use Settings → Safety and emergency → Unknown tracker alerts → Scan now; Find My Device tags can be made to play a sound from there."
    "tile" -> "Use the Tile app → 'Scan and Secure' to identify the Tile. Tiles can't be rung by strangers."
    "chipolo" -> "Use Settings → Safety and emergency → Unknown tracker alerts → Scan now (newer Chipolo models are on the Google/Apple networks). Older Chipolos can only be rung by their owner."
    else -> "Use Settings → Safety and emergency → Unknown tracker alerts → Scan now to try to play a sound on it."
}

private fun TrackerView.color() = when { verdict.following -> Red; row.trusted -> Green; verdict.allAtHome -> Cyan; else -> Amber }

@Composable
fun TrackersTab(wide: Boolean, now: Long) {
    val list by TrackerWatch.trackers.collectAsStateWithLifecycle()
    val fix by TrackerWatch.fix.collectAsStateWithLifecycle()
    var sel by rememberSaveable { mutableStateOf<String?>(null) }
    val testSel by TestHooks.select.collectAsStateWithLifecycle()
    LaunchedEffect(testSel, list) { testSel?.let { t -> list.firstOrNull { it.row.trackerId.contains(t, true) || it.row.type.contains(t, true) }?.let { sel = it.row.trackerId; TestHooks.select.value = null } } }
    val selected = list.firstOrNull { it.row.trackerId == sel }
    ListDetail(wide, selected != null, { sel = null }, list = {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                val n = list.count { it.verdict.following }
                Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(14.dp)).background(if (n > 0) Red.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
                    Text(if (n > 0) "⚠ $n tracker(s) may be following you" else "No tracker is following you", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = if (n > 0) Red else Green)
                    Text("${list.size} item tracker(s) seen in the last ${TrackerWatch.KEEP_DAYS} days · AirTag / Find My, SmartTag, Tile, Google Find My Device, Chipolo", color = Muted, fontSize = 12.sp)
                    Text("Location for sightings: " + (fix?.let { "±${it.accuracy.toInt()} m, ${Fmt.ago(now - it.time)} old" } ?: "none yet"), color = Muted, fontSize = 12.sp)
                }
            }
            if (list.isEmpty()) item { Text("No trackers seen yet.", color = Muted, modifier = Modifier.padding(20.dp)) }
            items(list, key = { it.row.trackerId }) { t ->
                val inRange = now - t.liveAt < 60_000
                Row(Modifier.fillMaxWidth().background(if (t.row.trackerId == sel) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background)
                    .clickable { sel = t.row.trackerId }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.width(56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (inRange) "${t.liveRssi}" else "—", color = if (inRange) rssiColor(t.liveRssi ?: -100) else Muted, fontWeight = FontWeight.Bold)
                        Text("dBm", color = Muted, fontSize = 10.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(t.row.type, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${t.row.address} · ${t.verdict.places.size} place(s) · ${t.sightings.size} sighting(s) · last ${Fmt.ago(now - t.row.lastSeen)} ago", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row {
                            Pill(when { t.verdict.following -> "FOLLOWING?"; t.row.trusted -> "Trusted"; t.verdict.allAtHome -> "Home only"; else -> "Watching" }, t.color())
                            when (t.row.separated) { true -> Pill("Separated from owner", Orange); false -> Pill("Owner nearby"); else -> {} }
                        }
                    }
                }
            }
            item { TrackerSettingsCard(fix) }
        }
    }, detail = { selected?.let { TrackerDetail(it, now) { sel = null } } }, empty = "Tap a tracker to see where it was seen")
}

@Composable
fun TrackerSettingsCard(fix: android.location.Location?) {
    var home by remember { mutableStateOf(TrackerWatch.home) }
    val r0 = remember { TrackerWatch.rule }
    var places by remember { mutableFloatStateOf(r0.minPlaces.toFloat()) }
    var sep by remember { mutableFloatStateOf(r0.placeSepM.toFloat()) }
    var mins by remember { mutableFloatStateOf(r0.minMinutes.toFloat()) }
    var bg by remember { mutableStateOf(TrackerWatch.backgroundWatch) }
    Column(Modifier.padding(horizontal = 12.dp)) {
        Section("Home") {
            Text(home?.let { "Home: %.4f, %.4f (radius %d m). Trackers seen only here never alert.".format(it.lat, it.lon, it.radiusM.toInt()) } ?: "No Home set. Set it while you're at home so your own household's trackers don't alert.", fontSize = 13.sp)
            Row {
                TextButton(enabled = fix != null, onClick = { fix?.let { val h = Home(it.latitude, it.longitude, 250.0); TrackerWatch.home = h; home = h } }) { Text("Set Home = here") }
                if (home != null) TextButton(onClick = { TrackerWatch.home = null; home = null }) { Text("Clear") }
            }
        }
        Section("Alert rule") {
            Text("Alert when seen at ${places.toInt()}+ places at least ${sep.toInt()} m apart", fontSize = 13.sp)
            Slider(places, { places = it }, valueRange = 2f..6f, steps = 3, onValueChangeFinished = { save(places, sep, mins) })
            Slider(sep, { sep = it }, valueRange = 200f..2000f, steps = 17, onValueChangeFinished = { save(places, sep, mins) })
            Text("…or with you for ${mins.toInt()}+ min while you moved ${sep.toInt()}+ m", fontSize = 13.sp)
            Slider(mins, { mins = it }, valueRange = 10f..120f, steps = 21, onValueChangeFinished = { save(places, sep, mins) })
            Text("Your paired Bluetooth devices are trusted automatically. Open a tracker to trust it.", color = Muted, fontSize = 12.sp)
        }
        Section("Tracker watch in background") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(bg, { bg = it; TrackerWatch.backgroundWatch = it })
                Spacer(Modifier.width(8.dp)); Text(if (bg) "On" else "Off (default)", fontWeight = FontWeight.SemiBold)
            }
            Text("Normally trackers are only checked while Signal Scanner is open. When on, a short low-power Bluetooth check runs every 3 minutes " +
                "with a small 'Tracker watch is on' notification. It needs Location on: Android hides Bluetooth trackers from apps when Location is off. " +
                "So while this is on, Auto Location leaves Location on when you close the app (otherwise the watch would see nothing).", color = Muted, fontSize = 12.sp)
        }
        Section("Limits") {
            Text("• Best at catching AirTags and other Find My items that are away from their owner – they keep the same address for about a day.\n" +
                "• Trackers near their owner change address about every 15 min, so they show up as new entries. That's expected and not the stalking case.\n" +
                "• Locations are approximate (network/fused), stored only on this phone for ${TrackerWatch.KEEP_DAYS} days.\n" +
                "• Android's own Unknown tracker alerts (Settings → Safety and emergency) work alongside this.", color = Muted, fontSize = 12.sp)
        }
    }
}

private fun save(p: Float, s: Float, m: Float) { TrackerWatch.rule = TrackerRule(p.toInt(), s.toDouble(), m.toInt(), s.toDouble()) }

@Composable
fun TrackerDetail(t: TrackerView, now: Long, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        DetailHeader(t.row.type, t.row.address, onClose)
        Text(t.verdict.reason, color = t.color(), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Sparkline(t.sightings.map { Sample(it.t, it.rssi) }, Modifier.fillMaxWidth().height(110.dp).padding(vertical = 6.dp), color = t.color(), axes = true, gapMs = 10 * 60_000L)
        Section("Tracker") {
            KV("Type", t.row.type); KV("Status", t.row.detail); KV("Address", t.row.address, mono = true); KV("Key bytes", t.row.payload, mono = true)
            KV("Signal now", if (now - t.liveAt < 60_000) "${t.liveRssi} dBm (${Fmt.ago(now - t.liveAt)} ago)" else "not in range")
            KV("First seen", Fmt.dateTime(t.row.firstSeen)); KV("Last seen", Fmt.dateTime(t.row.lastSeen))
            KV("Sightings (48 h)", "${t.sightings.size} · ${t.verdict.located} with location")
            KV("Places", "${t.verdict.places.size}"); KV("Travelled with you", km(t.verdict.travelledM)); KV("Spread", km(t.verdict.spreadM))
            KV("Time span", Fmt.ago(t.verdict.spanMs))
            if (t.row.alertedAt > 0) KV("Alerted", Fmt.dateTime(t.row.alertedAt), color = Red)
        }
        Row {
            OutlinedButton(onClick = { TrackerWatch.setTrusted(t.row.trackerId, !t.row.trusted) }) { Text(if (t.row.trusted) "Stop trusting" else "Trust this device") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { TrackerWatch.forget(t.row.trackerId); onClose() }) { Text("Forget") }
        }
        if (t.verdict.places.isNotEmpty()) {
            Section("Where it was seen") {
                PlacesPlot(t.verdict.places, TrackerWatch.home, Modifier.fillMaxWidth().height(200.dp))
                t.verdict.places.forEachIndexed { i, p ->
                    KV("Place ${i + 1}${if (p.atHome) " (Home)" else ""}", "%.4f, %.4f · %d× · %s–%s · best %d dBm".format(p.lat, p.lon, p.count, Fmt.time(p.first), Fmt.time(p.last), p.bestRssi))
                }
            }
        }
        Section("Find it / play a sound") { Text(findHint(t.row.family), fontSize = 13.sp) }
        Spacer(Modifier.height(24.dp))
    }
}

/** Simple local plot of sighting places (no online map): north up, scaled to fit, Home marked. */
@Composable
fun PlacesPlot(places: List<Place>, home: Home?, modifier: Modifier) {
    Canvas(modifier.padding(vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x14FFFFFF))) {
        val pts = places.map { it.lat to it.lon } + listOfNotNull(home?.let { it.lat to it.lon })
        val lat0 = pts.map { it.first }.average(); val lon0 = pts.map { it.second }.average()
        val k = Math.cos(Math.toRadians(lat0))
        fun xy(lat: Double, lon: Double) = ((lon - lon0) * k * 111320.0) to ((lat - lat0) * 110540.0)
        val m = pts.map { xy(it.first, it.second) }
        val span = maxOf(200.0, m.maxOf { kotlin.math.abs(it.first) } * 2, m.maxOf { kotlin.math.abs(it.second) } * 2) * 1.2
        val sc = (minOf(size.width, size.height) / span).toFloat()
        fun off(lat: Double, lon: Double) = xy(lat, lon).let { Offset(size.width / 2 + it.first.toFloat() * sc, size.height / 2 - it.second.toFloat() * sc) }
        val paint = android.graphics.Paint().apply { textSize = 26f; isAntiAlias = true; color = Color(0xFFE6EDF3).toArgb() }
        home?.let { h -> val c = off(h.lat, h.lon); drawCircle(Cyan.copy(alpha = 0.25f), (h.radiusM * sc).toFloat().coerceAtLeast(8f), c); drawContext.canvas.nativeCanvas.drawText("Home", c.x + 10, c.y - 10, paint) }
        val sorted = places.sortedBy { it.first }
        for (i in 1 until sorted.size) drawLine(Amber.copy(alpha = 0.6f), off(sorted[i - 1].lat, sorted[i - 1].lon), off(sorted[i].lat, sorted[i].lon), 3f)
        places.forEachIndexed { i, p -> val c = off(p.lat, p.lon); drawCircle(if (p.atHome) Cyan else Amber, 10f, c); drawCircle(Color.White, 10f, c, style = Stroke(2f))
            drawContext.canvas.nativeCanvas.drawText("${i + 1}", c.x + 12, c.y + 8, paint) }
        drawContext.canvas.nativeCanvas.drawText("N↑  ${km(span)} across", 12f, size.height - 12f, paint)
    }
}
