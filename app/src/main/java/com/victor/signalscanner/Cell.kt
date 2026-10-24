package com.victor.signalscanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.coroutines.resume

data class CellSample(val t: Long, val main: Int?, val q: Int?, val sinr: Double?)

data class CellEntry(
    val key: String,
    val tech: String,
    val registered: Boolean,
    val role: String,               // Serving (primary) / Secondary (CA/NSA) / Neighbor
    val mcc: String?, val mnc: String?, val operator: String?,
    val area: String?,              // TAC / LAC
    val cellId: String?,            // CI / NCI / CID
    val enb: String?,               // eNB / gNB + sector, when derivable
    val pci: Int?,
    val arfcn: Int?, val arfcnName: String,
    val bands: String?, val freqMhz: Double?, val bandwidthMhz: Double?,
    val mainName: String, val main: Int?,    // RSRP / SS-RSRP / RSCP / RSSI
    val qName: String, val q: Int?,          // RSRQ / Ec/No
    val sinrName: String, val sinr: Double?,
    val rssi: Int?, val cqi: Int?, val ta: Int?, val level: Int,
    val extra: List<Pair<String, String>>,
    val history: List<CellSample>,
    val lastSeen: Long,
)

data class CellState(
    val supported: Boolean = true,
    val permission: Boolean = true,
    val operator: String? = null,
    val simOperator: String? = null,
    val dataType: String? = null,
    val voiceType: String? = null,
    val displayType: String? = null,     // e.g. 5G NSA, LTE-A (CA), 5G+ (NR advanced)
    val nrState: String? = null,
    val carrierAggregation: String? = null,
    val bandwidths: String? = null,
    val roaming: Boolean = false,
    val sims: List<Pair<String, String>> = emptyList(),
    val lastUpdate: Long = 0,
    val error: String? = null,
)

object CellRepo {
    val cells = MutableStateFlow<List<CellEntry>>(emptyList())
    val state = MutableStateFlow(CellState())
}

class CellScanner(private val ctx: Context) {
    private val tm = ctx.getSystemService(TelephonyManager::class.java)
    private val hist = HashMap<String, ArrayList<CellSample>>()
    private var displayCb: Any? = null
    @Volatile private var displayText: String? = null

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    private fun ok(v: Int) = v != CellInfo.UNAVAILABLE && v != Int.MAX_VALUE
    private fun okL(v: Long) = v != CellInfo.UNAVAILABLE_LONG && v != Long.MAX_VALUE

    suspend fun loop(intervalMs: () -> Long) {
        if (tm == null || !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) { CellRepo.state.value = CellState(supported = false); return }
        registerDisplay()
        try {
            while (currentCoroutineContext().isActive) {
                runCatching { poll() }.onFailure { CellRepo.state.value = CellRepo.state.value.copy(error = it.message) }
                delay(intervalMs())
            }
        } finally { unregisterDisplay() }
    }

    @SuppressLint("MissingPermission")
    private fun registerDisplay() {
        if (Build.VERSION.SDK_INT < 31 || !has(Manifest.permission.READ_PHONE_STATE)) return
        val cb = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
            override fun onDisplayInfoChanged(d: TelephonyDisplayInfo) {
                displayText = when (d.overrideNetworkType) {
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA -> "LTE-A (carrier aggregation)"
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE Advanced Pro (5Ge)"
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA -> "5G NSA (LTE anchor + NR)"
                    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G+ / UW (NR advanced, mmWave or wide C-band)"
                    else -> if (netName(d.networkType) == "NR") "5G SA (standalone)" else netName(d.networkType)
                }
            }
        }
        runCatching { tm.registerTelephonyCallback(ContextCompat.getMainExecutor(ctx), cb); displayCb = cb }
    }

    private fun unregisterDisplay() {
        if (Build.VERSION.SDK_INT >= 31) (displayCb as? TelephonyCallback)?.let { runCatching { tm.unregisterTelephonyCallback(it) } }
        displayCb = null
    }

    @SuppressLint("MissingPermission")
    private suspend fun fresh(): List<CellInfo> {
        if (Build.VERSION.SDK_INT >= 29) {
            val r = withTimeoutOrNull(4000) {
                suspendCancellableCoroutine<List<CellInfo>?> { c ->
                    runCatching {
                        tm.requestCellInfoUpdate(ContextCompat.getMainExecutor(ctx), object : TelephonyManager.CellInfoCallback() {
                            override fun onCellInfo(cells: MutableList<CellInfo>) { if (c.isActive) c.resume(cells) }
                            override fun onError(errorCode: Int, detail: Throwable?) { if (c.isActive) c.resume(null) }
                        })
                    }.onFailure { if (c.isActive) c.resume(null) }
                }
            }
            if (r != null) return r
        }
        return tm.allCellInfo ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    private suspend fun poll() {
        val perm = has(Manifest.permission.ACCESS_FINE_LOCATION)
        val phone = has(Manifest.permission.READ_PHONE_STATE)
        if (!perm) { CellRepo.state.value = CellState(permission = false, error = "Needs precise location permission"); return }
        val now = System.currentTimeMillis()
        val infos = fresh()
        val out = ArrayList<CellEntry>()
        for (ci in infos) runCatching { parse(ci, now)?.let { out += it } }
        // de-duplicate by key, prefer registered
        val uniq = out.groupBy { it.key }.map { (_, v) -> v.maxByOrNull { if (it.registered) 1 else 0 }!! }
        synchronized(hist) {
            for (c in uniq) {
                val h = hist.getOrPut(c.key) { ArrayList() }
                h += CellSample(now, c.main, c.q, c.sinr); while (h.size > 180) h.removeAt(0)
            }
            hist.keys.retainAll(uniq.map { it.key }.toSet() + hist.filterValues { now - (it.lastOrNull()?.t ?: 0) < 10 * 60_000 }.keys)
        }
        CellRepo.cells.value = uniq.map { it.copy(history = synchronized(hist) { hist[it.key]?.toList() ?: emptyList() }) }
            .sortedWith(compareBy({ it.role != "Serving" }, { it.role != "Secondary" }, { -(it.main ?: -999) }))

        val ss: ServiceState? = if (phone && Build.VERSION.SDK_INT >= 26) runCatching { tm.serviceState }.getOrNull() else null
        val sstr = ss?.toString() ?: ""
        val nrState = Regex("nrState=(\\w+)").find(sstr)?.groupValues?.get(1)
        val caFlag = Regex("isUsingCarrierAggregation=(\\w+)").find(sstr)?.groupValues?.get(1)
        val bws = if (Build.VERSION.SDK_INT >= 28) ss?.cellBandwidths?.toList() ?: emptyList() else emptyList()
        val sims = ArrayList<Pair<String, String>>()
        if (phone) runCatching {
            val sm = ctx.getSystemService(SubscriptionManager::class.java)
            sm?.activeSubscriptionInfoList?.forEach { s ->
                sims += "SIM ${s.simSlotIndex + 1}" to listOfNotNull(s.carrierName?.toString(), s.displayName?.toString()?.takeIf { it != s.carrierName?.toString() },
                    if (Build.VERSION.SDK_INT >= 29) "${s.mccString}-${s.mncString}" else null, s.countryIso?.uppercase(), if (s.isEmbedded) "eSIM" else "physical SIM").joinToString(" · ")
            }
        }
        CellRepo.state.value = CellState(
            operator = tm.networkOperatorName?.takeIf { it.isNotBlank() }?.let { "$it (${tm.networkOperator})" },
            simOperator = tm.simOperatorName?.takeIf { it.isNotBlank() }?.let { n -> n + (if (Build.VERSION.SDK_INT >= 28) tm.simCarrierIdName?.toString()?.takeIf { it != n }?.let { " / $it" } ?: "" else "") },
            dataType = if (phone) netName(tm.dataNetworkType) else "needs Phone permission",
            voiceType = if (phone) netName(tm.voiceNetworkType) else null,
            displayType = displayText,
            nrState = nrState?.let { when (it) { "CONNECTED" -> "connected (NSA, 5G in use)"; "NOT_RESTRICTED" -> "available (NSA anchor, not connected)"; "RESTRICTED" -> "restricted"; "NONE" -> "none"; else -> it.lowercase() } },
            carrierAggregation = when { bws.size > 1 -> "yes – ${bws.size} carriers"; caFlag == "true" -> "yes"; caFlag == "false" -> "no"; else -> null },
            bandwidths = bws.takeIf { it.isNotEmpty() }?.joinToString(" + ") { "${it / 1000} MHz" },
            roaming = tm.isNetworkRoaming, sims = sims, lastUpdate = now,
            error = if (!phone) "Grant Phone permission for network type, SIM and 5G status" else null,
        )
    }

    private fun parse(ci: CellInfo, now: Long): CellEntry? {
        val reg = ci.isRegistered
        val role = when {
            Build.VERSION.SDK_INT >= 28 && ci.cellConnectionStatus == CellInfo.CONNECTION_PRIMARY_SERVING -> "Serving"
            Build.VERSION.SDK_INT >= 28 && ci.cellConnectionStatus == CellInfo.CONNECTION_SECONDARY_SERVING -> "Secondary"
            reg -> "Serving"
            else -> "Neighbor"
        }
        fun opName(id: CellIdentity) = listOfNotNull(id.operatorAlphaLong?.toString(), id.operatorAlphaShort?.toString()).firstOrNull()
        return when (ci) {
            is CellInfoLte -> {
                val id = ci.cellIdentity; val s = ci.cellSignalStrength
                val earfcn = id.earfcn.takeIf { ok(it) }
                val bands = (if (Build.VERSION.SDK_INT >= 30) id.bands.toList() else emptyList()).ifEmpty { earfcn?.let { CellBands.lteBand(it) }?.let { listOf(it) } ?: emptyList() }
                val ciV = id.ci.takeIf { ok(it) }
                val extra = ArrayList<Pair<String, String>>()
                if (Build.VERSION.SDK_INT >= 30) id.additionalPlmns.takeIf { it.isNotEmpty() }?.let { extra += "Other PLMNs" to it.joinToString(", ") }
                if (Build.VERSION.SDK_INT >= 28) id.bandwidth.takeIf { ok(it) }?.let { extra += "Bandwidth" to "${it / 1000.0} MHz" }
                extra += "Signal level" to "${s.level}/4"
                s.dbm.takeIf { ok(it) }?.let { extra += "dBm" to "$it" }
                CellEntry(
                    key = "LTE-${id.pci}-$earfcn", tech = "LTE", registered = reg, role = role,
                    mcc = id.mccString, mnc = id.mncString, operator = opName(id),
                    area = id.tac.takeIf { ok(it) }?.let { "TAC $it" }, cellId = ciV?.let { "CI $it" },
                    enb = ciV?.let { "eNB ${it shr 8} · sector ${it and 0xFF}" },
                    pci = id.pci.takeIf { ok(it) }, arfcn = earfcn, arfcnName = "EARFCN",
                    bands = bands.joinToString(", ") { "B$it" }.ifEmpty { null }, freqMhz = earfcn?.let { CellBands.lteFreq(it) },
                    bandwidthMhz = if (Build.VERSION.SDK_INT >= 28) id.bandwidth.takeIf { ok(it) }?.div(1000.0) else null,
                    mainName = "RSRP", main = s.rsrp.takeIf { ok(it) }, qName = "RSRQ", q = s.rsrq.takeIf { ok(it) },
                    sinrName = "RSSNR", sinr = s.rssnr.takeIf { ok(it) }?.toDouble(),
                    rssi = if (Build.VERSION.SDK_INT >= 29) s.rssi.takeIf { ok(it) } else null, cqi = s.cqi.takeIf { ok(it) }, ta = s.timingAdvance.takeIf { ok(it) },
                    level = s.level, extra = extra, history = emptyList(), lastSeen = now,
                )
            }
            is CellInfoNr -> {
                val id = ci.cellIdentity as CellIdentityNr; val s = ci.cellSignalStrength as CellSignalStrengthNr
                val arfcn = id.nrarfcn.takeIf { ok(it) }
                val nci = id.nci.takeIf { okL(it) }
                val extra = ArrayList<Pair<String, String>>()
                s.csiRsrp.takeIf { ok(it) }?.let { extra += "CSI-RSRP" to "$it dBm" }
                s.csiRsrq.takeIf { ok(it) }?.let { extra += "CSI-RSRQ" to "$it dB" }
                s.csiSinr.takeIf { ok(it) }?.let { extra += "CSI-SINR" to "$it dB" }
                if (Build.VERSION.SDK_INT >= 31) s.csiCqiReport.takeIf { it.isNotEmpty() }?.let { extra += "CSI CQI" to it.joinToString(",") }
                extra += "Signal level" to "${s.level}/4"
                val band = (if (Build.VERSION.SDK_INT >= 30) id.bands.toList() else emptyList())
                CellEntry(
                    key = "NR-${id.pci}-$arfcn", tech = "5G NR", registered = reg, role = role,
                    mcc = id.mccString, mnc = id.mncString, operator = opName(id),
                    area = id.tac.takeIf { ok(it) }?.let { "TAC $it" }, cellId = nci?.let { "NCI $it" },
                    enb = nci?.let { "gNB ${it shr 12} (if 24-bit gNB ID) · cell ${it and 0xFFF}" },
                    pci = id.pci.takeIf { ok(it) }, arfcn = arfcn, arfcnName = "NR-ARFCN",
                    bands = band.joinToString(", ") { "n$it" }.ifEmpty { arfcn?.let { CellBands.nrBandGuess(it) } }, freqMhz = arfcn?.let { CellBands.nrFreq(it) }, bandwidthMhz = null,
                    mainName = "SS-RSRP", main = s.ssRsrp.takeIf { ok(it) }, qName = "SS-RSRQ", q = s.ssRsrq.takeIf { ok(it) },
                    sinrName = "SS-SINR", sinr = s.ssSinr.takeIf { ok(it) }?.toDouble(), rssi = null, cqi = null,
                    ta = if (Build.VERSION.SDK_INT >= 34) s.timingAdvanceMicros.takeIf { ok(it) } else null,
                    level = s.level, extra = extra, history = emptyList(), lastSeen = now,
                )
            }
            is CellInfoWcdma -> {
                val id = ci.cellIdentity; val s = ci.cellSignalStrength
                val extra = ArrayList<Pair<String, String>>()
                id.psc.takeIf { ok(it) }?.let { extra += "PSC" to "$it" }
                CellEntry("WCDMA-${id.psc}-${id.uarfcn}", "3G WCDMA", reg, role, id.mccString, id.mncString, opName(id),
                    id.lac.takeIf { ok(it) }?.let { "LAC $it" }, id.cid.takeIf { ok(it) }?.let { "CID $it (RNC ${it shr 16})" }, null,
                    id.psc.takeIf { ok(it) }, id.uarfcn.takeIf { ok(it) }, "UARFCN", null, null, 5.0,
                    "RSCP", s.dbm.takeIf { ok(it) }, "Ec/No", if (Build.VERSION.SDK_INT >= 30) s.ecNo.takeIf { ok(it) } else null, "SINR", null,
                    null, null, null, s.level, extra, emptyList(), now)
            }
            is CellInfoGsm -> {
                val id = ci.cellIdentity; val s = ci.cellSignalStrength
                val extra = ArrayList<Pair<String, String>>()
                id.bsic.takeIf { ok(it) }?.let { extra += "BSIC" to "$it" }
                CellEntry("GSM-${id.arfcn}-${id.bsic}", "2G GSM", reg, role, id.mccString, id.mncString, opName(id),
                    id.lac.takeIf { ok(it) }?.let { "LAC $it" }, id.cid.takeIf { ok(it) }?.let { "CID $it" }, null,
                    null, id.arfcn.takeIf { ok(it) }, "ARFCN", null, null, 0.2,
                    "RSSI", s.dbm.takeIf { ok(it) }, "", null, "", null, if (Build.VERSION.SDK_INT >= 30) s.rssi.takeIf { ok(it) } else null, null,
                    s.timingAdvance.takeIf { ok(it) }, s.level, extra, emptyList(), now)
            }
            else -> null
        }
    }

    companion object {
        fun netName(t: Int): String = when (t) {
            TelephonyManager.NETWORK_TYPE_NR -> "NR"; TelephonyManager.NETWORK_TYPE_LTE -> "LTE"; TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN (Wi-Fi calling)"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"; TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"; TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"; TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"; TelephonyManager.NETWORK_TYPE_GSM -> "GSM"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "none/unknown"; else -> "type $t"
        }
    }
}

/** EARFCN/NR-ARFCN → band and downlink frequency (3GPP 36.101 / 38.104). */
object CellBands {
    // band, FDL_low MHz, NOffs-DL, last earfcn
    private val lte = listOf(
        intArrayOf(1, 2110, 0, 599), intArrayOf(2, 1930, 600, 1199), intArrayOf(3, 1805, 1200, 1949), intArrayOf(4, 2110, 1950, 2399), intArrayOf(5, 869, 2400, 2649),
        intArrayOf(7, 2620, 2750, 3449), intArrayOf(8, 925, 3450, 3799), intArrayOf(12, 729, 5010, 5179), intArrayOf(13, 746, 5180, 5279), intArrayOf(14, 758, 5280, 5379),
        intArrayOf(17, 734, 5730, 5849), intArrayOf(20, 791, 6150, 6449), intArrayOf(25, 1930, 8040, 8689), intArrayOf(26, 859, 8690, 9039), intArrayOf(28, 758, 9210, 9659),
        intArrayOf(29, 717, 9660, 9769), intArrayOf(30, 2350, 9770, 9869), intArrayOf(38, 2570, 37750, 38249), intArrayOf(40, 2300, 38650, 39649), intArrayOf(41, 2496, 39650, 41589),
        intArrayOf(46, 5150, 46790, 54539), intArrayOf(48, 3550, 55240, 56739), intArrayOf(66, 2110, 66436, 67335), intArrayOf(71, 617, 68586, 68935),
    )
    fun lteBand(e: Int): Int? = lte.firstOrNull { e in it[2]..it[3] }?.get(0)
    fun lteFreq(e: Int): Double? = lte.firstOrNull { e in it[2]..it[3] }?.let { it[1] + 0.1 * (e - it[2]) }
    fun nrFreq(n: Int): Double = when {
        n < 600000 -> n * 0.005
        n < 2016667 -> 3000 + (n - 600000) * 0.015
        else -> 24250.08 + (n - 2016667) * 0.06
    }
    fun nrBandGuess(n: Int): String? {
        val f = nrFreq(n)
        return when {
            f in 617.0..652.0 -> "n71"; f in 729.0..746.0 -> "n12/n29"; f in 758.0..768.0 -> "n14/n28"; f in 869.0..894.0 -> "n5"
            f in 1930.0..1995.0 -> "n2/n25"; f in 2110.0..2200.0 -> "n66"; f in 2496.0..2690.0 -> "n41"; f in 3450.0..3550.0 -> "n77 (3.45 GHz)"
            f in 3550.0..3700.0 -> "n48/n77"; f in 3700.0..3980.0 -> "n77 (C-band)"; f in 26500.0..29500.0 -> "n257/n261 (mmWave)"; f in 37000.0..40000.0 -> "n260 (mmWave)"
            else -> null
        }
    }
}
