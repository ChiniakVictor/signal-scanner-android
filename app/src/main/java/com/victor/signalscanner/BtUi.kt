package com.victor.signalscanner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun BtTab(devs: List<BtDevice>, st: BtState, wide: Boolean, now: Long) {
    var sel by rememberSaveable { mutableStateOf<String?>(null) }
    var q by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf("All") }
    var sort by rememberSaveable { mutableStateOf("Signal") }
    var named by rememberSaveable { mutableStateOf(false) }
    var inRange by rememberSaveable { mutableStateOf(true) }
    val shown = remember(devs, q, kind, sort, named, inRange) {
        val ql = q.trim().lowercase()
        devs.filter { d ->
            (kind == "All" || (kind == "BLE" && d.ble) || (kind == "Classic" && d.classic) || (kind == "Paired" && d.bond == "Paired")) &&
                (!named || d.name != null) && (!inRange || d.inRange || (kind == "Paired" && d.bond == "Paired")) &&
                (ql.isEmpty() || listOf(d.name ?: "", d.address, d.vendor ?: "", d.tag ?: "", d.deviceType, d.services.joinToString(" ")).any { it.lowercase().contains(ql) })
        }.let { l -> when (sort) {
            "Name" -> l.sortedWith(compareBy({ it.name == null }, { it.displayName.lowercase() }))
            "Vendor" -> l.sortedWith(compareBy({ it.vendor ?: "~" }, { -(it.rssi ?: -200) }))
            "Last seen" -> l.sortedByDescending { it.lastSeen }
            "First seen" -> l.sortedByDescending { it.firstSeen }
            "Packets" -> l.sortedByDescending { it.packets }
            else -> l.sortedByDescending { it.rssi ?: -200 }
        } }
    }
    val testSel by TestHooks.select.collectAsStateWithLifecycle()
    LaunchedEffect(testSel, shown) { testSel?.let { t -> shown.firstOrNull { x -> x.toString().contains(t, true) }?.let { sel = it.address; TestHooks.select.value = null } } }
    val selected = devs.firstOrNull { it.address == sel }
    ListDetail(wide, selected != null, { sel = null }, list = {
        Column(Modifier.fillMaxSize()) {
            Text(listOfNotNull(if (st.bleScanning) "LE scan running" else "LE scan stopped", if (st.discovering) "classic discovery running" else null,
                "${devs.count { it.inRange }} in range, ${devs.size} total", st.error).joinToString(" · "),
                color = if (st.error != null) Amber else Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) { SearchField(q, { q = it }, "Name, address, vendor, type, UUID…", Modifier.fillMaxWidth()) }
            ChipRow {
                listOf("All", "BLE", "Classic", "Paired").forEach { k -> FChip(k, kind == k) { kind = k } }
                FChip("Named", named) { named = !named }
                FChip("In range", inRange) { inRange = !inRange }
                SortMenu(listOf("Signal", "Name", "Vendor", "Last seen", "First seen", "Packets"), sort) { sort = it }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (shown.isEmpty()) item { Text(st.error ?: "No devices yet…", color = Muted, modifier = Modifier.padding(20.dp)) }
                items(shown, key = { it.address }) { d -> BtRow(d, now, d.address == sel) { sel = d.address } }
            }
        }
    }, detail = { selected?.let { BtDetail(it, now) { sel = null } } }, empty = "Tap a device to see everything about it")
}

@Composable
fun BtRow(d: BtDevice, now: Long, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background)
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp).alpha(if (d.inRange) 1f else 0.5f), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
            SignalBars(d.rssi)
            Text(d.rssi?.toString() ?: "—", color = rssiColor(d.rssi), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(d.displayName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (d.name == null) Muted else MaterialTheme.colorScheme.onBackground)
            Text("${d.address} · ${d.vendor ?: if (d.isRandom) "random address" else "unknown vendor"}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Pill(d.kind, if (d.classic) Orange else Cyan)
                d.tag?.let { if (it != d.name) Pill(it.take(28), Purple) }
                if (d.bond == "Paired") Pill("paired", Green)
                d.txPower?.let { Pill("tx $it dBm") }
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.End) {
            Sparkline(d.history, Modifier.width(70.dp).height(28.dp), color = rssiColor(d.rssi), windowMs = 5 * 60_000L, gapMs = 20_000)
            Text(if (d.inRange) Fmt.dist(d.distanceM) else if (d.lastSeen > 0) "gone ${Fmt.ago(now - d.lastSeen)}" else "not seen", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
fun BtDetail(d: BtDevice, now: Long, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        DetailHeader(d.displayName, "${d.address}${d.vendor?.let { " · $it" } ?: ""}", onClose)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(d.rssi?.let { "$it dBm" } ?: "not in range", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = rssiColor(d.rssi))
            Spacer(Modifier.width(10.dp))
            Text("${Fmt.dist(d.distanceM)} (estimate)", color = Muted, modifier = Modifier.padding(bottom = 6.dp))
        }
        Sparkline(d.history, Modifier.fillMaxWidth().height(150.dp).padding(vertical = 6.dp), color = rssiColor(d.rssi), axes = true, gapMs = 20_000)
        val h = d.history.map { it.rssi }
        if (h.size > 1) Text("History: ${h.size} samples · min ${h.min()} · avg ${h.average().toInt()} · max ${h.max()} dBm", color = Muted, fontSize = 12.sp)

        Section("Device") {
            KV("Name", d.name ?: "not advertised")
            KV("Address", d.address, mono = true)
            KV("Address type", d.addressKind)
            KV("Vendor", d.vendor ?: if (d.isRandom) "unknown – random addresses carry no vendor" else "not in IEEE registry")
            KV("Type", d.deviceType)
            d.cod?.let { KV("Class of device", BtParse.classOfDevice(it)) }
            KV("Appearance", d.appearance)
            KV("Bond state", d.bond)
            KV("Seen via", listOfNotNull("LE advertising".takeIf { d.ble }, "classic inquiry".takeIf { d.classic }, "paired list".takeIf { !d.ble && !d.classic }).joinToString(" + "))
        }
        d.manufacturer.forEach { m ->
            Section("Manufacturer data · ${m.company} (0x%04X)".format(m.id)) {
                m.decoded?.let { dec -> Text(dec.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); dec.lines.forEach { Text("• $it", fontSize = 13.sp) } }
                SelectionContainer { Text(m.hex, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Muted) }
            }
        }
        d.serviceData.forEach { s ->
            Section("Service data · ${s.uuid}") {
                s.decoded?.let { dec -> Text(dec.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); dec.lines.forEach { Text("• $it", fontSize = 13.sp) } }
                SelectionContainer { Text(s.hex, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Muted) }
            }
        }
        if (d.services.isNotEmpty()) Section("Advertised services") { d.services.forEach { Text(it, fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) } }
        Section("Advertising") {
            KV("Connectable", yn(d.connectable))
            KV("Packet format", d.legacy?.let { if (it) "legacy (BT 4.x)" else "extended (BT 5)" })
            KV("PHY", d.phy)
            KV("Tx power (advertised)", d.txPower?.let { "$it dBm" })
            KV("Calibrated power", d.measuredPower?.let { "$it dBm at 1 m" })
            KV("Advertising interval", d.advIntervalMs?.let { "≈ %.0f ms (estimated from packet timing)".format(it) })
            KV("Periodic interval", d.periodicIntervalMs?.let { "%.1f ms".format(it) })
            KV("Advertising set ID", d.advSid?.toString())
            KV("Flags", d.flags)
            KV("Packets received", d.packets.toString())
            KV("First seen", Fmt.dateTime(d.firstSeen))
            KV("Last seen", if (d.lastSeen > 0) "${Fmt.dateTime(d.lastSeen)} (${Fmt.ago(now - d.lastSeen)} ago)" else "—")
        }
        if (d.fields.isNotEmpty()) Section("Advertisement structures") {
            d.fields.forEach { f ->
                Column(Modifier.padding(vertical = 3.dp)) {
                    Text("0x%02X · %s".format(f.type, f.name), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(f.value, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (d.raw.isNotEmpty()) Section("Raw advertisement") { SelectionContainer { Text(d.raw, fontFamily = FontFamily.Monospace, fontSize = 11.sp) } }
        Spacer(Modifier.height(30.dp))
    }
}
