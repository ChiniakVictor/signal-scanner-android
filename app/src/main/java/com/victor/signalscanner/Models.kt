package com.victor.signalscanner

import android.net.wifi.ScanResult

data class Sample(val t: Long, val rssi: Int)

data class WifiAp(
    val bssid: String,
    val ssid: String,
    val hidden: Boolean,
    val vendor: String?,
    val bssidRandom: Boolean,
    val rssi: Int,
    val freq: Int,
    val band: String,
    val channel: Int,
    val widthMhz: Int,
    val widthName: String,
    val center0: Int,
    val center1: Int,
    val standard: Int,
    val standardName: String,
    val security: String,
    val capabilities: String,
    val mcResponder: Boolean,
    val azResponder: Boolean,
    val passpoint: Boolean,
    val operatorName: String?,
    val venueName: String?,
    val ie: IeInfo?,
    val firstSeen: Long,
    val lastSeen: Long,
    val resultTimeMs: Long,      // wall-clock time the radio actually heard this AP
    val history: List<Sample>,
    val seenCount: Int,
    val connected: Boolean,
    val inRange: Boolean,
    val mldMac: String?,
    val raw: ScanResult?,
) {
    val distanceM get() = WifiParse.distanceM(rssi, freq)
    val name get() = if (hidden) "(hidden network)" else ssid
    val shortStd get() = WifiParse.shortStandard(standard, freq)
    val maxRate get() = WifiParse.maxRate(standard, widthMhz, ie?.streams ?: 1)
}

data class ScanEvent(val time: Long, val requested: Boolean, val accepted: Boolean, val updated: Boolean?, val count: Int)

data class WifiState(
    val supported: Boolean = true,
    val enabled: Boolean = false,
    val locationOn: Boolean = true,
    val scanAlwaysAvailable: Boolean = false,
    val lastRequest: Long = 0,
    val lastRequestAccepted: Boolean? = null,
    val lastResults: Long = 0,
    val lastUpdated: Boolean? = null,
    val newestResultMs: Long = 0,   // wall time of the freshest AP observation
    val throttleSetting: String = "unknown",
    val throttleInferred: String = "",
    val events: List<ScanEvent> = emptyList(),
    val caps: List<Pair<String, String>> = emptyList(),
    val error: String? = null,
)

data class ConnInfo(
    val ssid: String?,
    val bssid: String?,
    val rssi: Int,
    val linkMbps: Int,
    val txMbps: Int,
    val rxMbps: Int,
    val maxTxMbps: Int,
    val maxRxMbps: Int,
    val freq: Int,
    val standard: Int,
    val security: String?,
    val ipv4: List<String>,
    val ipv6: List<String>,
    val gateway: List<String>,
    val dns: List<String>,
    val privateDns: String?,
    val dhcpServer: String?,
    val domains: String?,
    val mtu: Int,
    val iface: String?,
    val mac: String?,
    val macNote: String,
    val hiddenSsid: Boolean,
    val passpointFqdn: String?,
    val mldMac: String?,
    val mloLinks: List<String>,
    val downKbps: Int,
    val upKbps: Int,
    val validated: Boolean,
    val metered: Boolean,
    val networkId: Int,
)

data class ManuData(val id: Int, val company: String, val hex: String, val decoded: Decoded?)
data class SvcData(val uuid: String, val hex: String, val decoded: Decoded?)

data class BtDevice(
    val address: String,
    val name: String?,
    val vendor: String?,
    val addressKind: String,
    val isRandom: Boolean,
    val ble: Boolean,
    val classic: Boolean,
    val rssi: Int?,
    val history: List<Sample>,
    val txPower: Int?,           // advertised tx power level (at 0 m)
    val measuredPower: Int?,     // iBeacon/Eddystone calibrated power at 1 m / 0 m
    val cod: Int?,
    val deviceType: String,
    val bond: String,
    val connectable: Boolean?,
    val legacy: Boolean?,
    val phy: String?,
    val advIntervalMs: Double?,
    val packets: Int,
    val services: List<String>,
    val serviceData: List<SvcData>,
    val manufacturer: List<ManuData>,
    val appearance: String?,
    val flags: String?,
    val raw: String,
    val fields: List<AdField>,
    val firstSeen: Long,
    val lastSeen: Long,
    val inRange: Boolean,
    val tag: String?,            // short decoded label, e.g. "iBeacon", "AirPods"
    val advSid: Int?,
    val periodicIntervalMs: Double?,
) {
    val displayName get() = name ?: tag ?: vendor ?: "Unnamed device"
    val distanceM: Double? get() {
        val r = rssi ?: return null
        val at1m = measuredPower ?: txPower?.let { it - 41 } ?: -59
        return BtParse.distanceM(r, at1m)
    }
    val kind get() = when { ble && classic -> "Dual"; classic -> "Classic"; ble -> "BLE"; else -> "Paired" }
}

data class BtState(
    val supported: Boolean = true,
    val leSupported: Boolean = true,
    val enabled: Boolean = false,
    val bleScanning: Boolean = false,
    val discovering: Boolean = false,
    val error: String? = null,
    val adapterName: String? = null,
    val caps: List<Pair<String, String>> = emptyList(),
    val lastTick: Long = 0,
)

data class NfcState(
    val supported: Boolean = false,
    val enabled: Boolean = false,
    val secureSupported: Boolean = false,
    val secureEnabled: Boolean = false,
    val readerActive: Boolean = false,
    val hce: Boolean = false,
    val note: String? = null,
)

data class Radios(val wifi: Boolean, val bt: Boolean, val ble: Boolean, val nfc: Boolean, val wifiAware: Boolean, val rtt: Boolean, val uwb: Boolean)
