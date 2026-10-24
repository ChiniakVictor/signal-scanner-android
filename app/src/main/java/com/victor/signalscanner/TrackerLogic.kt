package com.victor.signalscanner

import kotlin.math.*

/** What kind of item tracker a BLE advertisement looks like. Pure code so it can be unit-tested. */
data class TrackerHit(
    val id: String,            // stable key for the sighting DB
    val type: String,          // "AirTag", "Samsung SmartTag", …
    val family: String,        // "apple", "samsung", "tile", "google", "chipolo", "dult"
    val separated: Boolean?,   // true = away from its owner (the case that matters), false = owner nearby, null = unknown
    val detail: String,        // human readable status
    val payload: String?,      // key bytes worth showing
)

object TrackerClassify {
    const val APPLE = 0x004C
    const val CHIPOLO_CO = 0x08C3

    private fun hex(b: ByteArray, from: Int = 0, to: Int = b.size) = (from until min(to, b.size)).joinToString("") { "%02X".format(b[it]) }

    /**
     * @param manu manufacturer id -> data (without the id bytes)
     * @param svcData 16-bit service UUID -> data
     * @param uuids16 advertised 16-bit service UUIDs
     */
    fun classify(address: String, manu: Map<Int, ByteArray>, svcData: Map<Int, ByteArray>, uuids16: Set<Int>): TrackerHit? {
        manu[APPLE]?.let { d -> findMy(address, d)?.let { return it } }
        svcData[0xFD5A]?.let { d ->
            val state = if (d.isNotEmpty()) (d[0].toInt() and 0xFF) else -1
            return TrackerHit("SS-$address", "Samsung SmartTag", "samsung", null,
                "SmartTag advertisement" + (if (state >= 0) " · state byte 0x%02X".format(state) else ""), hex(d, 0, 8))
        }
        svcData[0xFEAA]?.let { d ->
            val frame = if (d.isNotEmpty()) d[0].toInt() and 0xFF else -1
            if (frame == 0x40 || frame == 0x41)
                return TrackerHit("GF-$address", "Google Find My Device tag", "google", frame == 0x41,
                    if (frame == 0x41) "Find My Device network · unwanted-tracking mode ON (separated from owner)" else "Find My Device network · near owner / normal mode", hex(d, 1, 9))
        }
        svcData[0xFCB2]?.let { d ->
            return TrackerHit("DU-$address", "DULT tracker (cross-platform)", "dult", null, "Detecting Unwanted Location Trackers advertisement", hex(d, 0, 8))
        }
        if (uuids16.any { it == 0xFEED || it == 0xFEEC || it == 0xFD84 } || svcData.keys.any { it == 0xFEED || it == 0xFEEC || it == 0xFD84 } || manu.containsKey(0x067C))
            return TrackerHit("TL-$address", "Tile", "tile", null, "Tile tracker", svcData[0xFEED]?.let { hex(it, 0, 8) } ?: svcData[0xFEEC]?.let { hex(it, 0, 8) })
        if (uuids16.any { it == 0xFE33 || it == 0xFE65 } || svcData.keys.any { it == 0xFE33 || it == 0xFE65 } || manu.containsKey(CHIPOLO_CO))
            return TrackerHit("CP-$address", "Chipolo", "chipolo", null, "Chipolo tracker", null)
        return null
    }

    /** Apple "Offline Finding" (type 0x12). 25-byte form = separated from owner; 2-byte form = owner nearby. */
    fun findMy(address: String, d: ByteArray): TrackerHit? {
        var i = 0
        while (i + 1 < d.size) {
            val t = d[i].toInt() and 0xFF; val l = d[i + 1].toInt() and 0xFF
            if (t == 0 && l == 0) break
            if (t == 0x12) {
                val v = d.copyOfRange(min(i + 2, d.size), min(i + 2 + l, d.size))
                val status = if (v.isNotEmpty()) v[0].toInt() and 0xFF else 0
                val kind = when ((status shr 4) and 0x3) { 1 -> "AirTag"; 2 -> "Find My accessory"; 3 -> "AirPods (Find My)"; else -> "Apple device (Find My)" }
                val battery = when ((status shr 6) and 0x3) { 0 -> "full"; 1 -> "medium"; 2 -> "low"; else -> "critically low" }
                val separated = l >= 25
                return TrackerHit("FM-$address", kind, "apple", separated,
                    if (separated) "Separated from owner (full key broadcast) · battery $battery" else "Owner nearby (short form – address rotates ~15 min)",
                    if (separated) hex(v, 1, 7) else null)
            }
            if (l == 0) break
            i += 2 + l
        }
        return null
    }
}

data class Sighting(val t: Long, val lat: Double?, val lon: Double?, val acc: Float?, val rssi: Int)
data class Home(val lat: Double, val lon: Double, val radiusM: Double)
data class TrackerRule(val minPlaces: Int = 3, val placeSepM: Double = 500.0, val minMinutes: Int = 30, val minMoveM: Double = 500.0)
data class Place(val lat: Double, val lon: Double, val first: Long, val last: Long, val count: Int, val bestRssi: Int, val atHome: Boolean)
data class Verdict(
    val following: Boolean, val reason: String, val places: List<Place>, val travelledM: Double,
    val spanMs: Long, val spreadM: Double, val allAtHome: Boolean, val located: Int,
)

object TrackerRules {
    fun distM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun evaluate(raw: List<Sighting>, home: Home?, rule: TrackerRule, trusted: Boolean): Verdict {
        val s = raw.sortedBy { it.t }
        val loc = s.filter { it.lat != null && it.lon != null && (it.acc ?: 0f) <= 1000f }
        // greedy clustering: a sighting starts a new place when it is >= placeSepM from every existing place
        data class P(var lat: Double, var lon: Double, var first: Long, var last: Long, var n: Int, var best: Int)
        val ps = ArrayList<P>()
        for (x in loc) {
            val p = ps.firstOrNull { distM(it.lat, it.lon, x.lat!!, x.lon!!) < rule.placeSepM }
            if (p == null) ps += P(x.lat!!, x.lon!!, x.t, x.t, 1, x.rssi)
            else { p.last = max(p.last, x.t); p.first = min(p.first, x.t); p.n++; p.best = max(p.best, x.rssi) }
        }
        fun atHome(lat: Double, lon: Double) = home != null && distM(lat, lon, home.lat, home.lon) <= home.radiusM
        val places = ps.map { Place(it.lat, it.lon, it.first, it.last, it.n, it.best, atHome(it.lat, it.lon)) }
        var travelled = 0.0
        for (k in 1 until loc.size) {
            val a = loc[k - 1]; val b = loc[k]
            val d = distM(a.lat!!, a.lon!!, b.lat!!, b.lon!!)
            if (d > max(50.0, ((a.acc ?: 0f) + (b.acc ?: 0f)).toDouble())) travelled += d
        }
        var spread = 0.0
        for (i in places.indices) for (j in i + 1 until places.size) spread = max(spread, distM(places[i].lat, places[i].lon, places[j].lat, places[j].lon))
        // spread inside one cluster also counts (moving slowly within < placeSepM steps)
        for (i in loc.indices) if (i > 0) spread = max(spread, distM(loc[0].lat!!, loc[0].lon!!, loc[i].lat!!, loc[i].lon!!))
        val span = if (s.size > 1) s.last().t - s.first().t else 0L
        val allHome = home != null && loc.isNotEmpty() && loc.all { atHome(it.lat!!, it.lon!!) }
        val byPlaces = places.size >= rule.minPlaces
        val byTime = span >= rule.minMinutes * 60_000L && spread >= rule.minMoveM
        val following = !trusted && !allHome && (byPlaces || byTime)
        val reason = when {
            trusted -> "Trusted – never alerts"
            allHome -> "Only seen at Home – ignored"
            byPlaces -> "Seen at ${places.size} places ≥ ${rule.placeSepM.toInt()} m apart"
            byTime -> "With you for ${span / 60_000} min while you moved ${spread.toInt()} m"
            loc.isEmpty() -> "No location for these sightings yet"
            else -> "${places.size} place(s), ${span / 60_000} min, moved ${spread.toInt()} m – below alert thresholds"
        }
        return Verdict(following, reason, places, travelled, span, spread, allHome, loc.size)
    }
}
