package com.victor.signalscanner

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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun WifiTab(aps: List<WifiAp>, st: WifiState, wide: Boolean, now: Long, scanner: Scanner) {
    var sel by rememberSaveable { mutableStateOf<String?>(null) }
    var q by rememberSaveable { mutableStateOf("") }
    var band by rememberSaveable { mutableStateOf("All") }
    var sort by rememberSaveable { mutableStateOf("Signal") }
    var view by rememberSaveable { mutableStateOf("List") }
    var inRangeOnly by rememberSaveable { mutableStateOf(true) }
    val conn by Repo.conn.collectAsStateWithLifecycle()
    val shown = remember(aps, q, band, sort, inRangeOnly) {
        val ql = q.trim().lowercase()
        aps.filter { (band == "All" || it.band == band) && (!inRangeOnly || it.inRange) &&
            (ql.isEmpty() || listOf(it.ssid, it.bssid, it.vendor ?: "", it.security, it.channel.toString(), it.shortStd).any { s -> s.lowercase().contains(ql) }) }
            .let { l -> when (sort) {
                "Name" -> l.sortedWith(compareBy({ it.hidden }, { it.ssid.lowercase() }, { -it.rssi }))
                "Channel" -> l.sortedWith(compareBy({ it.freq }, { -it.rssi }))
                "Vendor" -> l.sortedWith(compareBy({ it.vendor ?: "~" }, { -it.rssi }))
                "Last seen" -> l.sortedByDescending { it.lastSeen }
                "First seen" -> l.sortedByDescending { it.firstSeen }
                else -> l.sortedWith(compareBy({ !it.connected }, { -it.rssi }))
            } }
    }
    val testCh by TestHooks.channels.collectAsStateWithLifecycle()
    LaunchedEffect(testCh) { testCh?.let { view = if (it) "Channels" else "List"; TestHooks.channels.value = null } }
    val testSel by TestHooks.select.collectAsStateWithLifecycle()
    LaunchedEffect(testSel, shown) { testSel?.let { t -> shown.firstOrNull { x -> x.toString().contains(t, true) }?.let { sel = it.bssid; TestHooks.select.value = null } } }
    val selected = aps.firstOrNull { it.bssid == sel }
    ListDetail(wide, selected != null, { sel = null }, list = {
        Column(Modifier.fillMaxSize()) {
            ScanStatusLine(st, now)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(q, { q = it }, "SSID, BSSID, vendor, channel…", Modifier.weight(1f))
            }
            ChipRow {
                listOf("All", "2.4 GHz", "5 GHz", "6 GHz").forEach { b -> FChip(if (b == "All") "All bands" else b, band == b) { band = b } }
                FChip("In range", inRangeOnly) { inRangeOnly = !inRangeOnly }
                FChip("Channels", view == "Channels") { view = if (view == "Channels") "List" else "Channels" }
                SortMenu(listOf("Signal", "Name", "Channel", "Vendor", "Last seen", "First seen"), sort) { sort = it }
            }
            if (view == "Channels") {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    val bands = if (band == "All") listOf("2.4 GHz", "5 GHz", "6 GHz") else listOf(band)
                    bands.forEach { b ->
                        val n = aps.count { it.band == b && it.inRange }
                        Text("$b · $n network(s)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        if (n == 0) Text("Nothing heard on $b", color = Muted, fontSize = 13.sp)
                        else ChannelGraph(aps, b, Modifier.fillMaxWidth().height(230.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(6.dp), sel)
                    }
                    Text("Each shape spans the channel width the AP uses; height is signal strength. Overlapping shapes share airtime.", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
                    shown.forEach { a -> WifiRow(a, now, a.bssid == sel) { sel = a.bssid } }
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                conn?.let { c -> item(key = "conn") { ConnectedCard(c) { sel = c.bssid } } }
                if (shown.isEmpty()) item { Text(if (st.error != null) st.error else "No networks yet – waiting for the first scan…", color = Muted, modifier = Modifier.padding(20.dp)) }
                items(shown, key = { it.bssid }) { a -> WifiRow(a, now, a.bssid == sel) { sel = a.bssid } }
            }
        }
    }, detail = { selected?.let { WifiDetail(it, conn, now, scanner) { sel = null } } }, empty = "Tap a network to see everything about it")
}

@Composable
fun ScanStatusLine(st: WifiState, now: Long) {
    val age = if (st.newestResultMs > 0) now - st.newestResultMs else -1
    val cached = st.lastRequestAccepted == false || (age > 0 && st.lastRequest > 0 && st.newestResultMs < st.lastRequest - 2000 && now - st.lastRequest > 8000)
    val text = when {
        st.error != null -> st.error
        st.lastRequest == 0L -> "Starting Wi-Fi scan…"
        cached -> "Cached results (Android refused the scan – throttled) · newest ${Fmt.ago(age)} old"
        else -> "Fresh scan · results ${Fmt.ago(age.coerceAtLeast(0))} old · requested ${Fmt.ago(now - st.lastRequest)} ago"
    }
    Text(text, color = if (st.error != null || cached) Amber else Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp))
}

@Composable
fun ConnectedCard(c: ConnInfo, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(Green.copy(alpha = 0.10f)).clickable(onClick = onClick).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalBars(c.rssi); Spacer(Modifier.width(8.dp))
            Text("Connected: ${c.ssid ?: "(name hidden – needs location)"}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${c.rssi} dBm", color = rssiColor(c.rssi), fontWeight = FontWeight.Bold)
        }
        Text(listOfNotNull("${c.linkMbps} Mbit/s", c.txMbps.takeIf { it > 0 }?.let { "tx $it" }, c.rxMbps.takeIf { it > 0 }?.let { "rx $it" },
            "${WifiParse.band(c.freq)} ch ${WifiParse.channelOf(c.freq)}", WifiParse.shortStandard(c.standard, c.freq), c.ipv4.firstOrNull()).joinToString(" · "), color = Muted, fontSize = 12.sp)
    }
}

@Composable
fun WifiRow(a: WifiAp, now: Long, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (selected) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.background)
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp).alpha(if (a.inRange) 1f else 0.5f), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(48.dp)) {
            SignalBars(a.rssi)
            Text("${a.rssi}", color = rssiColor(a.rssi), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (a.hidden) Muted else MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f, false))
                if (a.connected) { Spacer(Modifier.width(6.dp)); Pill("connected", Green) }
            }
            Text("${a.bssid} · ${a.vendor ?: if (a.bssidRandom) "randomized BSSID" else "unknown vendor"}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Pill("${a.band} ch ${a.channel}", Cyan); Pill(a.widthName.replace(" MHz", "MHz"), Muted); Pill(a.shortStd, Purple)
                Pill(a.security.substringBefore(" (").replace("-Personal", "").replace("-Enterprise", "-Ent"), if (WifiParse.isOpen(a.security)) Red else Muted)
            }
        }
        Spacer(Modifier.width(6.dp))
        Column(horizontalAlignment = Alignment.End) {
            Sparkline(a.history, Modifier.width(70.dp).height(28.dp), color = rssiColor(a.rssi))
            Text(if (a.inRange) Fmt.dist(a.distanceM) else "gone ${Fmt.ago(now - a.lastSeen)}", color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
fun WifiDetail(a: WifiAp, conn: ConnInfo?, now: Long, scanner: Scanner, onClose: () -> Unit) {
    val rtt by Repo.rtt.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        DetailHeader(a.name, "${a.bssid}${a.vendor?.let { " · $it" } ?: ""}", onClose)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${a.rssi} dBm", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = rssiColor(a.rssi))
            Spacer(Modifier.width(10.dp))
            Text("${WifiParse.quality(a.rssi)} · ${Fmt.dist(a.distanceM)} (estimate)${if (!a.inRange) " · out of range" else ""}", color = Muted, modifier = Modifier.padding(bottom = 6.dp))
        }
        Sparkline(a.history, Modifier.fillMaxWidth().height(150.dp).padding(vertical = 6.dp), color = rssiColor(a.rssi), axes = true)
        val h = a.history.map { it.rssi }
        if (h.size > 1) Text("History: ${h.size} readings · min ${h.min()} · avg ${h.average().toInt()} · max ${h.max()} dBm", color = Muted, fontSize = 12.sp)

        if (a.connected && conn != null) Section("Your connection") { ConnFields(conn) }

        Section("Network") {
            KV("SSID", if (a.hidden) "(hidden – not broadcast)" else a.ssid)
            KV("BSSID", a.bssid, mono = true)
            KV("Vendor (OUI)", a.vendor ?: if (a.bssidRandom) "none – locally administered (virtual/randomized) BSSID" else "not in IEEE registry")
            KV("MLD address (Wi-Fi 7)", a.mldMac, mono = true)
            KV("AP name", a.ie?.apName)
            a.ie?.wpsDevice?.forEach { KV("WPS ${it.key}", it.value) }
            KV("Passpoint", if (a.passpoint || a.ie?.hs20 == true) "yes${a.operatorName?.let { " · $it" } ?: ""}" else "no")
            KV("Venue", a.venueName)
        }
        Section("Radio") {
            KV("Frequency", "${a.freq} MHz")
            KV("Band / channel", "${a.band} · channel ${a.channel}")
            KV("Channel width", a.widthName)
            KV("Center frequency", listOfNotNull(a.center0.takeIf { it > 0 }?.let { "$it MHz (ch ${WifiParse.channelOf(it)})" }, a.center1.takeIf { it > 0 }?.let { "$it MHz (ch ${WifiParse.channelOf(it)})" }).joinToString(" + ").ifEmpty { "${a.freq} MHz" })
            KV("Standard", a.standardName)
            KV("Spatial streams", a.ie?.streams?.takeIf { it > 0 }?.toString())
            KV("Max PHY rate", a.maxRate?.let { "%.0f Mbit/s (theoretical, ${a.ie?.streams ?: 1}×${a.widthMhz} MHz)".format(it) })
            KV("Basic/supported rates", a.ie?.rates?.distinct()?.sorted()?.joinToString(", ") { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }?.let { "$it Mbit/s" })
            KV("BSS color", a.ie?.bssColor?.toString())
            KV("DTIM period", a.ie?.dtim?.toString())
            KV("Country", a.ie?.country?.let { it + (a.ie.countryEnv?.let { e -> " ($e)" } ?: "") + (a.ie.maxTxPowerDbm?.let { p -> ", max $p dBm" } ?: "") })
        }
        Section("Load") {
            KV("Clients", a.ie?.stationCount?.toString() ?: "not advertised")
            KV("Channel busy", a.ie?.channelUtil?.let { "$it %" } ?: "not advertised")
            val same = Repo.wifiAps.value.count { it.inRange && it.band == a.band && kotlin.math.abs(it.freq - a.freq) < (a.widthMhz / 2 + 10) && it.bssid != a.bssid }
            KV("Overlapping APs", "$same on overlapping channels")
        }
        Section("Security") {
            KV("Summary", a.security, color = if (WifiParse.isOpen(a.security)) Red else androidx.compose.ui.graphics.Color.Unspecified)
            a.ie?.rsn?.let { r ->
                KV("Key management", r.akms.joinToString(", "))
                KV("Pairwise cipher", r.pairwise.joinToString(", ")); KV("Group cipher", r.group)
                KV("Protected mgmt frames", if (r.mfpRequired) "required (PMF)" else if (r.mfpCapable) "capable" else "off")
            }
            KV("WPS", if (a.ie?.wps == true) "enabled${a.ie.wpsState?.let { " ($it)" } ?: ""}" else "no")
            KV("Capabilities", WifiParse.capTags(a.capabilities).joinToString(" "), mono = true)
        }
        Section("Roaming & ranging") {
            KV("802.11k (neighbor reports)", yn(a.ie?.k11)); KV("802.11r (fast roaming)", yn(a.ie?.r11)); KV("802.11v (BSS transition)", yn(a.ie?.v11))
            KV("802.11mc RTT responder", yn(a.mcResponder)); KV("802.11az NTB responder", yn(a.azResponder))
            if (a.mcResponder || a.azResponder) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { scanner.rangeRtt(a) }) { Text("Measure distance (RTT)") }
                    Text(rtt[a.bssid] ?: "", fontSize = 13.sp)
                }
            }
        }
        Section("Seen") {
            KV("First seen", Fmt.dateTime(a.firstSeen)); KV("Last heard", "${Fmt.dateTime(a.lastSeen)} (${Fmt.ago(now - a.lastSeen)} ago)")
            KV("Times heard", a.seenCount.toString())
        }
        a.ie?.let { ie ->
            Section("Information elements (${ie.list.size})") {
                ie.list.forEach { e ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text("${e.label} · ${e.name}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (e.summary.isNotBlank()) Text(e.summary, fontSize = 12.sp)
                        Text(e.hex, fontSize = 10.sp, color = Muted, fontFamily = FontFamily.Monospace, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (ie.vendors.isNotEmpty()) KV("Chip/vendor hints", ie.vendors.joinToString(", "))
            }
        }
        Spacer(Modifier.height(30.dp))
    }
}

fun yn(b: Boolean?) = when (b) { true -> "yes"; false -> "no"; null -> "unknown" }

@Composable
fun ConnFields(c: ConnInfo) {
    KV("Link speed", "${c.linkMbps} Mbit/s" + (if (c.txMbps > 0) " · tx ${c.txMbps}" else "") + (if (c.rxMbps > 0) " · rx ${c.rxMbps}" else ""))
    if (c.maxTxMbps > 0) KV("Max supported", "tx ${c.maxTxMbps} · rx ${c.maxRxMbps} Mbit/s")
    KV("Security", c.security)
    KV("IPv4", c.ipv4.joinToString("\n")); KV("IPv6", c.ipv6.joinToString("\n"))
    KV("Gateway", c.gateway.joinToString(", ")); KV("DNS", c.dns.joinToString("\n")); KV("Private DNS", c.privateDns)
    KV("DHCP server", c.dhcpServer); KV("Domain", c.domains); KV("MTU", c.mtu.takeIf { it > 0 }?.toString()); KV("Interface", c.iface)
    KV("Phone MAC", c.mac, mono = true); KV("MAC randomization", c.macNote)
    KV("Bandwidth estimate", "↓ ${c.downKbps / 1000} / ↑ ${c.upKbps / 1000} Mbit/s")
    KV("Internet", if (c.validated) "validated" else "not validated"); KV("Metered", yn(c.metered))
    KV("Hidden SSID", if (c.hiddenSsid) "yes" else null); KV("Passpoint FQDN", c.passpointFqdn)
    KV("AP MLD (Wi-Fi 7)", c.mldMac); c.mloLinks.forEach { KV("MLO", it) }
}
