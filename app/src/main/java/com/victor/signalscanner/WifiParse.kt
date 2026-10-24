package com.victor.signalscanner

import kotlin.math.log10
import kotlin.math.pow

data class Ie(val id: Int, val ext: Int?, val name: String, val summary: String, val hex: String) {
    val label get() = if (ext != null) "255/$ext" else "$id"
}

/** Everything decoded from an AP's beacon/probe-response information elements. */
class IeInfo {
    val list = ArrayList<Ie>()
    var rates = ArrayList<Double>()
    var dsChannel: Int? = null
    var dtim: Int? = null
    var country: String? = null
    var countryEnv: String? = null
    var maxTxPowerDbm: Int? = null
    var stationCount: Int? = null
    var channelUtil: Int? = null          // percent
    var htStreams = 0; var vhtStreams = 0; var heStreams = 0; var ehtPresent = false
    var ht = false; var vht = false; var he = false
    var htWidth40 = false
    var bssColor: Int? = null
    var rsn: Rsn? = null
    var wpa: Rsn? = null
    var wps = false; var wpsState: String? = null
    var wpsDevice = LinkedHashMap<String, String>()
    var wmm = false
    var k11 = false; var r11 = false; var v11 = false
    var ftmResponder = false
    var interworking = false; var hs20 = false; var oweTransition = false
    var mldMac: String? = null
    var apName: String? = null
    var vendors = LinkedHashSet<String>()
    var heOp6g: Triple<Int, Int, Int>? = null // primary channel, width MHz, center channel
    var ehtWidth: Int? = null
    var vhtWidth: Int? = null
    var vhtCenter0: Int? = null
    var reducedNeighbors = 0

    val streams get() = maxOf(htStreams, vhtStreams, heStreams)
}

data class Rsn(val group: String, val pairwise: List<String>, val akms: List<String>, val akmIds: List<Int>, val mfpRequired: Boolean, val mfpCapable: Boolean)

object WifiParse {

    fun hex(b: ByteArray, max: Int = 512): String =
        b.take(max).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } + if (b.size > max) " …" else ""

    fun channelOf(freq: Int): Int = when {
        freq == 2484 -> 14
        freq in 2412..2472 -> (freq - 2407) / 5
        freq in 5160..5895 -> (freq - 5000) / 5
        freq == 5935 -> 2
        freq in 5955..7115 -> (freq - 5950) / 5
        freq in 58320..70200 -> (freq - 56160) / 2160
        freq in 4910..4980 -> (freq - 4000) / 5
        else -> 0
    }

    fun band(freq: Int): String = when {
        freq < 2500 -> "2.4 GHz"
        freq < 5925 -> "5 GHz"
        freq < 7200 -> "6 GHz"
        freq > 50000 -> "60 GHz"
        else -> "?"
    }

    fun widthMhz(cw: Int): Int = when (cw) { 0 -> 20; 1 -> 40; 2 -> 80; 3 -> 160; 4 -> 160; 5 -> 320; else -> 20 }
    fun widthName(cw: Int): String = when (cw) { 4 -> "80+80 MHz"; else -> "${widthMhz(cw)} MHz" }

    fun standardName(std: Int, freq: Int): String = when (std) {
        1 -> if (freq < 2500) "802.11b/g (legacy)" else "802.11a (legacy)"
        4 -> "Wi-Fi 4 (802.11n)"
        5 -> "Wi-Fi 5 (802.11ac)"
        6 -> if (freq >= 5925) "Wi-Fi 6E (802.11ax)" else "Wi-Fi 6 (802.11ax)"
        7 -> "WiGig (802.11ad)"
        8 -> "Wi-Fi 7 (802.11be)"
        else -> "Unknown"
    }

    fun shortStandard(std: Int, freq: Int): String = when (std) {
        1 -> "Legacy"; 4 -> "Wi-Fi 4"; 5 -> "Wi-Fi 5"; 6 -> if (freq >= 5925) "Wi-Fi 6E" else "Wi-Fi 6"; 7 -> "11ad"; 8 -> "Wi-Fi 7"; else -> "?"
    }

    /** Free-space path-loss distance estimate in metres (very rough indoors). */
    fun distanceM(rssi: Int, freq: Int): Double = 10.0.pow((27.55 - 20 * log10(freq.toDouble()) + kotlin.math.abs(rssi)) / 20.0)

    fun bars(rssi: Int): Int = when {
        rssi >= -55 -> 4; rssi >= -67 -> 3; rssi >= -75 -> 2; rssi >= -85 -> 1; else -> 0
    }

    fun quality(rssi: Int): String = when {
        rssi >= -50 -> "Excellent"; rssi >= -60 -> "Very good"; rssi >= -67 -> "Good"; rssi >= -75 -> "Fair"; rssi >= -85 -> "Weak"; else -> "Very weak"
    }

    /** Theoretical max PHY rate (Mbit/s) from standard, width and spatial streams. */
    fun maxRate(std: Int, width: Int, streams: Int): Double? {
        val ss = streams.coerceAtLeast(1)
        val per = when (std) {
            4 -> if (width >= 40) 150.0 else 72.2
            5 -> when { width >= 160 -> 866.7; width >= 80 -> 433.3; width >= 40 -> 200.0; else -> 86.7 }
            6 -> when { width >= 160 -> 1201.0; width >= 80 -> 600.5; width >= 40 -> 286.8; else -> 143.4 }
            8 -> when { width >= 320 -> 2882.0; width >= 160 -> 1441.0; width >= 80 -> 720.6; width >= 40 -> 344.1; else -> 172.1 }
            1 -> 54.0
            else -> return null
        }
        return per * ss
    }

    private val cipherNames = mapOf(1 to "WEP-40", 2 to "TKIP", 4 to "CCMP-128", 5 to "WEP-104", 6 to "BIP-CMAC-128", 8 to "GCMP-128", 9 to "GCMP-256", 10 to "CCMP-256", 11 to "BIP-GMAC-128", 12 to "BIP-GMAC-256", 13 to "BIP-CMAC-256")
    private val akmNames = mapOf(1 to "802.1X", 2 to "PSK", 3 to "FT-802.1X", 4 to "FT-PSK", 5 to "802.1X-SHA256", 6 to "PSK-SHA256", 8 to "SAE", 9 to "FT-SAE",
        11 to "Suite-B", 12 to "Suite-B-192", 13 to "FT-802.1X-SHA384", 14 to "FILS-SHA256", 15 to "FILS-SHA384", 18 to "OWE", 19 to "FT-PSK-SHA384", 20 to "PSK-SHA384", 24 to "SAE-EXT-KEY", 25 to "FT-SAE-EXT-KEY")

    private fun suite(b: ByteArray, o: Int, names: Map<Int, String>): Pair<String, Int> {
        val oui = (b[o].u() shl 16) or (b[o + 1].u() shl 8) or b[o + 2].u()
        val t = b[o + 3].u()
        return if (oui == 0x000FAC || oui == 0x0050F2) (names[t] ?: "type $t") to t else ("%06X:%d".format(oui, t)) to (1000 + t)
    }

    private fun parseRsn(b: ByteArray, start: Int): Rsn? = runCatching {
        var o = start + 2 // version
        val grp = if (o + 4 <= b.size) suite(b, o, cipherNames).first.also { o += 4 } else "CCMP-128"
        val pw = ArrayList<String>(); val akm = ArrayList<String>(); val akmIds = ArrayList<Int>()
        if (o + 2 <= b.size) {
            val n = le16(b, o); o += 2
            repeat(n) { if (o + 4 <= b.size) { pw += suite(b, o, cipherNames).first; o += 4 } }
        }
        if (o + 2 <= b.size) {
            val n = le16(b, o); o += 2
            repeat(n) { if (o + 4 <= b.size) { val s = suite(b, o, akmNames); akm += s.first; akmIds += s.second; o += 4 } }
        }
        var req = false; var cap = false
        if (o + 2 <= b.size) { val c = le16(b, o); req = c and 0x40 != 0; cap = c and 0x80 != 0 }
        Rsn(grp, pw, akm, akmIds, req, cap)
    }.getOrNull()

    private fun Byte.u() = toInt() and 0xFF
    private fun le16(b: ByteArray, o: Int) = b[o].u() or (b[o + 1].u() shl 8)
    private fun be16(b: ByteArray, o: Int) = (b[o].u() shl 8) or b[o + 1].u()
    private fun streamsFromMap(map: Int, n: Int = 8): Int { var s = 0; for (i in 0 until n) if ((map shr (2 * i)) and 3 != 3) s = i + 1; return s }

    private val ieNames = mapOf(0 to "SSID", 1 to "Supported rates", 3 to "DS parameter (channel)", 5 to "TIM (DTIM)", 7 to "Country",
        11 to "BSS Load", 32 to "Power constraint", 35 to "TPC report", 42 to "ERP info", 45 to "HT capabilities (802.11n)", 51 to "AP channel report", 52 to "Neighbor report", 56 to "Timeout interval", 62 to "Secondary channel offset", 67 to "BSS available admission capacity", 72 to "20/40 BSS coexistence", 90 to "BSS max idle period", 159 to "Secondary channel offset", 48 to "RSN (WPA2/WPA3)",
        50 to "Extended rates", 54 to "Mobility domain (802.11r)", 59 to "Supported operating classes", 61 to "HT operation", 70 to "RM capabilities (802.11k)",
        74 to "Overlapping BSS scan params", 107 to "Interworking", 108 to "Advertisement protocol", 111 to "Roaming consortium", 113 to "Mesh configuration",
        114 to "Mesh ID", 127 to "Extended capabilities", 133 to "Cisco CCX (AP name)", 191 to "VHT capabilities (802.11ac)", 192 to "VHT operation", 195 to "Transmit power envelope",
        201 to "Reduced neighbor report", 221 to "Vendor specific", 244 to "RSN extension")
    private val extNames = mapOf(35 to "HE capabilities (802.11ax)", 36 to "HE operation", 38 to "MU EDCA parameters", 39 to "Spatial reuse", 59 to "HE 6 GHz band caps",
        106 to "EHT operation (802.11be)", 107 to "Multi-link (MLO)", 108 to "EHT capabilities", 55 to "Short SSID list")

    private val vendorOuis = mapOf(0x0050F2 to "Microsoft/WFA", 0x506F9A to "Wi-Fi Alliance", 0x001018 to "Broadcom", 0x000C43 to "MediaTek/Ralink", 0x00037F to "Qualcomm Atheros",
        0x8CFDF0 to "Qualcomm", 0x0017F2 to "Apple", 0x00904C to "Epigram (Broadcom)", 0x00E04C to "Realtek", 0x001392 to "Ruckus", 0x000B86 to "Aruba", 0x004096 to "Cisco",
        0x000FAC to "IEEE 802.11", 0x000CE7 to "MediaTek", 0x0C4327 to "MediaTek", 0x001A11 to "Google", 0x0050F3 to "Microsoft", 0x0024E2 to "Hon Hai", 0x00A0C6 to "Qualcomm", 0x001374 to "Atheros", 0x00146C to "Netgear", 0xF4F5E8 to "Google", 0x18FE34 to "Espressif", 0x0050C2 to "IEEE", 0x00156D to "Ubiquiti", 0x0026BB to "Apple", 0x000393 to "Apple", 0x2CF0A2 to "Apple")

    private val wpsAttrs = mapOf(0x1011 to "Device name", 0x1021 to "Manufacturer", 0x1023 to "Model name", 0x1024 to "Model number", 0x1042 to "Serial number", 0x1047 to "UUID", 0x1054 to "Primary device type")

    /** Parse raw elements (id, extId, body). */
    fun parseIes(items: List<Triple<Int, Int, ByteArray>>): IeInfo {
        val r = IeInfo()
        for ((id, extId, b) in items) {
            var name = ieNames[id] ?: "Element $id"
            var ext: Int? = null
            var sum = ""
            runCatching {
                when (id) {
                    0 -> sum = if (b.isEmpty() || b.all { it.toInt() == 0 }) "(hidden)" else String(b, Charsets.UTF_8)
                    1, 50 -> {
                        val rs = b.map { (it.u() and 0x7F) / 2.0 }; r.rates.addAll(rs)
                        sum = b.joinToString(", ") { val v = (it.u() and 0x7F) / 2.0; (if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()) + if (it.u() and 0x80 != 0) "*" else "" } + " Mbit/s (* = basic)"
                    }
                    3 -> { r.dsChannel = b[0].u(); sum = "channel ${b[0].u()}" }
                    5 -> { r.dtim = b[1].u(); sum = "DTIM count ${b[0].u()}, period ${b[1].u()}" }
                    7 -> {
                        val cc = String(b, 0, 2, Charsets.US_ASCII); r.country = cc
                        r.countryEnv = when (b[2].u().toChar()) { 'I' -> "indoor"; 'O' -> "outdoor"; ' ' -> "any"; else -> null }
                        var o = 3; val parts = ArrayList<String>()
                        while (o + 3 <= b.size) {
                            val f = b[o].u(); val n = b[o + 1].u(); val p = b[o + 2].u()
                            if (f >= 201) parts += "op-ext $f/$n" else if (f > 0) { parts += "ch $f${if (n > 1) "–${f + (n - 1) * (if (f > 14) 4 else 1)}" else ""}: ${if (p in 1..60) "$p dBm" else "power n/a"}"; if (p in 1..60) r.maxTxPowerDbm = maxOf(r.maxTxPowerDbm ?: 0, p) }
                            o += 3
                        }
                        sum = "$cc${r.countryEnv?.let { " ($it)" } ?: ""}  " + parts.joinToString("; ")
                    }
                    11 -> { r.stationCount = le16(b, 0); r.channelUtil = (b[2].u() * 100 + 127) / 255; sum = "${r.stationCount} clients, channel ${r.channelUtil}% busy, admission cap ${le16(b, 3) * 32} µs/s" }
                    32 -> sum = "local power constraint ${b[0].u()} dB"
                    35 -> sum = "tx power ${b[0].toInt()} dBm (as reported), link margin ${b[1].toInt()} dB"
                    51 -> sum = "operating class ${b[0].u()}, channels ${b.drop(1).joinToString(", ") { it.u().toString() }}"
                    42 -> sum = "non-ERP present=${b[0].u() and 1}, protection=${(b[0].u() shr 1) and 1}, barker preamble=${(b[0].u() shr 2) and 1}"
                    45 -> {
                        r.ht = true; val cap = le16(b, 0); r.htWidth40 = cap and 2 != 0
                        r.htStreams = (0 until 4).count { b[3 + it].u() != 0 }
                        sum = "${r.htStreams} stream(s), 40 MHz ${if (r.htWidth40) "yes" else "no"}, SGI20 ${if (cap and 0x20 != 0) "yes" else "no"}, SGI40 ${if (cap and 0x40 != 0) "yes" else "no"}, LDPC ${if (cap and 1 != 0) "yes" else "no"}"
                    }
                    48 -> { r.rsn = parseRsn(b, 0); sum = r.rsn?.let { "group ${it.group}; pairwise ${it.pairwise.joinToString("/")}; AKM ${it.akms.joinToString("/")}; PMF ${if (it.mfpRequired) "required" else if (it.mfpCapable) "capable" else "off"}" } ?: "" }
                    54 -> { r.r11 = true; sum = "mobility domain %04X, FT over DS ${if (b[2].u() and 1 != 0) "yes" else "no"}".format(le16(b, 0)) }
                    61 -> { val off = b[1].u() and 3; sum = "primary ch ${b[0].u()}, secondary ${when (off) { 1 -> "above"; 3 -> "below"; else -> "none" }}, width ${if (b[1].u() and 4 != 0) "any (40)" else "20"} MHz" }
                    70 -> { r.k11 = true; sum = "neighbor report ${if (b[0].u() and 2 != 0) "yes" else "no"}, beacon measurement ${if (b[0].u() and 0x70 != 0) "yes" else "no"}" }
                    107 -> { r.interworking = true; sum = "access network type ${b[0].u() and 0x0F} (${listOf("private", "private+guest", "chargeable public", "free public", "personal", "emergency", "?", "?", "?", "?", "?", "?", "?", "?", "test", "wildcard")[b[0].u() and 0x0F]}), internet ${if (b[0].u() and 0x10 != 0) "yes" else "no"}" }
                    111 -> sum = "${b[0].u()} consortium OI(s) in ANQP"
                    114 -> sum = String(b, Charsets.UTF_8)
                    127 -> {
                        fun bit(n: Int) = n / 8 < b.size && (b[n / 8].u() shr (n % 8)) and 1 == 1
                        r.v11 = bit(19); r.ftmResponder = bit(70); if (bit(31)) r.interworking = true
                        val f = ArrayList<String>()
                        if (bit(19)) f += "BSS transition (11v)"; if (bit(31)) f += "interworking"; if (bit(70)) f += "FTM responder"; if (bit(71)) f += "FTM initiator"
                        if (bit(62)) f += "op-mode notification"; if (bit(46)) f += "WNM notification"; if (bit(77)) f += "TWT requester"; if (bit(78)) f += "TWT responder"
                        sum = if (f.isEmpty()) "${b.size} bytes" else f.joinToString(", ")
                    }
                    133 -> { if (b.size >= 26) { r.apName = String(b, 10, 16, Charsets.US_ASCII).trim('\u0000', ' '); sum = "AP name \"${r.apName}\"" } }
                    191 -> {
                        r.vht = true; val cap = b[0].u() or (b[1].u() shl 8) or (b[2].u() shl 16) or (b[3].u() shl 24)
                        r.vhtStreams = streamsFromMap(le16(b, 4))
                        val cw = (cap shr 2) and 3
                        sum = "${r.vhtStreams} stream(s), ${when (cw) { 1 -> "160 MHz"; 2 -> "160 & 80+80 MHz"; else -> "80 MHz" }}, SU beamformer ${if (cap and (1 shl 11) != 0) "yes" else "no"}, MU beamformer ${if (cap and (1 shl 19) != 0) "yes" else "no"}"
                    }
                    192 -> {
                        val w = b[0].u(); val c0 = b[1].u(); val c1 = b[2].u()
                        r.vhtWidth = when { w == 0 -> null; c1 != 0 && kotlin.math.abs(c1 - c0) == 8 -> 160; c1 != 0 -> 160; w == 2 -> 160; w == 3 -> 160; else -> 80 }
                        r.vhtCenter0 = if (c1 != 0 && kotlin.math.abs(c1 - c0) == 8) c1 else c0
                        sum = "width ${when (w) { 0 -> "20/40"; 1 -> "80/160/80+80"; 2 -> "160 (old)"; 3 -> "80+80 (old)"; else -> "?" }}, center seg0 ch $c0, seg1 ch $c1"
                    }
                    195 -> sum = "max tx power ${b.drop(1).joinToString("/") { (it.toInt() / 2.0).toString() }} dBm"
                    201 -> { r.reducedNeighbors++; sum = "co-located/neighbor APs advertised (${b.size} bytes)" }
                    221 -> if (b.size >= 3) {
                        val oui = (b[0].u() shl 16) or (b[1].u() shl 8) or b[2].u(); val t = if (b.size > 3) b[3].u() else -1
                        val vn = vendorOuis[oui] ?: Lookup.vendor("%02X:%02X:%02X:00:00:00".format(b[0].u(), b[1].u(), b[2].u())) ?: "%06X".format(oui)
                        name = "Vendor: $vn"
                        when {
                            oui == 0x0050F2 && t == 1 -> { name = "WPA (legacy)"; r.wpa = parseRsn(b, 4); sum = r.wpa?.let { "group ${it.group}; pairwise ${it.pairwise.joinToString("/")}; AKM ${it.akms.joinToString("/")}" } ?: "" }
                            oui == 0x0050F2 && t == 2 -> { name = "WMM / QoS"; r.wmm = true; sum = "WMM ${if (b.size > 4 && b[4].u() == 1) "parameters" else "info"}" }
                            oui == 0x0050F2 && t == 4 -> {
                                name = "WPS"; r.wps = true; var o = 4
                                while (o + 4 <= b.size) {
                                    val at = be16(b, o); val al = be16(b, o + 2); if (o + 4 + al > b.size) break
                                    val v = b.copyOfRange(o + 4, o + 4 + al)
                                    when (at) {
                                        0x1044 -> r.wpsState = if (v[0].u() == 2) "configured" else "not configured"
                                        0x1047 -> r.wpsDevice["UUID"] = hex(v).replace(" ", "")
                                        0x1054 -> r.wpsDevice["Device type"] = "cat ${be16(v, 0)}, sub ${be16(v, 6)}"
                                        0x1057 -> if (v[0].u() == 1) r.wpsDevice["AP setup locked"] = "yes"
                                        0x1041 -> if (v[0].u() == 1) r.wpsDevice["Selected registrar"] = "yes (WPS active now)"
                                        else -> wpsAttrs[at]?.let { k -> String(v, Charsets.UTF_8).trim('\u0000', ' ').takeIf { it.isNotBlank() }?.let { r.wpsDevice[k] = it } }
                                    }
                                    o += 4 + al
                                }
                                sum = (listOfNotNull(r.wpsState) + r.wpsDevice.map { "${it.key}: ${it.value}" }).joinToString("; ")
                            }
                            oui == 0x506F9A && t == 0x10 -> { name = "Hotspot 2.0 (Passpoint)"; r.hs20 = true; sum = "release ${((if (b.size > 4) b[4].u() else 0) shr 4) + 1}" }
                            oui == 0x506F9A && t == 0x1C -> { name = "OWE transition"; r.oweTransition = true; sum = "paired with an open/OWE network" }
                            oui == 0x506F9A && t == 0x09 -> { name = "Wi-Fi Direct (P2P)"; sum = "P2P group owner / device" }
                            oui == 0x506F9A && t == 0x1A -> { name = "Multi-AP (EasyMesh)"; sum = "EasyMesh" }
                            oui == 0x506F9A && t == 0x1B -> { name = "Multi-AP (EasyMesh)"; sum = "EasyMesh" }
                            else -> { r.vendors += vn; sum = "type $t, ${b.size} bytes" }
                        }
                    }
                    255 -> {
                        ext = extId.takeIf { it >= 0 } ?: b[0].u()
                        val e = if (extId >= 0) b else b.copyOfRange(1, b.size)
                        name = extNames[ext] ?: "Extension $ext"
                        when (ext) {
                            35 -> { r.he = true; val phy0 = e[6].u(); r.heStreams = streamsFromMap(le16(e, 17))
                                sum = "${r.heStreams} stream(s), ${listOfNotNull("40 MHz (2.4)".takeIf { phy0 and 2 != 0 }, "40/80 MHz (5/6)".takeIf { phy0 and 4 != 0 }, "160 MHz".takeIf { phy0 and 8 != 0 }, "80+80 MHz".takeIf { phy0 and 16 != 0 }).joinToString(", ")}" }
                            36 -> {
                                val p = e[0].u() or (e[1].u() shl 8) or (e[2].u() shl 16)
                                r.bssColor = e[3].u() and 0x3F
                                var o = 6
                                if (p and (1 shl 14) != 0) o += 3
                                if (p and (1 shl 15) != 0) o += 1
                                var s6 = ""
                                if (p and (1 shl 17) != 0 && o + 5 <= e.size) {
                                    val pc = e[o].u(); val w = when (e[o + 1].u() and 3) { 0 -> 20; 1 -> 40; 2 -> 80; else -> 160 }
                                    val c1 = e[o + 3].u(); val center = if (w == 160 && c1 != 0) c1 else e[o + 2].u()
                                    r.heOp6g = Triple(pc, w, center); s6 = ", 6 GHz primary ch $pc, $w MHz, center ch $center"
                                }
                                sum = "BSS color ${r.bssColor}$s6"
                            }
                            106 -> {
                                if (e[0].u() and 1 != 0 && e.size >= 8) { r.ehtWidth = when (e[5].u() and 7) { 0 -> 20; 1 -> 40; 2 -> 80; 3 -> 160; 4 -> 320; else -> null }; sum = "EHT width ${r.ehtWidth} MHz, center ch ${e[7].u().takeIf { it != 0 } ?: e[6].u()}" }
                                else sum = "EHT operating on the HT/VHT/HE channel"
                                r.ehtPresent = true
                            }
                            108 -> { r.ehtPresent = true; sum = "802.11be (Wi-Fi 7) capabilities" }
                            107 -> {
                                // Basic multi-link: control(2), common info length(1), MLD MAC(6)
                                if (e.size >= 9 && (e[0].u() and 7) == 0) { r.mldMac = (3 until 9).joinToString(":") { "%02x".format(e[it].u()) }; sum = "MLD address ${r.mldMac}" } else sum = "multi-link element"
                            }
                            else -> sum = "${e.size} bytes"
                        }
                    }
                    else -> sum = "${b.size} bytes"
                }
            }
            r.list += Ie(id, ext, name, sum, hex(b, 64))
        }
        return r
    }

    /** Parses the bracketed capability string Android reports, e.g. "[RSN-PSK+SAE-CCMP][ESS][WPS]". */
    fun capTags(caps: String): List<String> = Regex("""\[([^\]]+)]""").findAll(caps).map { it.groupValues[1] }.toList()

    /** Human readable security summary. */
    fun security(caps: String, ie: IeInfo?): String {
        val akm = (ie?.rsn?.akmIds ?: emptyList()).toSet()
        val c = caps.uppercase()
        val hasRsn = ie?.rsn != null || c.contains("RSN") || c.contains("WPA2")
        return when {
            18 in akm || c.contains("OWE") -> if (c.contains("OWE_TRANSITION") || ie?.oweTransition == true) "Enhanced Open (OWE, transition)" else "Enhanced Open (OWE)"
            12 in akm || c.contains("SUITE_B_192") || c.contains("EAP_SUITE_B") -> "WPA3-Enterprise 192-bit"
            (8 in akm || 24 in akm || c.contains("SAE")) && (2 in akm || 6 in akm || c.contains("PSK")) -> "WPA2/WPA3-Personal (transition)"
            8 in akm || 24 in akm || 9 in akm || c.contains("SAE") -> "WPA3-Personal (SAE)"
            (1 in akm || 3 in akm || 5 in akm || c.contains("EAP")) && ie?.rsn?.mfpRequired == true && 5 in akm -> "WPA3-Enterprise"
            hasRsn && (1 in akm || 3 in akm || 5 in akm || c.contains("EAP")) -> "WPA2-Enterprise (802.1X)"
            hasRsn && ie?.wpa != null -> "WPA/WPA2-Personal (mixed)"
            hasRsn -> "WPA2-Personal (PSK)"
            ie?.wpa != null || c.contains("WPA-") -> if (c.contains("EAP")) "WPA-Enterprise (legacy)" else "WPA-Personal (legacy, weak)"
            c.contains("WEP") -> "WEP (insecure)"
            else -> "Open (no encryption)"
        }
    }

    fun isOpen(sec: String) = sec.startsWith("Open")
}
