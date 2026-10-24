package com.victor.signalscanner

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.*

/** Pure helpers (unit-tested). */
object Dsp {
    /** Goertzel power of frequency f in samples x (rate fs), normalized to amplitude. */
    fun toneAmplitude(x: DoubleArray, fs: Double, f: Double): Double {
        if (x.isEmpty()) return 0.0
        val w = 2 * PI * f / fs; val c = 2 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (v in x) { val s0 = v + c * s1 - s2; s2 = s1; s1 = s0 }
        val p = s1 * s1 + s2 * s2 - c * s1 * s2
        return 2 * sqrt(max(p, 0.0)) / x.size
    }
    /** Where a tone of frequency f shows up when sampled at fs. */
    fun alias(f: Double, fs: Double): Double { val r = f % fs; return if (r > fs / 2) fs - r else r }
    fun detrend(x: DoubleArray): DoubleArray { val m = x.average(); return DoubleArray(x.size) { x[it] - m } }

    /** In-place radix-2 FFT. */
    fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var cr = 1f; var ci = 0f
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci; val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti
                    val ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

private class MagBuf {
    val t = LongArray(8192); val x = FloatArray(8192); val y = FloatArray(8192); val z = FloatArray(8192); var n = 0L
    fun add(ts: Long, a: Float, b: Float, c: Float) { val i = (n % 8192).toInt(); t[i] = ts; x[i] = a; y[i] = b; z[i] = c; n++ }
    fun last(k: Int): List<Int> { val m = min(k.toLong(), n).toInt(); return (0 until m).map { ((n - m + it) % 8192).toInt() } }
}

data class MagUi(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f, val mag: Float = 0f, val dev: Float = 0f, val rate: Double = 0.0,
                 val hum: String = "collecting…", val hist: List<Sample> = emptyList(), val baseline: Triple<Float, Float, Float>? = null)

@Composable
fun MagnetTab() {
    val ctx = LocalContext.current
    val sm = remember { ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val sensor = remember { sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) }
    val uncal = remember { sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED) }
    if (sensor == null) { Text("No magnetometer on this device", color = Muted, modifier = Modifier.padding(20.dp)); return }
    val buf = remember { MagBuf() }
    var ui by remember { mutableStateOf(MagUi()) }
    var baseline by remember { mutableStateOf<Triple<Float, Float, Float>?>(null) }
    var beep by remember { mutableStateOf(false) }
    var vibe by remember { mutableStateOf(false) }
    var accuracy by remember { mutableIntStateOf(-1) }
    val hist = remember { ArrayList<Sample>() }
    val cal by TestHooks.calibrate.collectAsState()
    LaunchedEffect(cal, ui.mag) { if (cal && ui.mag > 0) { baseline = Triple(ui.x, ui.y, ui.z); TestHooks.calibrate.value = false } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { synchronized(buf) { buf.add(e.timestamp, e.values[0], e.values[1], e.values[2]) } }
            override fun onAccuracyChanged(s: Sensor?, a: Int) { accuracy = a }
        }
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_START) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_FASTEST)
            if (ev == Lifecycle.Event.ON_STOP) sm.unregisterListener(l)
        }
        owner.lifecycle.addObserver(obs)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        onDispose { owner.lifecycle.removeObserver(obs); sm.unregisterListener(l) }
    }
    // UI/analysis tick at 10 Hz; beeps/vibration only while this tab is shown
    LaunchedEffect(Unit) {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 60) }.getOrNull()
        val vib = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
        var lastBeep = 0L
        try {
            while (true) {
                kotlinx.coroutines.delay(100)
                val idx: List<Int>; val ts: LongArray; val xs: FloatArray; val ys: FloatArray; val zs: FloatArray
                synchronized(buf) { idx = buf.last(1024); ts = LongArray(idx.size) { buf.t[idx[it]] }; xs = FloatArray(idx.size) { buf.x[idx[it]] }; ys = FloatArray(idx.size) { buf.y[idx[it]] }; zs = FloatArray(idx.size) { buf.z[idx[it]] } }
                if (idx.size < 4) continue
                val k = idx.size - 1
                val mag = sqrt(xs[k] * xs[k] + ys[k] * ys[k] + zs[k] * zs[k])
                val span = (ts[k] - ts[0]) / 1e9
                val rate = if (span > 0) k / span else 0.0
                val b = baseline
                val dev = if (b != null) sqrt((xs[k] - b.first).pow(2) + (ys[k] - b.second).pow(2) + (zs[k] - b.third).pow(2)) else 0f
                val now = System.currentTimeMillis()
                hist += Sample(now, mag.roundToInt()); while (hist.isNotEmpty() && hist[0].t < now - 60_000) hist.removeAt(0)
                // AC hum: magnitude series over the last ~1-2 s
                val hum = run {
                    val n = idx.size
                    if (rate < 20 || n < 64) return@run "collecting…"
                    val m = DoubleArray(n) { sqrt((xs[it] * xs[it] + ys[it] * ys[it] + zs[it] * zs[it]).toDouble()) }.let { Dsp.detrend(it) }
                    val rms = sqrt(m.sumOf { it * it } / n)
                    val parts = listOf(50.0, 60.0).map { f ->
                        val fa = Dsp.alias(f, rate); val a = Dsp.toneAmplitude(m, rate, fa)
                        Triple(f, fa, a)
                    }
                    val best = parts.maxBy { it.third }
                    val aliasNote = if (rate < 2 * best.first + 5) " (aliased to %.0f Hz at %.0f Hz sampling)".format(best.second, rate) else ""
                    if (best.third > 0.05 && best.third > rms * 0.6) "%.0f Hz hum detected · %.2f µT%s".format(best.first, best.third, aliasNote)
                    else "no clear 50/60 Hz hum (noise %.2f µT rms)%s".format(rms, if (rate < 125) " · sensor runs at %.0f Hz, so mains hum can only be seen aliased".format(rate) else "")
                }
                ui = MagUi(xs[k], ys[k], zs[k], mag, dev, rate, hum, hist.toList(), b)
                if ((beep || vibe) && b != null && dev > 5f) {
                    val gap = (1200 / (1 + dev / 10f)).toLong().coerceIn(60, 1200)
                    if (now - lastBeep > gap) {
                        lastBeep = now
                        if (beep) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
                        if (vibe && Build.VERSION.SDK_INT >= 26) vib?.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                }
            }
        } finally { tone?.release() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("%.1f".format(ui.mag), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Cyan)
            Text(" µT", fontSize = 20.sp, color = Muted, modifier = Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.weight(1f))
            Text("X %.1f\nY %.1f\nZ %.1f".format(ui.x, ui.y, ui.z), fontSize = 13.sp, color = Muted)
        }
        Text("Earth's field is about 25–65 µT. Metal, magnets, speakers and wiring change it; move the phone slowly – the sensor is near the top of the phone.", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        val dev = ui.dev
        val dc = when { baseline == null -> Muted; dev < 5 -> Green; dev < 20 -> Amber; dev < 60 -> Orange; else -> Red }
        Text(if (baseline == null) "Deviation: tap Calibrate away from metal first" else "Deviation from baseline: %.1f µT".format(dev), fontWeight = FontWeight.SemiBold, color = dc)
        Box(Modifier.fillMaxWidth().height(18.dp).padding(vertical = 3.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x22FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((dev / 100f).coerceIn(0f, 1f)).background(dc))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { baseline = Triple(ui.x, ui.y, ui.z) }) { Text(if (baseline == null) "Calibrate" else "Re-calibrate") }
            if (baseline != null) TextButton(onClick = { baseline = null }) { Text("Clear") }
            Spacer(Modifier.width(8.dp)); Checkbox(beep, { beep = it }); Text("Beep", fontSize = 13.sp); Checkbox(vibe, { vibe = it }); Text("Vibrate", fontSize = 13.sp)
        }
        Text("Last 60 s (µT)", color = Muted, fontSize = 12.sp)
        val hi = max(100, (ui.hist.maxOfOrNull { it.rssi } ?: 0) + 10); val lo = max(0, min(0, (ui.hist.minOfOrNull { it.rssi } ?: 0) - 10))
        Sparkline(ui.hist, Modifier.fillMaxWidth().height(140.dp), color = Cyan, axes = true, windowMs = 60_000, low = lo, high = hi)
        Section("AC hum (mains wiring)") { Text(ui.hum, fontSize = 13.sp) }
        Section("Sensor") {
            KV("Name", sensor.name); KV("Vendor", sensor.vendor); KV("Version", "${sensor.version}")
            KV("Range", "±%.0f µT".format(sensor.maximumRange)); KV("Resolution", "%.3f µT".format(sensor.resolution)); KV("Power", "%.2f mA".format(sensor.power))
            KV("Max rate (spec)", if (sensor.minDelay > 0) "%.0f Hz".format(1e6 / sensor.minDelay) else "on change")
            KV("Measured rate", "%.0f Hz".format(ui.rate))
            KV("Calibration", when (accuracy) { 3 -> "high"; 2 -> "medium"; 1 -> "low – wave the phone in a figure-8"; 0 -> "unreliable – wave the phone in a figure-8"; else -> "—" })
            KV("Uncalibrated sensor", uncal?.let { "${it.name} (${it.vendor})" } ?: "no")
        }
        Spacer(Modifier.height(24.dp))
    }
}
