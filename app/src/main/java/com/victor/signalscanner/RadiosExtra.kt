package com.victor.signalscanner

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareSession
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Extra sections for the Radios tab: Wi-Fi Aware attach test, UWB, RTT, environment sensors, and the full sensor list. */
@SuppressLint("MissingPermission")
@Composable
fun RadiosExtra() {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    var awareStatus by remember { mutableStateOf("—") }
    var awareTry by remember { mutableIntStateOf(0) }
    val awm = remember { if (pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) ctx.getSystemService(WifiAwareManager::class.java) else null }
    LaunchedEffect(awareTry) {
        if (awm == null) { awareStatus = "not supported"; return@LaunchedEffect }
        if (!awm.isAvailable) { awareStatus = "supported but unavailable right now (Wi-Fi off, hotspot or another Aware/Direct session active)"; return@LaunchedEffect }
        awareStatus = "attaching…"
        runCatching {
            awm.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) { awareStatus = "attach OK – a NAN session can be created (closed again right away)"; session.close() }
                override fun onAttachFailed() { awareStatus = "attach failed" }
            }, Handler(Looper.getMainLooper()))
        }.onFailure { awareStatus = "attach error: ${it.message}" }
    }
    val awareChars = remember { if (Build.VERSION.SDK_INT >= 29) runCatching { awm?.characteristics?.let { c ->
        listOfNotNull("max service name ${c.maxServiceNameLength} B", "max specific info ${c.maxServiceSpecificInfoLength} B", "max match filter ${c.maxMatchFilterLength} B",
            if (Build.VERSION.SDK_INT >= 31) "instant comm mode ${if (c.isInstantCommunicationModeSupported) "yes" else "no"}" else null).joinToString(" · ") } }.getOrNull() else null }
    val rtt = remember { if (pm.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) ctx.getSystemService(WifiRttManager::class.java) else null }
    val uwb = pm.hasSystemFeature("android.hardware.uwb")

    Section("Wi-Fi Aware (NAN)") {
        KV("Hardware", if (awm != null) "present" else "not present")
        KV("Status", awareStatus)
        KV("Capabilities", awareChars)
        if (awm != null) TextButton(onClick = { awareTry++ }) { Text("Test attach again") }
    }
    Section("Wi-Fi RTT / Ultra-wideband") {
        KV("Wi-Fi RTT (802.11mc FTM)", if (rtt == null) "not supported" else if (rtt.isAvailable) "available – open an 802.11mc AP in Wi-Fi to range it" else "supported, currently unavailable (Wi-Fi or Location off)")
        KV("UWB hardware", if (uwb) "present" else "not present")
        if (uwb) KV("UWB note", "Android doesn't let apps scan for UWB devices or read the chip state; UWB ranging only works inside a session with a paired accessory (e.g. SmartTag2 / car keys).")
    }

    // live environment sensors, only while this tab is shown
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    var pressure by remember { mutableStateOf<Float?>(null) }
    var lux by remember { mutableStateOf<Float?>(null) }
    var prox by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) {
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { when (e.sensor.type) { Sensor.TYPE_PRESSURE -> pressure = e.values[0]; Sensor.TYPE_LIGHT -> lux = e.values[0]; Sensor.TYPE_PROXIMITY -> prox = e.values[0] } }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        listOf(Sensor.TYPE_PRESSURE, Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY).forEach { t -> sm.getDefaultSensor(t)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_NORMAL) } }
        onDispose { sm.unregisterListener(l) }
    }
    val proxSensor = remember { sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) }
    Section("Environment") {
        KV("Barometer", pressure?.let { "%.1f hPa · ≈ %.0f m altitude (standard atmosphere)".format(it, SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, it)) }
            ?: if (sm.getDefaultSensor(Sensor.TYPE_PRESSURE) == null) "no barometer" else "waiting…")
        KV("Light", lux?.let { "%.0f lux".format(it) } ?: "—")
        KV("Proximity", prox?.let { if (proxSensor != null && it < proxSensor.maximumRange) "near (%.0f cm)".format(it) else "far" } ?: "—")
    }

    val sensors = remember { sm.getSensorList(Sensor.TYPE_ALL).sortedBy { it.stringType } }
    Section("All sensors (${sensors.size})") {
        sensors.forEach { s ->
            val rate = if (s.minDelay > 0) "%.0f Hz max".format(1e6 / s.minDelay) else if (s.minDelay == 0) "on change" else "one-shot"
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(s.name, fontSize = 13.sp)
                Text("${s.stringType.removePrefix("android.sensor.")} · ${s.vendor} · v${s.version} · range %s · res %s · %.2f mA · %s%s".format(
                    fmtNum(s.maximumRange), fmtNum(s.resolution), s.power, rate, if (s.isWakeUpSensor) " · wake-up" else ""), color = Muted, fontSize = 11.sp)
            }
        }
    }
}

private fun fmtNum(v: Float) = if (v == 0f) "0" else if (abs(v) >= 100) "%.0f".format(v) else if (abs(v) >= 1) "%.2f".format(v) else "%.4f".format(v)
private fun abs(v: Float) = kotlin.math.abs(v)
