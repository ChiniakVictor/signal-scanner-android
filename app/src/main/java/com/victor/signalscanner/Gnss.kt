package com.victor.signalscanner

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

data class Sat(
    val key: String, val constellation: String, val svid: Int, val cn0: Float, val elev: Float, val azim: Float,
    val used: Boolean, val almanac: Boolean, val ephemeris: Boolean, val freqMhz: Double?, val signal: String?, val basebandCn0: Float?,
)

data class GnssFix(val lat: Double, val lon: Double, val acc: Float?, val alt: Double?, val vAcc: Float?, val speed: Float?, val bearing: Float?, val time: Long, val provider: String?)

data class GnssState(
    val listening: Boolean = false, val started: Boolean = false, val ttffMs: Int? = null, val listenSince: Long = 0,
    val fix: GnssFix? = null, val error: String? = null, val gnssHw: String? = null, val capabilities: String? = null, val updates: Int = 0,
)

object GnssRepo {
    val sats = MutableStateFlow<List<Sat>>(emptyList())
    val state = MutableStateFlow(GnssState())
}

/** Listens to GnssStatus + GPS fixes. Started/stopped by the GNSS tab while it is visible and the app is in front. */
class GnssListener(private val ctx: Context) {
    private val lm = ctx.getSystemService(LocationManager::class.java)
    private var active = false

    private val statusCb = object : GnssStatus.Callback() {
        override fun onStarted() { GnssRepo.state.value = GnssRepo.state.value.copy(started = true) }
        override fun onStopped() { GnssRepo.state.value = GnssRepo.state.value.copy(started = false) }
        override fun onFirstFix(ttffMillis: Int) { GnssRepo.state.value = GnssRepo.state.value.copy(ttffMs = ttffMillis) }
        override fun onSatelliteStatusChanged(s: GnssStatus) {
            val list = ArrayList<Sat>(s.satelliteCount)
            for (i in 0 until s.satelliteCount) {
                val c = constName(s.getConstellationType(i))
                val f = if (s.hasCarrierFrequencyHz(i)) s.getCarrierFrequencyHz(i) / 1e6 else null
                val sig = f?.let { signalName(s.getConstellationType(i), it) }
                list += Sat("$c-${s.getSvid(i)}-${sig ?: ""}", c, s.getSvid(i), s.getCn0DbHz(i), s.getElevationDegrees(i), s.getAzimuthDegrees(i), s.usedInFix(i),
                    s.hasAlmanacData(i), s.hasEphemerisData(i), f, sig, if (Build.VERSION.SDK_INT >= 30 && s.hasBasebandCn0DbHz(i)) s.getBasebandCn0DbHz(i) else null)
            }
            GnssRepo.sats.value = list
            GnssRepo.state.value = GnssRepo.state.value.copy(updates = GnssRepo.state.value.updates + 1)
        }
    }

    private val locCb = LocationListener { l: Location ->
        GnssRepo.state.value = GnssRepo.state.value.copy(fix = GnssFix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else null, if (l.hasAltitude()) l.altitude else null,
            if (Build.VERSION.SDK_INT >= 26 && l.hasVerticalAccuracy()) l.verticalAccuracyMeters else null, if (l.hasSpeed()) l.speed else null, if (l.hasBearing()) l.bearing else null, l.time, l.provider))
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (active || lm == null) return
        val hw = if (Build.VERSION.SDK_INT >= 28) {
            val y = lm.gnssYearOfHardware
            listOfNotNull(lm.gnssHardwareModelName, if (y > 0) "($y)" else null).joinToString(" ")
        } else null
        val caps = if (Build.VERSION.SDK_INT >= 30) lm.gnssCapabilities.let { c ->
            listOfNotNull("measurements".takeIf { c.hasMeasurements() }, "navigation messages".takeIf { c.hasNavigationMessages() },
                "antenna info".takeIf { c.hasAntennaInfo() }).joinToString(", ")
        } else null
        val okPerm = ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!okPerm) { GnssRepo.state.value = GnssState(error = "Needs precise location permission"); return }
        if (Build.VERSION.SDK_INT >= 28 && !lm.isLocationEnabled) { GnssRepo.state.value = GnssState(error = "Location is off", gnssHw = hw); return }
        runCatching {
            lm.registerGnssStatusCallback(ContextCompat.getMainExecutor(ctx), statusCb)
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locCb, ctx.mainLooper)
            active = true
            GnssRepo.state.value = GnssRepo.state.value.copy(listening = true, listenSince = System.currentTimeMillis(), error = null, gnssHw = hw, capabilities = caps, ttffMs = null)
        }.onFailure { GnssRepo.state.value = GnssRepo.state.value.copy(error = it.message) }
    }

    fun stop() {
        if (!active || lm == null) return
        runCatching { lm.unregisterGnssStatusCallback(statusCb) }
        runCatching { lm.removeUpdates(locCb) }
        active = false
        GnssRepo.state.value = GnssRepo.state.value.copy(listening = false, started = false)
    }

    companion object {
        fun constName(t: Int) = when (t) {
            GnssStatus.CONSTELLATION_GPS -> "GPS"; GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"; GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
            GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"; GnssStatus.CONSTELLATION_QZSS -> "QZSS"; GnssStatus.CONSTELLATION_SBAS -> "SBAS"
            GnssStatus.CONSTELLATION_IRNSS -> "NavIC"; else -> "Unknown"
        }
        fun signalName(t: Int, f: Double): String = when {
            kotlin.math.abs(f - 1575.42) < 1 -> when (t) { GnssStatus.CONSTELLATION_GALILEO -> "E1"; GnssStatus.CONSTELLATION_BEIDOU -> "B1C"; else -> "L1" }
            kotlin.math.abs(f - 1176.45) < 1 -> when (t) { GnssStatus.CONSTELLATION_GALILEO -> "E5a"; GnssStatus.CONSTELLATION_BEIDOU -> "B2a"; else -> "L5" }
            kotlin.math.abs(f - 1207.14) < 1 -> if (t == GnssStatus.CONSTELLATION_GALILEO) "E5b" else "B2b"
            kotlin.math.abs(f - 1561.098) < 1 -> "B1I"
            kotlin.math.abs(f - 1227.60) < 1 -> "L2"
            kotlin.math.abs(f - 1278.75) < 1 -> if (t == GnssStatus.CONSTELLATION_GALILEO) "E6" else "L6"
            kotlin.math.abs(f - 1268.52) < 1 -> "B3I"
            f in 1597.0..1607.0 -> "G1"
            f in 1240.0..1250.0 -> "G2"
            kotlin.math.abs(f - 2492.028) < 1 -> "S"
            else -> "%.2f MHz".format(f)
        }
    }
}
