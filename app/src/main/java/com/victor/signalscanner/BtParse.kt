package com.victor.signalscanner

import java.util.UUID
import kotlin.math.pow

data class AdField(val type: Int, val name: String, val value: String, val hex: String)
data class Decoded(val title: String, val lines: List<String>)

/** Parses BLE advertising data (AD structures) and decodes well-known payloads. */
object BtParse {
    private fun Byte.u() = toInt() and 0xFF
    fun hex(b: ByteArray?): String = b?.joinToString("") { "%02X".format(it.u()) } ?: ""
    fun hexSp(b: ByteArray?): String = b?.joinToString(" ") { "%02X".format(it.u()) } ?: ""

    private val adNames = mapOf(0x01 to "Flags", 0x02 to "16-bit UUIDs (partial)", 0x03 to "16-bit UUIDs", 0x04 to "32-bit UUIDs (partial)", 0x05 to "32-bit UUIDs",
        0x06 to "128-bit UUIDs (partial)", 0x07 to "128-bit UUIDs", 0x08 to "Short name", 0x09 to "Complete name", 0x0A to "Tx power level", 0x0D to "Class of device",
        0x10 to "Device ID", 0x12 to "Connection interval range", 0x14 to "Solicited 16-bit UUIDs", 0x15 to "Solicited 128-bit UUIDs", 0x16 to "Service data (16-bit)",
        0x17 to "Public target address", 0x18 to "Random target address", 0x19 to "Appearance", 0x1A to "Advertising interval", 0x1B to "LE device address", 0x1C to "LE role",
        0x20 to "Service data (32-bit)", 0x21 to "Service data (128-bit)", 0x24 to "URI", 0x27 to "LE supported features", 0x2C to "BIG info", 0x2D to "Broadcast code",
        0x2E to "Resolvable set identifier", 0x2F to "Advertising interval (long)", 0x30 to "Broadcast name", 0x31 to "Encrypted advertising data", 0xFF to "Manufacturer data")

    fun fields(raw: ByteArray?): List<AdField> {
        if (raw == null) return emptyList()
        val out = ArrayList<AdField>()
        var o = 0
        while (o < raw.size) {
            val len = raw[o].u(); if (len == 0 || o + 1 + len > raw.size) break
            val t = raw[o + 1].u(); val v = raw.copyOfRange(o + 2, o + 1 + len)
            out += AdField(t, adNames[t] ?: "Type 0x%02X".format(t), describe(t, v), hexSp(v))
            o += 1 + len
        }
        return out
    }

    private fun describe(t: Int, v: ByteArray): String = runCatching {
        when (t) {
            0x01 -> flags(v[0].u())
            0x02, 0x03, 0x14 -> (0 until v.size / 2).joinToString(", ") { val u = v[2 * it].u() or (v[2 * it + 1].u() shl 8); uuid16Label(u) }
            0x08, 0x09, 0x30 -> String(v, Charsets.UTF_8)
            0x0A -> "${v[0].toInt()} dBm"
            0x19 -> appearance(v[0].u() or (v[1].u() shl 8))
            0x1A -> "%.1f ms".format((v[0].u() or (v[1].u() shl 8)) * 0.625)
            0x16 -> { val u = v[0].u() or (v[1].u() shl 8); "${uuid16Label(u)}: ${hexSp(v.copyOfRange(2, v.size))}" }
            0xFF -> { val id = v[0].u() or (v[1].u() shl 8); "${Lookup.company(id) ?: "Company 0x%04X".format(id)}: ${hexSp(v.copyOfRange(2, v.size))}" }
            0x24 -> String(v, 1, v.size - 1, Charsets.UTF_8)
            else -> hexSp(v)
        }
    }.getOrDefault(hexSp(v))

    fun flags(f: Int): String = listOfNotNull("LE limited discoverable".takeIf { f and 1 != 0 }, "LE general discoverable".takeIf { f and 2 != 0 },
        "BR/EDR not supported".takeIf { f and 4 != 0 }, "LE+BR/EDR (controller)".takeIf { f and 8 != 0 }, "LE+BR/EDR (host)".takeIf { f and 16 != 0 }).joinToString(", ")

    fun uuid16Label(u: Int): String = "0x%04X".format(u) + (Lookup.uuidName(u)?.let { " $it" } ?: "")

    private const val BASE_LO = -0x7fffff7fa064cb05L // 0x800000805F9B34FB
    fun uuid16Of(u: UUID): Int? = if (u.leastSignificantBits == BASE_LO && (u.mostSignificantBits and 0xFFFFFFFFL) == 0x1000L && (u.mostSignificantBits ushr 48) == 0L)
        ((u.mostSignificantBits ushr 32) and 0xFFFF).toInt() else null

    fun uuidLabel(u: UUID): String = uuid16Of(u)?.let { uuid16Label(it) } ?: knownUuid128[u.toString().lowercase()]?.let { "$u $it" } ?: u.toString()

    private val knownUuid128 = mapOf(
        "0000fe2c-0000-1000-8000-00805f9b34fb" to "Google Fast Pair",
        "6e400001-b5a3-f393-e0a9-e50e24dcca9e" to "(Nordic UART)",
        "0000fd44-0000-1000-8000-00805f9b34fb" to "(Apple Find My)",
        "d0611e78-bbb4-4591-a5f8-487910ae4366" to "(Apple Continuity)",
        "9fa480e0-4967-4542-9390-d343dc5d04ae" to "(Apple Nearby)",
    )

    fun appearance(a: Int): String {
        val cat = a shr 6
        val n = mapOf(0 to "Unknown", 1 to "Phone", 2 to "Computer", 3 to "Watch", 4 to "Clock", 5 to "Display", 6 to "Remote control", 7 to "Eyeglasses",
            8 to "Tag", 9 to "Keyring", 10 to "Media player", 11 to "Barcode scanner", 12 to "Thermometer", 13 to "Heart rate sensor", 14 to "Blood pressure",
            15 to "HID", 16 to "Glucose meter", 17 to "Running/walking sensor", 18 to "Cycling", 19 to "Control device", 20 to "Network device", 21 to "Sensor",
            22 to "Light fixture", 23 to "Fan", 24 to "HVAC", 25 to "Air conditioning", 26 to "Humidifier", 27 to "Heating", 28 to "Access control",
            29 to "Motorized device", 30 to "Power device", 31 to "Light source", 32 to "Window covering", 33 to "Audio sink", 34 to "Audio source",
            35 to "Motorized vehicle", 36 to "Domestic appliance", 37 to "Wearable audio device", 38 to "Aircraft", 39 to "AV equipment", 40 to "Display equipment",
            41 to "Hearing aid", 42 to "Gaming", 43 to "Signage", 49 to "Pulse oximeter", 50 to "Weight scale", 51 to "Personal mobility", 52 to "Glucose monitor",
            53 to "Insulin pump", 54 to "Medication delivery", 55 to "Spirometer", 81 to "Outdoor sports")
        val sub = when (a) { 0x03C1 -> " (keyboard)"; 0x03C2 -> " (mouse)"; 0x03C3 -> " (joystick)"; 0x03C4 -> " (gamepad)"; 0x0941 -> " (earbud)"; 0x0942 -> " (headset)"; 0x0943 -> " (headphones)"; 0x00C1 -> " (sports watch)"; 0x0080 -> ""; else -> "" }
        return (n[cat] ?: "Category $cat") + sub + " (0x%04X)".format(a)
    }

    // --- manufacturer / service data decoders --------------------------------------------------

    private val appleTypes = mapOf(0x01 to "Apple overflow area", 0x02 to "iBeacon", 0x03 to "AirPrint", 0x05 to "AirDrop", 0x06 to "HomeKit", 0x07 to "Proximity pairing (AirPods/Beats)",
        0x08 to "Hey Siri", 0x09 to "AirPlay target", 0x0A to "AirPlay source", 0x0B to "Magic Switch (Watch)", 0x0C to "Handoff", 0x0D to "Tethering target",
        0x0E to "Tethering source", 0x0F to "Nearby Action", 0x10 to "Nearby Info", 0x12 to "Find My", 0x16 to "Unknown (0x16)")
    private val airpodsModels = mapOf(0x0220 to "AirPods", 0x0F20 to "AirPods (2nd gen)", 0x1320 to "AirPods (3rd gen)", 0x0E20 to "AirPods Pro", 0x1420 to "AirPods Pro (2nd gen)",
        0x2420 to "AirPods Pro 2 (USB-C)", 0x0A20 to "AirPods Max", 0x1F20 to "AirPods Max (USB-C)", 0x1920 to "AirPods 4", 0x1B20 to "AirPods 4 ANC", 0x0320 to "Powerbeats3",
        0x0B20 to "Powerbeats Pro", 0x0C20 to "Beats Solo Pro", 0x1120 to "Beats Studio Buds", 0x1020 to "Beats Flex", 0x0520 to "BeatsX", 0x0620 to "Beats Solo3",
        0x0920 to "Beats Studio3", 0x1720 to "Beats Studio Pro", 0x1220 to "Beats Fit Pro", 0x1620 to "Beats Studio Buds+")
    private val msDeviceTypes = mapOf(1 to "Xbox One", 6 to "Apple iPhone", 7 to "Apple iPad", 8 to "Android device", 9 to "Windows 10/11 desktop", 11 to "Windows 10 phone",
        12 to "Linux device", 13 to "Windows IoT", 14 to "Surface Hub", 15 to "Windows laptop", 16 to "Windows tablet")

    fun decodeManufacturer(id: Int, d: ByteArray): Decoded? = runCatching {
        when (id) {
            0x004C -> decodeApple(d)
            0x0006 -> {
                if (d.isNotEmpty() && d[0].u() == 0x01 && d.size >= 2) {
                    val dt = d[1].u() and 0x1F
                    Decoded("Microsoft CDP beacon", listOf("Device: ${msDeviceTypes[dt] ?: "type $dt"}", "Shows a Windows/Xbox/phone-link device nearby (Connected Devices Platform)"))
                } else if (d.isNotEmpty() && d[0].u() == 0x03) Decoded("Microsoft Swift Pair", listOf("Device is in pairing mode and offers Swift Pair on Windows" + if (d.size > 3) ", name: " + String(d, 3, d.size - 3, Charsets.UTF_8).trim('\u0000') else ""))
                else Decoded("Microsoft", listOf(hexSp(d)))
            }
            0x0075 -> Decoded("Samsung", listOf(when {
                d.size >= 2 && d[0].u() == 0x42 && d[1].u() == 0x04 -> "Samsung Galaxy device / Smart View advertisement"
                d.size >= 1 && d[0].u() == 0x01 -> "Samsung Galaxy Buds / wearable"
                else -> "Samsung proprietary data (${d.size} bytes)"
            }))
            0x00E0 -> Decoded("Google", listOf("Google proprietary data (${d.size} bytes)"))
            0x0059 -> Decoded("Nordic Semiconductor", listOf("Usually a custom/DIY device or sensor"))
            0x0499 -> {
                if (d.isNotEmpty() && (d[0].u() == 3 || d[0].u() == 5) && d.size >= 14) {
                    val fmt = d[0].u()
                    if (fmt == 5) {
                        val temp = ((d[1].toInt() shl 8) or d[2].u()) * 0.005; val hum = (((d[3].u() shl 8) or d[4].u()) * 0.0025)
                        val pres = (((d[5].u() shl 8) or d[6].u()) + 50000) / 100.0
                        Decoded("RuuviTag", listOf("Temperature %.2f °C".format(temp), "Humidity %.1f %%".format(hum), "Pressure %.1f hPa".format(pres)))
                    } else Decoded("RuuviTag", listOf("Data format 3"))
                } else Decoded("Ruuvi", listOf(hexSp(d)))
            }
            else -> null
        }
    }.getOrNull()

    private fun decodeApple(d: ByteArray): Decoded {
        val lines = ArrayList<String>(); var title = "Apple Continuity"
        var o = 0
        while (o + 2 <= d.size) {
            val t = d[o].u()
            if (t == 0x01) { lines += "Overflow area (iOS app advertising in the background)"; break }  // no length byte; rest is a bitmask
            val l = minOf(d[o + 1].u(), d.size - o - 2)
            if (t == 0 && l == 0) break
            val v = d.copyOfRange(o + 2, o + 2 + l)
            val tn = appleTypes[t] ?: "type 0x%02X".format(t)
            when (t) {
                0x02 -> if (l >= 21) {
                    title = "iBeacon"
                    val u = UUID(java.nio.ByteBuffer.wrap(v, 0, 8).long, java.nio.ByteBuffer.wrap(v, 8, 8).long)
                    lines += "iBeacon UUID $u"; lines += "Major ${((v[16].u() shl 8) or v[17].u())}, Minor ${((v[18].u() shl 8) or v[19].u())}"; lines += "Measured power @1 m: ${v[20].toInt()} dBm"
                }
                0x12 -> {
                    title = if (l >= 25) "Find My (AirTag / separated Apple device)" else "Find My (owner nearby)"
                    val st = v.getOrNull(0)?.u() ?: 0
                    val batt = when ((st shr 6) and 3) { 0 -> "full"; 1 -> "medium"; 2 -> "low"; else -> "critically low" }
                    lines += "Find My: ${if (l >= 25) "separated from owner (offline-finding key broadcast)" else "near its owner"}, battery $batt"
                    if (l >= 25) lines += "Rotating public-key fragment (changes every ~15 min)"
                }
                0x07 -> if (v.isNotEmpty() && v[0].u() != 0x01) {
                    title = "AirPods / Beats"
                    lines += "Proximity pairing (newer encrypted format – model/battery not readable)"
                } else {
                    title = "AirPods / Beats"
                    if (l >= 3) { val m = (v[1].u() shl 8) or v[2].u(); lines += "Model: ${airpodsModels[m] ?: "0x%04X".format(m)}" }
                    if (l >= 7) { val b1 = v[5].u(); val b2 = v[6].u()
                        fun lvl(x: Int) = if (x == 15) "–" else "${x * 10}%"
                        lines += "Battery L ${lvl(b1 shr 4)} R ${lvl(b1 and 15)} case ${lvl(b2 and 15)}"
                        lines += "Charging: ${listOfNotNull("L".takeIf { b2 and 0x10 != 0 }, "R".takeIf { b2 and 0x20 != 0 }, "case".takeIf { b2 and 0x40 != 0 }).ifEmpty { listOf("no") }.joinToString("+")}" }
                }
                0x10 -> {
                    if (title == "Apple Continuity") title = "Apple device (Nearby Info)"
                    if (l >= 1) {
                        val act = v[0].u() and 0x0F
                        val acts = mapOf(0 to "activity unknown", 1 to "reporting disabled", 3 to "idle", 5 to "playing audio", 7 to "screen on / locked", 9 to "video", 10 to "on a call (watch)", 11 to "on a call", 13 to "driving", 14 to "on a call", 15 to "video/call")
                        lines += "Nearby Info: ${acts[act] ?: "action $act"}${if (l >= 2 && v[1].u() and 0x10 != 0) ", Wi-Fi on" else ""}"
                    }
                }
                0x0F -> lines += "Nearby Action (type 0x%02X) – e.g. setup/Wi-Fi password sharing".format(v.getOrNull(1)?.u() ?: 0)
                0x0C -> lines += "Handoff (continuity of an app between devices)"
                0x09 -> lines += "AirPlay target (Apple TV/HomePod/Mac)"
                0x05 -> lines += "AirDrop is open/searching"
                0x06 -> lines += "HomeKit accessory"
                0x0B -> lines += "Apple Watch (Magic Switch)"
                0x0D -> lines += "Instant Hotspot available"
                else -> lines += "$tn: ${hexSp(v)}"
            }
            o += 2 + l
        }
        return Decoded(title, lines.ifEmpty { listOf(hexSp(d)) })
    }

    fun decodeService(u16: Int, d: ByteArray): Decoded? = runCatching {
        when (u16) {
            0xFEAA -> when (d[0].u()) {
                0x00 -> Decoded("Eddystone-UID", listOf("Tx @0 m ${d[1].toInt()} dBm", "Namespace ${hex(d.copyOfRange(2, 12))}", "Instance ${hex(d.copyOfRange(12, 18))}"))
                0x10 -> {
                    val schemes = listOf("http://www.", "https://www.", "http://", "https://")
                    val exp = listOf(".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/", ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov")
                    val sb = StringBuilder(schemes.getOrElse(d[2].u()) { "" })
                    for (i in 3 until d.size) { val c = d[i].u(); if (c < exp.size) sb.append(exp[c]) else sb.append(c.toChar()) }
                    Decoded("Eddystone-URL", listOf("URL $sb", "Tx @0 m ${d[1].toInt()} dBm"))
                }
                0x20 -> Decoded("Eddystone-TLM", listOf("Battery ${(d[2].u() shl 8) or d[3].u()} mV", "Temperature %.1f °C".format(d[4].toInt() + d[5].u() / 256.0),
                    "Adv count ${java.nio.ByteBuffer.wrap(d, 6, 4).int.toLong() and 0xFFFFFFFFL}", "Uptime ${(java.nio.ByteBuffer.wrap(d, 10, 4).int.toLong() and 0xFFFFFFFFL) / 10} s"))
                0x30 -> Decoded("Eddystone-EID", listOf("Ephemeral ID ${hex(d.copyOfRange(2, minOf(10, d.size)))}"))
                else -> Decoded("Eddystone", listOf(hexSp(d)))
            }
            0xFE2C -> Decoded("Google Fast Pair", listOf(if (d.size == 3) "Model ID 0x${hex(d)} (device is in pairing mode)" else "Account-key filter (paired device advertising to its owner's phones)"))
            0xFD6F -> Decoded("Exposure Notification", listOf("Rolling proximity ID ${hex(d.copyOfRange(0, minOf(16, d.size)))}"))
            0xFD5A -> Decoded("Samsung SmartTag / SmartThings Find", listOf("Offline-finding advertisement (${d.size} bytes)"))
            0xFEED, 0xFEEC -> Decoded("Tile tracker", listOf("Tile advertisement"))
            0xFCF1 -> Decoded("Google Find My Device network", listOf("Tracker/phone advertising for Find My Device (${d.size} bytes)"))
            0xFFF6 -> Decoded("Matter commissioning", listOf("Discriminator ${(d.getOrNull(1)?.u() ?: 0) or (((d.getOrNull(2)?.u() ?: 0) and 0x0F) shl 8)}",
                "Vendor ID 0x%04X, product 0x%04X".format(d[3].u() or (d[4].u() shl 8), d[5].u() or (d[6].u() shl 8))))
            0xFEF3 -> Decoded("Google Nearby / Quick Share", listOf("${d.size} bytes"))
            0x181A -> Decoded("Environmental sensing", listOf(hexSp(d)))
            0x180F -> Decoded("Battery", listOf("${d[0].u()} %"))
            else -> null
        }
    }.getOrNull()

    // --- Bluetooth classic Class of Device ------------------------------------------------------
    private val majors = mapOf(0x0000 to "Miscellaneous", 0x0100 to "Computer", 0x0200 to "Phone", 0x0300 to "Networking", 0x0400 to "Audio/Video",
        0x0500 to "Peripheral", 0x0600 to "Imaging", 0x0700 to "Wearable", 0x0800 to "Toy", 0x0900 to "Health", 0x1F00 to "Uncategorized")
    private val minors = mapOf(0x0104 to "Desktop", 0x0108 to "Server", 0x010C to "Laptop", 0x0110 to "Handheld PC/PDA", 0x0114 to "Palm-size PC", 0x0118 to "Wearable computer",
        0x011C to "Tablet", 0x0204 to "Cellular phone", 0x0208 to "Cordless phone", 0x020C to "Smartphone", 0x0210 to "Modem/gateway", 0x0404 to "Headset", 0x0408 to "Hands-free",
        0x0410 to "Microphone", 0x0414 to "Loudspeaker", 0x0418 to "Headphones", 0x041C to "Portable audio", 0x0420 to "Car audio", 0x0424 to "Set-top box", 0x0428 to "Hi-Fi audio",
        0x042C to "VCR", 0x0430 to "Video camera", 0x0434 to "Camcorder", 0x0438 to "Video monitor", 0x043C to "Display + loudspeaker", 0x0440 to "Video conferencing",
        0x0448 to "Gaming/toy", 0x0540 to "Keyboard", 0x0580 to "Mouse", 0x05C0 to "Keyboard + mouse", 0x0504 to "Joystick", 0x0508 to "Gamepad", 0x050C to "Remote control",
        0x0704 to "Wristwatch", 0x0708 to "Pager", 0x070C to "Jacket", 0x0710 to "Helmet", 0x0714 to "Glasses")

    fun classOfDevice(cod: Int): String {
        val major = cod and 0x1F00
        val minor = cod and 0x1FFC
        val m = majors[major] ?: "Major 0x%04X".format(major)
        val mi = minors[minor] ?: minors[(cod and 0x1F00) or (cod and 0xC0)]
        val svc = listOfNotNull("positioning".takeIf { cod and 0x10000 != 0 }, "networking".takeIf { cod and 0x20000 != 0 }, "rendering".takeIf { cod and 0x40000 != 0 },
            "capturing".takeIf { cod and 0x80000 != 0 }, "object transfer".takeIf { cod and 0x100000 != 0 }, "audio".takeIf { cod and 0x200000 != 0 },
            "telephony".takeIf { cod and 0x400000 != 0 }, "information".takeIf { cod and 0x800000 != 0 }, "LE audio".takeIf { cod and 0x4000 != 0 })
        return "$m${mi?.let { " · $it" } ?: ""}" + (if (svc.isNotEmpty()) " (services: ${svc.joinToString(", ")})" else "") + " [0x%06X]".format(cod)
    }

    fun majorShort(cod: Int?): String? {
        if (cod == null) return null
        return minors[cod and 0x1FFC] ?: minors[(cod and 0x1F00) or (cod and 0xC0)] ?: majors[cod and 0x1F00]?.takeIf { it != "Uncategorized" }
    }

    /** Address type from the top bits of a random address. */
    fun addressKind(addr: String, apiType: Int?): String {
        val first = addr.take(2).toIntOrNull(16) ?: return "?"
        return when (apiType) {
            0 -> "Public (fixed, from the maker)"
            1 -> when (first shr 6) { 3 -> "Random static"; 1 -> "Random resolvable private (rotates)"; 0 -> "Random non-resolvable (rotates)"; else -> "Random" }
            else -> "Public or random (Android didn't say)"
        }
    }

    /** Distance from RSSI with a log-distance model. measuredAt1m in dBm; n = path-loss exponent. */
    fun distanceM(rssi: Int, measuredAt1m: Int, n: Double = 2.2): Double = 10.0.pow((measuredAt1m - rssi) / (10.0 * n))
}
