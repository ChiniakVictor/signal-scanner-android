package com.victor.signalscanner

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

data class TrackerView(
    val row: TrackerRow, val verdict: Verdict, val sightings: List<SightingRow>,
    val liveRssi: Int?, val liveAt: Long,
)

/** Tracker detection: classify BLE adverts, log sightings (with approximate location) to Room, evaluate the "following you" rule. All on-device. */
object TrackerWatch {
    private const val TAG = "SSTrackers"
    const val CHANNEL = "tracker_alerts"
    const val KEEP_DAYS = 7
    const val WINDOW_MS = 48 * 3600_000L   // rule looks at the last 48 h of sightings

    private lateinit var app: Context
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dao by lazy { TrackerDb.get(app).dao() }
    private val prefs by lazy { app.getSharedPreferences("trackers", Context.MODE_PRIVATE) }

    val trackers = MutableStateFlow<List<TrackerView>>(emptyList())
    val fix = MutableStateFlow<Location?>(null)
    val live = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val lastRecorded = ConcurrentHashMap<String, SightingRow>()
    private var bonded: Set<String> = emptySet()
    private var locListening = false

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= 26) app.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Unknown tracker alerts", NotificationManager.IMPORTANCE_HIGH).apply { description = "A Bluetooth tracker seems to be moving with you" })
        io.launch { runCatching { dao.pruneSightings(System.currentTimeMillis() - KEEP_DAYS * 86400_000L); dao.pruneTrackers(System.currentTimeMillis() - KEEP_DAYS * 86400_000L) }; refresh() }
    }

    // ---------- settings ----------
    var rule: TrackerRule
        get() = TrackerRule(prefs.getInt("min_places", 3), prefs.getInt("sep_m", 500).toDouble(), prefs.getInt("min_min", 30), prefs.getInt("sep_m", 500).toDouble())
        set(v) { prefs.edit().putInt("min_places", v.minPlaces).putInt("sep_m", v.placeSepM.toInt()).putInt("min_min", v.minMinutes).apply(); io.launch { refresh() } }
    var home: Home?
        get() = if (prefs.contains("home_lat_b")) Home(Double.fromBits(prefs.getLong("home_lat_b", 0L)), Double.fromBits(prefs.getLong("home_lon_b", 0L)), prefs.getInt("home_r", 250).toDouble()) else null
        set(v) {
            if (v == null) prefs.edit().remove("home_lat_b").remove("home_lon_b").apply()
            else prefs.edit().putLong("home_lat_b", v.lat.toBits()).putLong("home_lon_b", v.lon.toBits()).putInt("home_r", v.radiusM.toInt()).apply()
            io.launch { refresh() }
        }
    var backgroundWatch: Boolean
        get() = prefs.getBoolean("bg_watch", false)
        set(v) { prefs.edit().putBoolean("bg_watch", v).apply(); syncService(app) }

    fun syncService(ctx: Context) {
        val i = Intent(ctx, TrackerWatchService::class.java)
        if (backgroundWatch) runCatching { ContextCompat.startForegroundService(ctx, i) }.onFailure { Log.w(TAG, "can't start watch: $it") }
        else runCatching { ctx.stopService(i) }
    }

    // ---------- location (approximate, only while scanning) ----------
    private val locCb = LocationListener { l -> fix.value = l }

    @SuppressLint("MissingPermission")
    fun startLocation() {
        if (locListening || !has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION)) return
        val lm = app.getSystemService(LocationManager::class.java) ?: return
        val provider = when {
            Build.VERSION.SDK_INT >= 31 && lm.allProviders.contains(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.allProviders.contains(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> LocationManager.GPS_PROVIDER
        }
        runCatching {
            listOf(provider, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).firstNotNullOfOrNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
                ?.let { if (fix.value == null) fix.value = it }
            if (Build.VERSION.SDK_INT >= 31 && provider == LocationManager.FUSED_PROVIDER)
                // high accuracy: this phone's network location (Google Location Accuracy) may be off, so let fused use GNSS
                lm.requestLocationUpdates(provider, android.location.LocationRequest.Builder(20_000L).setQuality(android.location.LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateDistanceMeters(20f).build(), ContextCompat.getMainExecutor(app), locCb)
            else lm.requestLocationUpdates(provider, 20_000L, 20f, locCb, Looper.getMainLooper())
            locListening = true
        }.onFailure { Log.w(TAG, "location: $it") }
    }

    fun stopLocation() {
        if (!locListening) return
        runCatching { app.getSystemService(LocationManager::class.java)?.removeUpdates(locCb) }
        locListening = false
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun refreshBonded() {
        if (Build.VERSION.SDK_INT >= 31 && !has(Manifest.permission.BLUETOOTH_CONNECT)) return
        bonded = runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.map { it.address }?.toSet() }.getOrNull() ?: bonded
    }

    // ---------- sightings ----------
    fun onScan(r: ScanResult) {
        if (!::app.isInitialized) return
        val rec = r.scanRecord ?: return
        val manu = HashMap<Int, ByteArray>()
        rec.manufacturerSpecificData?.let { sa -> for (k in 0 until sa.size()) manu[sa.keyAt(k)] = sa.valueAt(k) }
        val svc = HashMap<Int, ByteArray>()
        rec.serviceData?.forEach { (u, b) -> short16(u)?.let { svc[it] = b } }
        val uu = rec.serviceUuids?.mapNotNull { short16(it) }?.toSet() ?: emptySet()
        val hit = TrackerClassify.classify(r.device.address, manu, svc, uu) ?: return
        onHit(hit, r.device.address, r.rssi)
    }

    private fun short16(u: ParcelUuid): Int? {
        val s = u.uuid.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) s.substring(4, 8).toInt(16) else null
    }

    fun onHit(hit: TrackerHit, address: String, rssi: Int, now: Long = System.currentTimeMillis()) {
        live[hit.id] = rssi to now
        val f = fix.value?.takeIf { now - it.time < 5 * 60_000L }
        val prev = lastRecorded[hit.id]
        val moved = prev != null && f != null && (prev.lat == null || TrackerRules.distM(prev.lat, prev.lon!!, f.latitude, f.longitude) > 100)
        if (prev != null && now - prev.t < 60_000L && !moved) return   // at most one sighting a minute unless we moved
        val row = SightingRow(trackerId = hit.id, t = now, lat = f?.latitude, lon = f?.longitude, acc = f?.accuracy, rssi = rssi, address = address)
        lastRecorded[hit.id] = row
        io.launch {
            runCatching {
                if (bonded.isEmpty()) refreshBonded()
                val old = dao.get(hit.id)
                dao.upsert(TrackerRow(hit.id, hit.type, hit.family, hit.detail, address, hit.payload, hit.separated,
                    old?.firstSeen ?: now, now, (old?.trusted ?: false) || address in bonded, old?.alertedAt ?: 0))
                dao.insert(row)
                refresh(alertFor = hit.id)
            }.onFailure { Log.w(TAG, "db: $it") }
        }
    }

    fun setTrusted(id: String, t: Boolean) = io.launch { dao.setTrusted(id, t); if (!t) dao.setAlerted(id, 0); refresh() }
    fun forget(id: String) = io.launch { dao.deleteSightings(id); dao.delete(id); lastRecorded.remove(id); live.remove(id); refresh() }

    fun refresh(alertFor: String? = null) {
        val now = System.currentTimeMillis()
        val h = home; val ru = rule
        val list = dao.recent(now - KEEP_DAYS * 86400_000L).map { t ->
            val s = dao.sightings(t.trackerId, now - WINDOW_MS)
            val v = TrackerRules.evaluate(s.map { Sighting(it.t, it.lat, it.lon, it.acc, it.rssi) }, h, ru, t.trusted)
            val l = live[t.trackerId]
            TrackerView(t, v, s, l?.first, l?.second ?: 0L)
        }
        // owner-nearby Find My items rotate their address every ~15 min; hide stale rotations so the list doesn't fill up with them
        trackers.value = list.filter { it.verdict.following || it.row.trusted || it.row.separated != false || now - it.row.lastSeen < 30 * 60_000L }.sortedWith(compareBy({ !it.verdict.following }, { -it.row.lastSeen }))
        list.filter { it.verdict.following && it.row.alertedAt == 0L && (alertFor == null || it.row.trackerId == alertFor) }.forEach { tv ->
            dao.setAlerted(tv.row.trackerId, now); notify(tv)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notify(tv: TrackerView) {
        Log.i(TAG, "ALERT ${tv.row.trackerId}: ${tv.verdict.reason}")
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) return
        val pi = PendingIntent.getActivity(app, tv.row.trackerId.hashCode(),
            Intent(app, MainActivity::class.java).putExtra("tab", 2).putExtra("select", tv.row.trackerId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Unknown ${tv.row.type} may be following you")
            .setContentText(tv.verdict.reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${tv.verdict.reason}. Seen ${tv.sightings.size} times since ${fmtDay(tv.row.firstSeen)}. Open Signal Scanner → Trackers for details and how to find it."))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(pi).build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(app).notify(tv.row.trackerId.hashCode(), n) }
    }

    fun fmtDay(t: Long): String = java.text.SimpleDateFormat("EEE HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))

    /** BLE filters used by the background watch (the screen-off scanner requires filters). */
    fun scanFilters(): List<android.bluetooth.le.ScanFilter> {
        fun u(x: Int) = ParcelUuid.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(x))
        return listOf(
            android.bluetooth.le.ScanFilter.Builder().setManufacturerData(TrackerClassify.APPLE, byteArrayOf(0x12), byteArrayOf(0xFF.toByte())).build(),
            android.bluetooth.le.ScanFilter.Builder().setServiceData(u(0xFD5A), byteArrayOf()).build(),
            android.bluetooth.le.ScanFilter.Builder().setServiceData(u(0xFEAA), byteArrayOf(0x40), byteArrayOf(0xFE.toByte())).build(),
            android.bluetooth.le.ScanFilter.Builder().setServiceData(u(0xFCB2), byteArrayOf()).build(),
            android.bluetooth.le.ScanFilter.Builder().setServiceUuid(u(0xFEED)).build(),
            android.bluetooth.le.ScanFilter.Builder().setServiceUuid(u(0xFEEC)).build(),
            android.bluetooth.le.ScanFilter.Builder().setServiceUuid(u(0xFD84)).build(),
            android.bluetooth.le.ScanFilter.Builder().setServiceUuid(u(0xFE33)).build(),
            android.bluetooth.le.ScanFilter.Builder().setServiceUuid(u(0xFE65)).build(),
            android.bluetooth.le.ScanFilter.Builder().setManufacturerData(TrackerClassify.CHIPOLO_CO, byteArrayOf()).build(),
        )
    }
}
