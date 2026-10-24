package com.victor.signalscanner

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

private const val TAG = "SSScanner"
private const val HISTORY = 120

/** Holds all scan state. Scanning only runs between start() and stop() (the activity's onStart/onStop). */
object Repo {
    val wifiAps = MutableStateFlow<List<WifiAp>>(emptyList())
    val wifiState = MutableStateFlow(WifiState())
    val conn = MutableStateFlow<ConnInfo?>(null)
    val bt = MutableStateFlow<List<BtDevice>>(emptyList())
    val btState = MutableStateFlow(BtState())
    val nfcState = MutableStateFlow(NfcState())
    val taps = MutableStateFlow<List<NfcTap>>(emptyList())
    val running = MutableStateFlow(false)
    val rtt = MutableStateFlow<Map<String, String>>(emptyMap())
    val radios = MutableStateFlow<Radios?>(null)
    val locationOn = MutableStateFlow(true)

    fun addTap(t: NfcTap) { taps.value = (listOf(t) + taps.value).take(200) }
}

class Scanner(private val ctx: Context) {
    private val app = ctx.applicationContext
    private val settings = Settings(app)
    private val wm = app.getSystemService(WifiManager::class.java)
    private val cm = app.getSystemService(ConnectivityManager::class.java)
    private val btm = app.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = btm?.adapter
    private var scope: CoroutineScope? = null
    private var receiversOn = false

    // ---------- radios ----------
    fun detectRadios(): Radios {
        val pm = app.packageManager
        val r = Radios(
            wifi = pm.hasSystemFeature(PackageManager.FEATURE_WIFI) && wm != null,
            bt = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) && adapter != null,
            ble = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && adapter != null,
            nfc = pm.hasSystemFeature(PackageManager.FEATURE_NFC) && NfcAdapter.getDefaultAdapter(app) != null,
            wifiAware = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE),
            rtt = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT),
            uwb = pm.hasSystemFeature("android.hardware.uwb"),
        )
        Repo.radios.value = r
        return r
    }

    fun locationEnabled(): Boolean {
        val lm = app.getSystemService(LocationManager::class.java) ?: return true
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else true
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED
    private val canWifi get() = has(Manifest.permission.ACCESS_FINE_LOCATION) && (Build.VERSION.SDK_INT < 33 || has(Manifest.permission.NEARBY_WIFI_DEVICES))
    private val canBtScan get() = if (Build.VERSION.SDK_INT >= 31) has(Manifest.permission.BLUETOOTH_SCAN) else has(Manifest.permission.ACCESS_FINE_LOCATION)
    private val canBtConnect get() = Build.VERSION.SDK_INT < 31 || has(Manifest.permission.BLUETOOTH_CONNECT)

    fun start() {
        if (scope != null) return
        Lookup.load(app)
        detectRadios()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        Repo.running.value = true
        registerReceivers()
        registerNetCallback()
        s.launch { wifiLoop() }
        s.launch { btLoop() }
        s.launch { CellScanner(app).loop { 5000L } }
        TrackerWatch.init(app); TrackerWatch.startLocation()
        refreshNfcState()
    }

    fun stop() {
        scope?.cancel(); scope = null
        Repo.running.value = false
        TrackerWatch.stopLocation()
        stopBle()
        @SuppressLint("MissingPermission")
        runCatching { if (canBtScan && adapter?.isDiscovering == true) adapter?.cancelDiscovery() }
        Repo.btState.value = Repo.btState.value.copy(bleScanning = false, discovering = false)
        if (receiversOn) { runCatching { app.unregisterReceiver(receiver) }; receiversOn = false }
        netCb?.let { runCatching { cm.unregisterNetworkCallback(it) } }; netCb = null
    }

    /** Restart loops so new intervals take effect. */
    fun restart() { if (scope != null) { stop(); start() } }

    // ---------- receivers ----------
    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                    val updated = i.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                    scope?.launch { readWifi(updated) }
                }
                WifiManager.WIFI_STATE_CHANGED_ACTION -> scope?.launch { readWifi(null) }
                BluetoothDevice.ACTION_FOUND -> {
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    if (d != null) {
                        val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        val name = i.getStringExtra(BluetoothDevice.EXTRA_NAME)
                        val cls = runCatching { (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_CLASS, android.bluetooth.BluetoothClass::class.java)
                            else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_CLASS))?.hashCode() }.getOrNull()
                        bt.onClassic(d, if (rssi == Short.MIN_VALUE.toInt()) null else rssi, name, cls)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> Repo.btState.value = Repo.btState.value.copy(discovering = true)
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> Repo.btState.value = Repo.btState.value.copy(discovering = false)
                BluetoothAdapter.ACTION_STATE_CHANGED -> scope?.launch { btStateRefresh(); if (adapter?.isEnabled == true) startBle() else stopBle() }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED, BluetoothDevice.ACTION_NAME_CHANGED -> {}
                NfcAdapter.ACTION_ADAPTER_STATE_CHANGED -> refreshNfcState()
                LocationManager.MODE_CHANGED_ACTION -> {
                    Repo.locationOn.value = locationEnabled()
                    scope?.launch { requestScan(); stopBle(); startBle() }
                }
            }
        }
    }

    private fun registerReceivers() {
        val f = IntentFilter().apply {
            addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION); addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED); addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED); addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        Repo.locationOn.value = locationEnabled()
        ContextCompat.registerReceiver(app, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        receiversOn = true
    }

    // ---------- Wi-Fi ----------
    private val tracks = HashMap<String, WifiTrack>()
    private class WifiTrack(val first: Long) { val hist = ArrayList<Sample>(); var count = 0; var lastTs = 0L; var ap: WifiAp? = null }
    private val events = ArrayList<ScanEvent>()

    private fun readThrottleSetting(): String = try {
        when (android.provider.Settings.Global.getString(app.contentResolver, "wifi_scan_throttle_enabled")) {
            null -> "on (default – setting never changed)"
            "0" -> "off (Developer options)"
            "1" -> "on"
            else -> "unknown"
        }
    } catch (e: SecurityException) { "not readable by apps" } catch (e: Exception) { "not readable (${e.javaClass.simpleName})" }

    private suspend fun wifiLoop() {
        if (wm == null) { Repo.wifiState.value = WifiState(supported = false); return }
        Repo.wifiState.value = Repo.wifiState.value.copy(caps = wifiCaps(), throttleSetting = readThrottleSetting())
        readWifi(null)
        while (currentCoroutineContext().isActive) {
            requestScan()
            delay(settings.wifiIntervalSec.coerceAtLeast(5) * 1000L)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestScan() {
        val st = Repo.wifiState.value
        val enabled = wm.isWifiEnabled
        val lm = app.getSystemService(LocationManager::class.java)
        val locOn = if (Build.VERSION.SDK_INT >= 28) lm?.isLocationEnabled ?: true else true
        if (!canWifi) { Repo.wifiState.value = st.copy(enabled = enabled, locationOn = locOn, error = "Permission needed: Nearby devices + Location"); return }
        if (!enabled && !wm.isScanAlwaysAvailable) { Repo.wifiState.value = st.copy(enabled = false, locationOn = locOn, error = "Wi-Fi is off"); return }
        if (!locOn) { Repo.wifiState.value = st.copy(enabled = enabled, locationOn = false, error = "Location is off – Android returns no Wi-Fi scan results", throttleSetting = readThrottleSetting()); return }
        @Suppress("DEPRECATION") val ok = runCatching { wm.startScan() }.getOrDefault(false)
        val now = System.currentTimeMillis()
        synchronized(events) { events += ScanEvent(now, true, ok, null, 0); while (events.size > 40) events.removeAt(0) }
        Repo.wifiState.value = st.copy(enabled = enabled, locationOn = locOn, lastRequest = now, lastRequestAccepted = ok,
            error = if (!locOn) "Location is off – Android returns no Wi-Fi results" else null,
            throttleSetting = readThrottleSetting(), throttleInferred = inferThrottle(), events = synchronized(events) { events.toList() })
        if (!ok) scope?.launch { readWifi(false) }
        else scope?.launch { delay(12000); if (Repo.wifiState.value.lastResults < now) readWifi(null) }
    }

    private fun inferThrottle(): String {
        val now = System.currentTimeMillis()
        val recent = synchronized(events) { events.filter { it.requested && now - it.time < 120_000 } }
        val ok = recent.count { it.accepted }; val rej = recent.count { !it.accepted }
        return when {
            ok > 4 -> "off – $ok scans accepted in the last 2 min (limit is 4 when throttled)"
            rej > 0 -> "on – $rej scan request(s) refused in the last 2 min, showing cached results"
            else -> "$ok scan(s) accepted in the last 2 min"
        }
    }

    private fun wifiCaps(): List<Pair<String, String>> {
        val l = ArrayList<Pair<String, String>>()
        fun yn(b: Boolean) = if (b) "yes" else "no"
        runCatching {
            l += "5 GHz" to yn(wm.is5GHzBandSupported)
            if (Build.VERSION.SDK_INT >= 30) { l += "6 GHz" to yn(wm.is6GHzBandSupported); l += "60 GHz" to yn(wm.is60GHzBandSupported) }
            if (Build.VERSION.SDK_INT >= 30) {
                l += "Wi-Fi 6 (ax)" to yn(wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11AX))
                if (Build.VERSION.SDK_INT >= 33) l += "Wi-Fi 7 (be)" to yn(wm.isWifiStandardSupported(ScanResult.WIFI_STANDARD_11BE))
            }
            l += "WPA3-SAE" to yn(if (Build.VERSION.SDK_INT >= 29) wm.isWpa3SaeSupported else false)
            if (Build.VERSION.SDK_INT >= 29) { l += "Enhanced Open (OWE)" to yn(wm.isEnhancedOpenSupported); l += "WPA3-192" to yn(wm.isWpa3SuiteBSupported) }
            if (Build.VERSION.SDK_INT >= 30) l += "Scan throttling setting" to readThrottleSetting()
            l += "Wi-Fi RTT (802.11mc)" to yn(app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT))
            l += "Wi-Fi Aware" to yn(app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE))
            l += "Wi-Fi Direct" to yn(wm.isP2pSupported)
            if (Build.VERSION.SDK_INT >= 31) { l += "STA/STA concurrency" to yn(wm.isStaConcurrencyForLocalOnlyConnectionsSupported); l += "Dual-band simultaneous (DBS)" to yn(wm.isMakeBeforeBreakWifiSwitchingSupported) }
            if (Build.VERSION.SDK_INT >= 30) l += "Max signal level" to "${wm.maxSignalLevel}"
        }
        return l
    }

    @SuppressLint("MissingPermission")
    private fun readWifi(updated: Boolean?) {
        if (wm == null) return
        val results = if (canWifi) runCatching { wm.scanResults }.getOrNull() ?: emptyList() else emptyList()
        val now = System.currentTimeMillis()
        val bootOffset = now - SystemClock.elapsedRealtime()
        val connBssid = Repo.conn.value?.bssid?.lowercase()
        val seen = HashSet<String>()
        var newest = 0L
        synchronized(tracks) {
            for (r in results) {
                val b = r.BSSID?.lowercase() ?: continue
                seen += b
                val tr = tracks.getOrPut(b) { WifiTrack(now) }
                val heard = bootOffset + r.timestamp / 1000
                newest = maxOf(newest, heard)
                if (r.timestamp != tr.lastTs) {
                    tr.lastTs = r.timestamp; tr.count++
                    tr.hist += Sample(heard, r.level); while (tr.hist.size > HISTORY) tr.hist.removeAt(0)
                }
                tr.ap = build(r, tr, heard, b == connBssid)
            }
            val forget = settings.forgetMinutes * 60_000L
            val it = tracks.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val ap = e.value.ap ?: run { it.remove(); null } ?: continue
                if (e.key !in seen) {
                    if (now - ap.resultTimeMs > forget) it.remove()
                    else e.value.ap = ap.copy(inRange = false, connected = e.key == connBssid)
                }
            }
            Repo.wifiAps.value = tracks.values.mapNotNull { it.ap }
        }
        synchronized(events) {
            if (updated != null && events.isNotEmpty()) { val l = events.last(); events[events.size - 1] = l.copy(updated = updated, count = results.size) }
        }
        Repo.wifiState.value = Repo.wifiState.value.copy(enabled = wm.isWifiEnabled, scanAlwaysAvailable = runCatching { wm.isScanAlwaysAvailable }.getOrDefault(false),
            lastResults = now, lastUpdated = updated, newestResultMs = if (newest > 0) newest else Repo.wifiState.value.newestResultMs,
            throttleInferred = inferThrottle(), events = synchronized(events) { events.toList() })
    }

    private fun build(r: ScanResult, tr: WifiTrack, heard: Long, connected: Boolean): WifiAp {
        val ssid = if (Build.VERSION.SDK_INT >= 33) r.wifiSsid?.toString()?.removeSurrounding("\"") ?: "" else @Suppress("DEPRECATION") (r.SSID ?: "")
        val ie: IeInfo? = if (Build.VERSION.SDK_INT >= 30) runCatching {
            WifiParse.parseIes(r.informationElements.map { e -> val bb = e.bytes; val arr = ByteArray(bb.remaining()); bb.duplicate().get(arr); Triple(e.id, e.idExt, arr) })
        }.getOrNull() else null
        val std = if (Build.VERSION.SDK_INT >= 30) r.wifiStandard else 0
        var width = WifiParse.widthMhz(r.channelWidth)
        ie?.ehtWidth?.let { if (it > width) width = it }
        val widthName = if (width == 320) "320 MHz" else WifiParse.widthName(r.channelWidth)
        val sec = WifiParse.security(r.capabilities ?: "", ie)
        val hidden = ssid.isEmpty() || ssid == "<unknown ssid>" || ssid.all { it == '\u0000' }
        return WifiAp(
            bssid = r.BSSID.lowercase(), ssid = ssid, hidden = hidden, vendor = Lookup.vendor(r.BSSID), bssidRandom = Lookup.isLocallyAdministered(r.BSSID),
            rssi = r.level, freq = r.frequency, band = WifiParse.band(r.frequency), channel = WifiParse.channelOf(r.frequency), widthMhz = width, widthName = widthName,
            center0 = r.centerFreq0, center1 = r.centerFreq1, standard = std, standardName = WifiParse.standardName(std, r.frequency),
            security = sec, capabilities = r.capabilities ?: "", mcResponder = r.is80211mcResponder,
            azResponder = if (Build.VERSION.SDK_INT >= 35) runCatching { r.is80211azNtbResponder }.getOrDefault(false) else false,
            passpoint = r.isPasspointNetwork, operatorName = r.operatorFriendlyName?.toString()?.takeIf { it.isNotBlank() }, venueName = r.venueName?.toString()?.takeIf { it.isNotBlank() },
            ie = ie, firstSeen = tr.first, lastSeen = heard, resultTimeMs = heard, history = tr.hist.toList(), seenCount = tr.count, connected = connected, inRange = true,
            mldMac = (if (Build.VERSION.SDK_INT >= 33) r.apMldMacAddress?.toString() else null) ?: ie?.mldMac, raw = r,
        )
    }

    // --- connected network ---
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var lastLp: LinkProperties? = null
    private var lastNc: NetworkCapabilities? = null

    private fun registerNetCallback() {
        if (cm == null) return
        val cb = if (Build.VERSION.SDK_INT >= 31) object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onCapabilitiesChanged(n: Network, nc: NetworkCapabilities) { lastNc = nc; publishConn() }
            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) { lastLp = lp; publishConn() }
            override fun onLost(n: Network) { lastNc = null; lastLp = null; Repo.conn.value = null }
        } else object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, nc: NetworkCapabilities) { lastNc = nc; publishConn() }
            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) { lastLp = lp; publishConn() }
            override fun onLost(n: Network) { lastNc = null; lastLp = null; Repo.conn.value = null }
        }
        runCatching { cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb); netCb = cb }
            .onFailure { Log.w(TAG, "net callback", it) }
    }

    private fun secName(t: Int) = when (t) { 0 -> "Open"; 1 -> "WEP"; 2 -> "WPA2-Personal (PSK)"; 3 -> "WPA2-Enterprise"; 4 -> "WPA3-Personal (SAE)"; 5 -> "WPA3-Enterprise 192-bit"
        6 -> "Enhanced Open (OWE)"; 7 -> "WAPI-PSK"; 8 -> "WAPI-CERT"; 9 -> "WPA3-Enterprise"; 10 -> "OSEN"; 11 -> "Passpoint R1/R2"; 12 -> "Passpoint R3"; 13 -> "DPP (Easy Connect)"; else -> null }

    @SuppressLint("MissingPermission")
    private fun publishConn() {
        val nc = lastNc ?: return
        val wi: WifiInfo? = (if (Build.VERSION.SDK_INT >= 29) nc.transportInfo as? WifiInfo else null) ?: @Suppress("DEPRECATION") wm?.connectionInfo
        if (wi == null) return
        val lp = lastLp
        val iface = lp?.interfaceName
        val apiMac = wi.macAddress?.takeIf { it != "02:00:00:00:00:00" }
        val nifMac = runCatching { NetworkInterface.getByName(iface ?: "wlan0")?.hardwareAddress?.joinToString(":") { "%02x".format(it) } }.getOrNull()
        val mac = apiMac ?: nifMac
        val macNote = when {
            mac == null -> "Android hides the MAC from apps. Samsung uses a randomized (private) MAC per network by default – check Wi-Fi > network > MAC address type."
            Lookup.isLocallyAdministered(mac) -> "Randomized (private) MAC – locally administered bit set"
            else -> "Device (hardware) MAC – randomization is off for this network"
        }
        val ssid = wi.ssid?.removeSurrounding("\"")?.takeIf { it != WifiManager.UNKNOWN_SSID && it != "<unknown ssid>" }
        val v4 = lp?.linkAddresses?.filter { it.address is Inet4Address }?.map { "${it.address.hostAddress}/${it.prefixLength}" } ?: emptyList()
        val v6 = lp?.linkAddresses?.filter { it.address !is Inet4Address }?.map { "${it.address.hostAddress}/${it.prefixLength}" } ?: emptyList()
        Repo.conn.value = ConnInfo(
            ssid = ssid, bssid = wi.bssid?.takeIf { it != "02:00:00:00:00:00" }?.lowercase(), rssi = wi.rssi, linkMbps = wi.linkSpeed,
            txMbps = if (Build.VERSION.SDK_INT >= 29) wi.txLinkSpeedMbps else -1, rxMbps = if (Build.VERSION.SDK_INT >= 29) wi.rxLinkSpeedMbps else -1,
            maxTxMbps = if (Build.VERSION.SDK_INT >= 30) wi.maxSupportedTxLinkSpeedMbps else -1, maxRxMbps = if (Build.VERSION.SDK_INT >= 30) wi.maxSupportedRxLinkSpeedMbps else -1,
            freq = wi.frequency, standard = if (Build.VERSION.SDK_INT >= 30) wi.wifiStandard else 0,
            security = if (Build.VERSION.SDK_INT >= 31) secName(wi.currentSecurityType) else null,
            ipv4 = v4, ipv6 = v6, gateway = lp?.routes?.filter { it.hasGateway() }?.mapNotNull { it.gateway?.hostAddress }?.distinct() ?: emptyList(),
            dns = lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList(),
            privateDns = if (Build.VERSION.SDK_INT >= 28) (lp?.privateDnsServerName ?: if (lp?.isPrivateDnsActive == true) "automatic (active)" else "off / not active") else null,
            dhcpServer = if (Build.VERSION.SDK_INT >= 30) lp?.dhcpServerAddress?.hostAddress else null, domains = lp?.domains,
            mtu = if (Build.VERSION.SDK_INT >= 29) lp?.mtu ?: 0 else 0, iface = iface, mac = mac, macNote = macNote,
            hiddenSsid = wi.hiddenSSID, passpointFqdn = if (Build.VERSION.SDK_INT >= 29) wi.passpointFqdn else null,
            mldMac = if (Build.VERSION.SDK_INT >= 33) wi.apMldMacAddress?.toString() else null,
            mloLinks = if (Build.VERSION.SDK_INT >= 33) runCatching { wi.associatedMloLinks.map { "link ${it.linkId}: ${it.apMacAddress} · ${WifiParse.band(freqOf(it.band, it.channel))} ch ${it.channel} · ${it.rssi} dBm" } }.getOrDefault(emptyList()) else emptyList(),
            downKbps = nc.linkDownstreamBandwidthKbps, upKbps = nc.linkUpstreamBandwidthKbps,
            validated = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), metered = !nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            networkId = wi.networkId,
        )
    }

    private fun freqOf(band: Int, ch: Int) = when (band) { 1 -> 2407 + ch * 5; 2 -> 5000 + ch * 5; 8 -> 5950 + ch * 5; else -> 0 }

    // --- RTT ranging (802.11mc) ---
    @SuppressLint("MissingPermission")
    fun rangeRtt(ap: WifiAp) {
        val sr = ap.raw ?: return
        val rtt = app.getSystemService(WifiRttManager::class.java)
        fun put(s: String) { Repo.rtt.value = Repo.rtt.value + (ap.bssid to s) }
        if (rtt == null || !rtt.isAvailable) { put("RTT not available right now"); return }
        put("measuring…")
        runCatching {
            val req = RangingRequest.Builder().addAccessPoint(sr).build()
            rtt.startRanging(req, ContextCompat.getMainExecutor(app), object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) { put("failed (code $code)") }
                override fun onRangingResults(results: MutableList<RangingResult>) {
                    val r = results.firstOrNull()
                    if (r == null || r.status != RangingResult.STATUS_SUCCESS) put("no answer from AP")
                    else put("%.1f m ± %.1f m (%d/%d measurements)".format(r.distanceMm / 1000.0, r.distanceStdDevMm / 1000.0, r.numSuccessfulMeasurements, r.numAttemptedMeasurements))
                }
            })
        }.onFailure { put("failed: ${it.message}") }
    }

    // ---------- Bluetooth ----------
    private val bt = BtTracker()

    private fun btStateRefresh() {
        val a = adapter
        @SuppressLint("MissingPermission")
        val name = if (canBtConnect) runCatching { a?.name }.getOrNull() else null
        val caps = ArrayList<Pair<String, String>>()
        if (a != null && Build.VERSION.SDK_INT >= 26) runCatching {
            fun yn(b: Boolean) = if (b) "yes" else "no"
            caps += "LE 2M PHY" to yn(a.isLe2MPhySupported); caps += "LE Coded PHY (long range)" to yn(a.isLeCodedPhySupported)
            caps += "LE extended advertising" to yn(a.isLeExtendedAdvertisingSupported); caps += "LE periodic advertising" to yn(a.isLePeriodicAdvertisingSupported)
            caps += "Offloaded scan filtering" to yn(a.isOffloadedFilteringSupported); caps += "Multiple advertisement" to yn(a.isMultipleAdvertisementSupported)
            if (Build.VERSION.SDK_INT >= 33) caps += "LE Audio" to yn(a.isLeAudioSupported == 10)
        }
        Repo.btState.value = Repo.btState.value.copy(supported = a != null, leSupported = app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE),
            enabled = a?.isEnabled == true, adapterName = name, caps = caps)
    }

    private suspend fun btLoop() {
        btStateRefresh()
        if (adapter == null) { Repo.btState.value = Repo.btState.value.copy(supported = false); return }
        startBle()
        var lastDiscovery = 0L
        var bleStarted = SystemClock.elapsedRealtime()
        while (currentCoroutineContext().isActive) {
            val st = Repo.btState.value
            if (!canBtScan) Repo.btState.value = st.copy(error = "Permission needed: Nearby devices")
            else if (adapter?.isEnabled != true) Repo.btState.value = st.copy(enabled = false, error = "Bluetooth is off")
            else {
                Repo.btState.value = st.copy(error = if (!locationEnabled()) "Location is off – Android hides nearby Bluetooth devices from apps (paired devices still listed)" else null)
                if (!st.bleScanning) startBle()
                // Android downgrades unfiltered scans after 30 min; restart every 20 min
                if (SystemClock.elapsedRealtime() - bleStarted > 20 * 60_000L) { stopBle(); startBle(); bleStarted = SystemClock.elapsedRealtime() }
                if (settings.classicDiscovery && !Repo.btState.value.discovering && SystemClock.elapsedRealtime() - lastDiscovery > 15_000) {
                    @SuppressLint("MissingPermission") val ok = runCatching { adapter?.startDiscovery() == true }.getOrDefault(false)
                    if (ok) lastDiscovery = SystemClock.elapsedRealtime()
                }
                if (canBtConnect) bt.addBonded(runCatching { @SuppressLint("MissingPermission") val b = adapter?.bondedDevices; b }.getOrNull())
            }
            bt.tick(System.currentTimeMillis(), settings.btIntervalSec * 1000L, settings.forgetMinutes * 60_000L)
            Repo.btState.value = Repo.btState.value.copy(lastTick = System.currentTimeMillis(), enabled = adapter?.isEnabled == true)
            delay(settings.btIntervalSec.coerceAtLeast(1) * 1000L)
        }
    }

    private val leCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: android.bluetooth.le.ScanResult) { bt.onLe(result, canBtConnect); TrackerWatch.onScan(result) }
        override fun onBatchScanResults(results: MutableList<android.bluetooth.le.ScanResult>) { results.forEach { bt.onLe(it, canBtConnect); TrackerWatch.onScan(it) } }
        override fun onScanFailed(errorCode: Int) {
            Repo.btState.value = Repo.btState.value.copy(bleScanning = false, error = "BLE scan failed (code $errorCode${if (errorCode == 6) ": started too often, retrying" else ""})")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBle() {
        val a = adapter ?: return
        if (!canBtScan || !a.isEnabled || Repo.btState.value.bleScanning) return
        val sc = a.bluetoothLeScanner ?: return
        val s = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
        if (Build.VERSION.SDK_INT >= 26 && a.isLeExtendedAdvertisingSupported) s.setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
        runCatching { sc.startScan(null, s.build(), leCb); Repo.btState.value = Repo.btState.value.copy(bleScanning = true, error = null) }
            .onFailure { Repo.btState.value = Repo.btState.value.copy(bleScanning = false, error = "BLE scan: ${it.message}") }
    }

    @SuppressLint("MissingPermission")
    private fun stopBle() {
        if (!Repo.btState.value.bleScanning) return
        runCatching { if (canBtScan) adapter?.bluetoothLeScanner?.stopScan(leCb) }
        Repo.btState.value = Repo.btState.value.copy(bleScanning = false)
    }

    // ---------- NFC ----------
    fun refreshNfcState(readerActive: Boolean? = null) {
        val a = NfcAdapter.getDefaultAdapter(app)
        val pm = app.packageManager
        Repo.nfcState.value = if (a == null) NfcState(supported = false, note = "This device has no NFC hardware")
        else NfcState(supported = true, enabled = a.isEnabled,
            secureSupported = Build.VERSION.SDK_INT >= 29 && runCatching { a.isSecureNfcSupported }.getOrDefault(false),
            secureEnabled = Build.VERSION.SDK_INT >= 29 && runCatching { a.isSecureNfcEnabled }.getOrDefault(false),
            readerActive = readerActive ?: Repo.nfcState.value.readerActive,
            hce = pm.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION))
    }
}

/** Aggregates Bluetooth LE advertisements and classic discovery results per address. */
class BtTracker {
    private class T(val addr: String, val first: Long) {
        var name: String? = null; var rssi: Int? = null; var lastRssiAt = 0L; var lastSeen = 0L
        val hist = ArrayList<Sample>(); var ble = false; var classic = false; var cod: Int? = null; var type = 0; var bond = 10
        var addrType: Int? = null; var txPower: Int? = null; var connectable: Boolean? = null; var legacy: Boolean? = null; var phy: String? = null
        var raw: ByteArray? = null; var packets = 0; val stamps = ArrayList<Long>(); var sid: Int? = null; var periodic: Double? = null
        var bondedOnly = false; var snap: BtDevice? = null; var dirty = true
    }
    private val map = HashMap<String, T>()

    private fun t(addr: String, now: Long) = map.getOrPut(addr) { T(addr, now) }

    @SuppressLint("MissingPermission")
    fun onLe(r: android.bluetooth.le.ScanResult, canConnect: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(map) {
            val x = t(r.device.address, now)
            x.ble = true; x.bondedOnly = false; x.rssi = r.rssi; x.lastRssiAt = now; x.lastSeen = now; x.packets++
            val rec = r.scanRecord
            rec?.deviceName?.takeIf { it.isNotBlank() }?.let { x.name = it }
            if (canConnect) runCatching { r.device.name?.takeIf { it.isNotBlank() }?.let { x.name = it }; x.bond = r.device.bondState; x.type = r.device.type }
            if (Build.VERSION.SDK_INT >= 35) runCatching { x.addrType = r.device.addressType }
            if (Build.VERSION.SDK_INT >= 26) {
                x.connectable = r.isConnectable; x.legacy = r.isLegacy
                x.phy = listOfNotNull(phyName(r.primaryPhy), r.secondaryPhy.takeIf { it != 0 }?.let { phyName(it) }).joinToString(" / ")
                if (r.txPower != 127) x.txPower = r.txPower
                if (r.advertisingSid != 255) x.sid = r.advertisingSid
                if (r.periodicAdvertisingInterval != 0) x.periodic = r.periodicAdvertisingInterval * 1.25
            }
            rec?.txPowerLevel?.takeIf { it != Int.MIN_VALUE }?.let { x.txPower = it }
            rec?.bytes?.let { x.raw = trimAd(it) }
            x.stamps += r.timestampNanos / 1_000_000; while (x.stamps.size > 40) x.stamps.removeAt(0)
            x.dirty = true
        }
    }

    private fun phyName(p: Int) = when (p) { 1 -> "LE 1M"; 2 -> "LE 2M"; 3 -> "LE Coded"; else -> null }

    private fun trimAd(b: ByteArray): ByteArray {
        var o = 0
        while (o < b.size) { val l = b[o].toInt() and 0xFF; if (l == 0) break; o += 1 + l }
        return b.copyOf(minOf(o, b.size))
    }

    @SuppressLint("MissingPermission")
    fun onClassic(d: BluetoothDevice, rssi: Int?, name: String?, cod: Int?) {
        val now = System.currentTimeMillis()
        synchronized(map) {
            val x = t(d.address, now)
            x.classic = true; x.bondedOnly = false; x.lastSeen = now; if (rssi != null) { x.rssi = rssi; x.lastRssiAt = now }
            name?.let { x.name = it }; cod?.let { x.cod = it }
            runCatching { x.bond = d.bondState; x.type = d.type; d.name?.let { x.name = it } }
            if (Build.VERSION.SDK_INT >= 35) runCatching { x.addrType = d.addressType }
            x.dirty = true
        }
    }

    @SuppressLint("MissingPermission")
    fun addBonded(devs: Set<BluetoothDevice>?) {
        devs ?: return
        val now = System.currentTimeMillis()
        synchronized(map) {
            for (d in devs) {
                val x = map[d.address] ?: T(d.address, now).also { it.bondedOnly = true; map[d.address] = it }
                runCatching {
                    x.bond = d.bondState; x.type = d.type; d.name?.let { x.name = it }
                    if (x.cod == null) x.cod = d.bluetoothClass?.hashCode()
                    if (Build.VERSION.SDK_INT >= 35) x.addrType = d.addressType
                }
                x.dirty = true
            }
        }
    }

    fun tick(now: Long, intervalMs: Long, forgetMs: Long) {
        synchronized(map) {
            val it = map.values.iterator()
            while (it.hasNext()) {
                val x = it.next()
                if (!x.bondedOnly && now - x.lastSeen > forgetMs && x.bond != 12) { it.remove(); continue }
                val r = x.rssi
                if (r != null && now - x.lastRssiAt <= intervalMs + 500) { x.hist += Sample(now, r); while (x.hist.size > HISTORY) x.hist.removeAt(0); x.dirty = true }
                val inRange = !x.bondedOnly && now - x.lastSeen <= maxOf(intervalMs * 3, 15_000L)
                if (x.dirty || x.snap?.inRange != inRange) { x.snap = snapshot(x, inRange); x.dirty = false }
            }
            Repo.bt.value = map.values.mapNotNull { it.snap }
        }
    }

    private fun advInterval(st: List<Long>): Double? {
        if (st.size < 6) return null
        val d = st.zipWithNext { a, b -> b - a }.filter { it in 5..20_000 }.sorted()
        if (d.size < 4) return null
        return d[d.size / 4].toDouble()
    }

    private fun snapshot(x: T, inRange: Boolean): BtDevice {
        val fields = BtParse.fields(x.raw)
        val manu = ArrayList<ManuData>(); val svcData = ArrayList<SvcData>(); val svcs = ArrayList<String>()
        var measured: Int? = null; var tag: String? = null; var appearance: String? = null; var flags: String? = null
        val raw = x.raw
        if (raw != null) {
            var o = 0
            while (o < raw.size) {
                val l = raw[o].toInt() and 0xFF; if (l == 0 || o + 1 + l > raw.size) break
                val t = raw[o + 1].toInt() and 0xFF; val v = raw.copyOfRange(o + 2, o + 1 + l)
                runCatching {
                    when (t) {
                        0x01 -> flags = BtParse.flags(v[0].toInt() and 0xFF)
                        0x02, 0x03 -> for (i in 0 until v.size / 2) svcs += BtParse.uuid16Label((v[2 * i].toInt() and 0xFF) or ((v[2 * i + 1].toInt() and 0xFF) shl 8))
                        0x06, 0x07 -> for (i in 0 until v.size / 16) { val bb = java.nio.ByteBuffer.wrap(v.copyOfRange(16 * i, 16 * i + 16).reversedArray()); svcs += BtParse.uuidLabel(java.util.UUID(bb.long, bb.long)) }
                        0x19 -> appearance = BtParse.appearance((v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8))
                        0xFF -> if (v.size >= 2) {
                            val id = (v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8); val d = v.copyOfRange(2, v.size)
                            val dec = BtParse.decodeManufacturer(id, d)
                            manu += ManuData(id, Lookup.company(id) ?: "0x%04X".format(id), BtParse.hexSp(d), dec)
                            if (dec != null && tag == null) tag = dec.title
                            if (id == 0x004C && d.size >= 23 && d[0].toInt() == 2) measured = d[22].toInt()
                        }
                        0x16 -> if (v.size >= 2) {
                            val u = (v[0].toInt() and 0xFF) or ((v[1].toInt() and 0xFF) shl 8); val d = v.copyOfRange(2, v.size)
                            val dec = BtParse.decodeService(u, d)
                            svcData += SvcData(BtParse.uuid16Label(u), BtParse.hexSp(d), dec)
                            if (dec != null && (tag == null || tag!!.startsWith("Apple"))) tag = dec.title
                            if (u == 0xFEAA && d.size >= 2 && (d[0].toInt() == 0 || d[0].toInt() == 0x10)) measured = d[1].toInt() - 41
                        }
                    }
                }
                o += 1 + l
            }
        }
        val vendor = (if (x.addrType == 0 || (x.addrType == null && x.classic)) Lookup.vendor(x.addr) else null) ?: manu.firstOrNull()?.company
        val isRandom = x.addrType == 1
        val type = when (x.type) { 1 -> "Classic (BR/EDR)"; 2 -> "Low Energy"; 3 -> "Dual mode"; else -> if (x.classic) "Classic" else if (x.ble) "Low Energy" else "Unknown" }
        val bond = when (x.bond) { 12 -> "Paired"; 11 -> "Pairing…"; else -> "Not paired" }
        return BtDevice(
            address = x.addr, name = x.name, vendor = vendor, addressKind = BtParse.addressKind(x.addr, x.addrType), isRandom = isRandom,
            ble = x.ble, classic = x.classic, rssi = if (inRange) x.rssi else null, history = x.hist.toList(), txPower = x.txPower, measuredPower = measured,
            cod = x.cod, deviceType = (BtParse.majorShort(x.cod)?.let { "$type · $it" } ?: type), bond = bond, connectable = x.connectable, legacy = x.legacy,
            phy = x.phy, advIntervalMs = advInterval(x.stamps), packets = x.packets, services = svcs.distinct(), serviceData = svcData, manufacturer = manu,
            appearance = appearance, flags = flags, raw = BtParse.hexSp(raw), fields = fields, firstSeen = x.first, lastSeen = x.lastSeen, inRange = inRange,
            tag = tag ?: BtParse.majorShort(x.cod)?.takeIf { it != "Uncategorized" && it != "Miscellaneous" }, advSid = x.sid, periodicIntervalMs = x.periodic,
        )
    }
}
