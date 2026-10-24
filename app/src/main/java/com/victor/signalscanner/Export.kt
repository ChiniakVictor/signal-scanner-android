package com.victor.signalscanner

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes CSV/JSON snapshots to Downloads/SignalScanner (on-device only). */
object Export {
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun iso(t: Long) = if (t <= 0) "" else SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(t))
    private fun csv(vararg v: Any?) = v.joinToString(",") { x ->
        val s = x?.toString() ?: ""
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun wifiCsv(aps: List<WifiAp>): String = buildString {
        appendLine(csv("ssid", "bssid", "vendor", "rssi_dbm", "frequency_mhz", "band", "channel", "width_mhz", "center0_mhz", "center1_mhz", "standard", "security",
            "capabilities", "80211mc", "passpoint", "clients", "channel_util_pct", "country", "streams", "max_rate_mbps", "distance_m", "first_seen", "last_heard", "in_range", "connected", "rssi_history"))
        aps.forEach { a -> appendLine(csv(a.ssid, a.bssid, a.vendor, a.rssi, a.freq, a.band, a.channel, a.widthMhz, a.center0, a.center1, a.standardName, a.security,
            a.capabilities, a.mcResponder, a.passpoint, a.ie?.stationCount, a.ie?.channelUtil, a.ie?.country, a.ie?.streams, a.maxRate, "%.1f".format(a.distanceM),
            iso(a.firstSeen), iso(a.lastSeen), a.inRange, a.connected, a.history.joinToString(" ") { it.rssi.toString() })) }
    }

    fun btCsv(ds: List<BtDevice>): String = buildString {
        appendLine(csv("name", "address", "address_type", "vendor", "kind", "device_type", "rssi_dbm", "tx_power_dbm", "distance_m", "bond", "connectable", "phy",
            "adv_interval_ms", "packets", "decoded", "services", "manufacturer_data", "service_data", "raw_adv", "first_seen", "last_seen", "rssi_history"))
        ds.forEach { d -> appendLine(csv(d.name, d.address, d.addressKind, d.vendor, d.kind, d.deviceType, d.rssi, d.txPower, d.distanceM?.let { "%.1f".format(it) }, d.bond,
            d.connectable, d.phy, d.advIntervalMs, d.packets, d.tag, d.services.joinToString("; "),
            d.manufacturer.joinToString("; ") { "${it.company}: ${it.hex}" + (it.decoded?.let { x -> " (" + x.title + ": " + x.lines.joinToString(" / ") + ")" } ?: "") },
            d.serviceData.joinToString("; ") { "${it.uuid}: ${it.hex}" }, d.raw, iso(d.firstSeen), iso(d.lastSeen), d.history.joinToString(" ") { it.rssi.toString() })) }
    }

    fun nfcCsv(ts: List<NfcTap>): String = buildString {
        appendLine(csv("time", "uid", "type", "technologies", "details", "ndef"))
        ts.forEach { t -> appendLine(csv(iso(t.time), t.uid, t.guess, t.techs.joinToString(" "), t.fields.joinToString("; ") { "${it.first}=${it.second}" }, t.ndef.joinToString("; ") { "${it.first}: ${it.second}" })) }
    }

    fun cellCsv(cs: List<CellEntry>): String = buildString {
        val st = CellRepo.state.value
        appendLine(csv("tech", "role", "registered", "mcc", "mnc", "operator", "area", "cell_id", "site", "pci", "channel_type", "channel", "band", "freq_mhz", "bandwidth_mhz",
            "main_metric", "main_dbm", "quality_metric", "quality_db", "sinr_metric", "sinr_db", "rssi", "cqi", "timing_advance", "extra", "last_seen", "data_network", "nr_state", "carrier_aggregation", "history"))
        cs.forEach { c -> appendLine(csv(c.tech, c.role, c.registered, c.mcc, c.mnc, c.operator, c.area, c.cellId, c.enb, c.pci, c.arfcnName, c.arfcn, c.bands, c.freqMhz, c.bandwidthMhz,
            c.mainName, c.main, c.qName, c.q, c.sinrName, c.sinr, c.rssi, c.cqi, c.ta, c.extra.joinToString("; ") { "${it.first}=${it.second}" }, iso(c.lastSeen),
            st.dataType, st.nrState, st.carrierAggregation, c.history.joinToString(" ") { "${it.main ?: ""}/${it.q ?: ""}/${it.sinr ?: ""}" })) }
    }

    fun json(): String {
        val o = JSONObject()
        o.put("exported", iso(System.currentTimeMillis()))
        o.put("wifi", JSONArray().apply { Repo.wifiAps.value.forEach { a -> put(JSONObject().apply {
            put("ssid", a.ssid); put("bssid", a.bssid); put("vendor", a.vendor); put("rssi", a.rssi); put("freq", a.freq); put("band", a.band); put("channel", a.channel)
            put("width", a.widthMhz); put("center0", a.center0); put("center1", a.center1); put("standard", a.standardName); put("security", a.security); put("capabilities", a.capabilities)
            put("mc", a.mcResponder); put("passpoint", a.passpoint); put("distance_m", a.distanceM); put("first_seen", iso(a.firstSeen)); put("last_heard", iso(a.lastSeen)); put("connected", a.connected)
            put("history", JSONArray(a.history.map { JSONObject().put("t", iso(it.t)).put("rssi", it.rssi) }))
            put("ies", JSONArray(a.ie?.list?.map { JSONObject().put("id", it.label).put("name", it.name).put("summary", it.summary).put("hex", it.hex) } ?: emptyList<JSONObject>()))
        }) } })
        Repo.conn.value?.let { c -> o.put("connected", JSONObject().apply {
            put("ssid", c.ssid); put("bssid", c.bssid); put("rssi", c.rssi); put("link_mbps", c.linkMbps); put("tx_mbps", c.txMbps); put("rx_mbps", c.rxMbps); put("freq", c.freq)
            put("ipv4", JSONArray(c.ipv4)); put("ipv6", JSONArray(c.ipv6)); put("gateway", JSONArray(c.gateway)); put("dns", JSONArray(c.dns)); put("mac_note", c.macNote)
        }) }
        o.put("bluetooth", JSONArray().apply { Repo.bt.value.forEach { d -> put(JSONObject().apply {
            put("name", d.name); put("address", d.address); put("address_type", d.addressKind); put("vendor", d.vendor); put("kind", d.kind); put("type", d.deviceType)
            put("rssi", d.rssi); put("tx_power", d.txPower); put("distance_m", d.distanceM); put("bond", d.bond); put("connectable", d.connectable); put("phy", d.phy)
            put("adv_interval_ms", d.advIntervalMs); put("decoded", d.tag); put("services", JSONArray(d.services)); put("raw", d.raw)
            put("manufacturer", JSONArray(d.manufacturer.map { JSONObject().put("id", it.id).put("company", it.company).put("hex", it.hex).put("decoded", it.decoded?.let { x -> x.title + ": " + x.lines.joinToString(" / ") }) }))
            put("service_data", JSONArray(d.serviceData.map { JSONObject().put("uuid", it.uuid).put("hex", it.hex).put("decoded", it.decoded?.let { x -> x.title + ": " + x.lines.joinToString(" / ") }) }))
            put("first_seen", iso(d.firstSeen)); put("last_seen", iso(d.lastSeen))
            put("history", JSONArray(d.history.map { JSONObject().put("t", iso(it.t)).put("rssi", it.rssi) }))
        }) } })
        o.put("cells", JSONArray().apply { CellRepo.cells.value.forEach { c -> put(JSONObject().apply {
            put("tech", c.tech); put("role", c.role); put("registered", c.registered); put("mcc", c.mcc); put("mnc", c.mnc); put("operator", c.operator); put("area", c.area)
            put("cell_id", c.cellId); put("site", c.enb); put("pci", c.pci); put(c.arfcnName.ifEmpty { "channel" }, c.arfcn); put("band", c.bands); put("freq_mhz", c.freqMhz)
            put("bandwidth_mhz", c.bandwidthMhz); put(c.mainName.ifEmpty { "signal" }, c.main); if (c.qName.isNotEmpty()) put(c.qName, c.q); if (c.sinrName.isNotEmpty()) put(c.sinrName, c.sinr)
            put("ta", c.ta); put("cqi", c.cqi); put("last_seen", iso(c.lastSeen))
        }) } })
        CellRepo.state.value.let { st -> o.put("cell_network", JSONObject().apply {
            put("operator", st.operator); put("data_network", st.dataType); put("displayed_as", st.displayType); put("voice_network", st.voiceType); put("nr_state", st.nrState)
            put("carrier_aggregation", st.carrierAggregation); put("bandwidths", st.bandwidths)
        }) }
        o.put("nfc", JSONArray().apply { Repo.taps.value.forEach { t -> put(JSONObject().apply {
            put("time", iso(t.time)); put("uid", t.uid); put("type", t.guess); put("techs", JSONArray(t.techs))
            put("details", JSONObject().apply { t.fields.forEach { put(it.first, it.second) } }); put("ndef", JSONArray(t.ndef.map { "${it.first}: ${it.second}" }))
        }) } })
        return o.toString(1)
    }

    /** Saves to Downloads/SignalScanner. Returns a user-visible path. */
    fun save(ctx: Context, baseName: String, ext: String, mime: String, content: String): String {
        val name = "${baseName}_${stamp()}.$ext"
        return if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SignalScanner")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("could not create file")
            ctx.contentResolver.openOutputStream(uri)!!.use { it.write(content.toByteArray()) }
            "Download/SignalScanner/$name"
        } else {
            val dir = File(ctx.getExternalFilesDir(null), "exports").apply { mkdirs() }
            File(dir, name).writeText(content); "${dir.absolutePath}/$name"
        }
    }
}
