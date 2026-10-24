package com.victor.signalscanner

import android.content.Context
import java.util.zip.GZIPInputStream

/** Offline lookup tables (IEEE OUI registry, Bluetooth SIG company IDs and 16-bit UUIDs). */
object Lookup {
    @Volatile private var oui: Map<Int, String> = emptyMap()
    @Volatile private var companies: Map<Int, String> = emptyMap()
    @Volatile private var uuids: Map<Int, String> = emptyMap()
    @Volatile var loaded = false; private set

    /** Common names that read better than the raw registry text. */
    private val uuidOverrides = mapOf(
        0xFD6F to "Exposure Notification", 0xFE2C to "Google Fast Pair", 0xFEAA to "Eddystone (Google)",
        0xFD5A to "Samsung SmartTag", 0xFD44 to "Apple Find My", 0xFEED to "Tile", 0xFEEC to "Tile",
        0xFFF6 to "Matter commissioning", 0xFEF3 to "Google Nearby", 0xFE9F to "Google", 0xFCF1 to "Google Find My Device",
        0xFE03 to "Amazon", 0xFE07 to "Sonos", 0xFDF0 to "Google (Phone Hub)", 0x1812 to "HID over GATT (keyboard/mouse)",
    )

    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val o = HashMap<Int, String>(48000)
            runCatching {
                GZIPInputStream(ctx.assets.open("oui.dat")).bufferedReader().useLines { lines ->
                    lines.forEach { l -> val t = l.indexOf('\t'); if (t == 6) o[l.substring(0, 6).toInt(16)] = shortVendor(l.substring(7)) }
                }
            }
            oui = o
            companies = readHex(ctx, "bt_companies.txt")
            uuids = readHex(ctx, "bt_uuids.txt") + uuidOverrides
            loaded = true
        }
    }

    private fun readHex(ctx: Context, name: String): Map<Int, String> = runCatching {
        ctx.assets.open(name).bufferedReader().useLines { lines ->
            lines.mapNotNull { l -> val t = l.indexOf('\t'); if (t > 0) l.substring(0, t).toInt(16) to l.substring(t + 1) else null }.toMap()
        }
    }.getOrDefault(emptyMap())

    private val suffix = Regex("""[, ]+(co\.?,? ?ltd\.?|ltd\.?|inc\.?|corp(oration)?\.?|llc|gmbh|s\.?a\.?|b\.?v\.?|limited|co\.?|ag|oy|a/s|ab|kk|pte|plc)$""", RegexOption.IGNORE_CASE)
    private fun shortVendor(s: String): String {
        var v = s.trim()
        repeat(3) { v = v.replace(suffix, "").trim() }
        return v.ifEmpty { s }
    }

    /** Vendor for a MAC address; null for locally administered (randomized) addresses or unknown OUIs. */
    fun vendor(mac: String?): String? {
        val m = parseMac(mac) ?: return null
        if (m[0].toInt() and 0x02 != 0) return null
        return oui[((m[0].toInt() and 0xFF) shl 16) or ((m[1].toInt() and 0xFF) shl 8) or (m[2].toInt() and 0xFF)]
    }

    fun company(id: Int): String? = companies[id]
    fun uuidName(u16: Int): String? = uuids[u16]

    fun parseMac(mac: String?): ByteArray? {
        if (mac == null) return null
        val p = mac.split(':', '-')
        if (p.size != 6) return null
        return runCatching { ByteArray(6) { p[it].toInt(16).toByte() } }.getOrNull()
    }

    fun isLocallyAdministered(mac: String?): Boolean = parseMac(mac)?.let { it[0].toInt() and 0x02 != 0 } ?: false
}
