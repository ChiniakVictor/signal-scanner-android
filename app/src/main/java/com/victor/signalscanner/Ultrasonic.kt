package com.victor.signalscanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.*

data class Tone(val freqHz: Double, val start: Long, var last: Long, var peakDb: Float, var frames: Int, var logged: Boolean = false)
data class ToneLog(val freqHz: Double, val start: Long, val durationMs: Long, val peakDb: Float, val aboveFloorDb: Float)

/** Tracks narrowband peaks across frames; a peak lasting >= minMs is reported as a persistent tone. Pure (unit-tested). */
class ToneTracker(private val tolHz: Double = 50.0, private val gapMs: Long = 400, private val minMs: Long = 1000) {
    val active = ArrayList<Tone>()
    val log = ArrayList<ToneLog>()
    fun update(now: Long, peaks: List<Pair<Double, Float>>, floorDb: Float) {
        for ((f, db) in peaks) {
            val t = active.firstOrNull { abs(it.freqHz - f) <= tolHz }
            if (t != null) { t.last = now; t.frames++; if (db > t.peakDb) t.peakDb = db } else active += Tone(f, now, now, db, 1)
        }
        val it = active.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (now - t.last > gapMs) { if (t.last - t.start >= minMs) finish(t, floorDb); it.remove() }
        }
    }
    private fun finish(t: Tone, floor: Float) { log.add(0, ToneLog(t.freqHz, t.start, t.last - t.start, t.peakDb, t.peakDb - floor)); while (log.size > 200) log.removeAt(log.size - 1) }
    fun persistent(now: Long) = active.filter { now - it.start >= minMs }
}

object UltraDsp {
    /** Local-maximum peaks at least [minAbove] dB above the band's median, strongest first. */
    fun peaks(db: FloatArray, from: Int, to: Int, binHz: Double, minAbove: Float = 12f, max: Int = 6): Pair<Float, List<Pair<Double, Float>>> {
        val band = db.copyOfRange(from, to).sorted(); val floor = band[band.size / 2]
        val out = ArrayList<Pair<Double, Float>>()
        for (i in max(from, 2) until min(to, db.size - 2)) {
            val v = db[i]
            if (v > floor + minAbove && v >= db[i - 1] && v >= db[i + 1] && v > db[i - 2] && v > db[i + 2]) out += i * binHz to v
        }
        return floor to out.sortedByDescending { it.second }.take(max)
    }
}

private const val FS = 48000
private const val N = 4096
private const val LO_HZ = 15000.0
private const val HI_HZ = 22000.0

@Composable
fun UltrasonicTab() {
    val ctx = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; if (ok) running = true }
    val binHz = FS.toDouble() / N
    val from = (LO_HZ / binHz).toInt(); val to = (HI_HZ / binHz).toInt()
    val cols = to - from
    val bmp = remember { Bitmap.createBitmap(cols, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) } }
    var frame by remember { mutableIntStateOf(0) }
    var spectrum by remember { mutableStateOf(FloatArray(0)) }
    var floorDb by remember { mutableFloatStateOf(0f) }
    var fullLevel by remember { mutableFloatStateOf(0f) }
    var srcName by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val tracker = remember { ToneTracker() }
    var logs by remember { mutableStateOf<List<ToneLog>>(emptyList()) }
    var live by remember { mutableStateOf<List<Tone>>(emptyList()) }
    val hook by TestHooks.ultra.collectAsState()
    LaunchedEffect(hook) { hook?.let { if (!it || granted) running = it; TestHooks.ultra.value = null } }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) running = false }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); running = false }
    }
    if (running) DisposableEffect(Unit) {
        val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        val th = Thread {
            @SuppressLint("MissingPermission")
            fun open(): AudioRecord? {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
                val min = AudioRecord.getMinBufferSize(FS, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                for (src in listOfNotNull(if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else null, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
                    val r = runCatching { AudioRecord(src, FS, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, max(min, N * 8)) }.getOrNull()
                    if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                        srcName = when (src) { MediaRecorder.AudioSource.UNPROCESSED -> "unprocessed mic"; MediaRecorder.AudioSource.VOICE_RECOGNITION -> "voice-recognition mic (minimal processing)"; else -> "default mic" }
                        return r
                    }
                    r?.release()
                }
                return null
            }
            val rec = open()
            if (rec == null) { err = "Couldn't open the microphone (in use by another app?)"; running = false; return@Thread }
            val win = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
            val ring = FloatArray(N); val hop = N / 2; val tmp = FloatArray(hop)
            val re = FloatArray(N); val im = FloatArray(N); val db = FloatArray(N / 2)
            try {
                rec.startRecording()
                while (!stopFlag.get()) {
                    var got = 0
                    while (got < hop && !stopFlag.get()) { val n = rec.read(tmp, got, hop - got, AudioRecord.READ_BLOCKING); if (n <= 0) break; got += n }
                    System.arraycopy(ring, hop, ring, 0, N - hop); System.arraycopy(tmp, 0, ring, N - hop, hop)
                    for (i in 0 until N) { re[i] = ring[i] * win[i]; im[i] = 0f }
                    Dsp.fft(re, im)
                    var tot = 0.0
                    for (i in 0 until N / 2) { val p = re[i] * re[i] + im[i] * im[i]; tot += p; db[i] = (10 * log10(p.toDouble() + 1e-20) - 20 * log10(N / 4.0)).toFloat() }
                    val (floor, pk) = UltraDsp.peaks(db, from, to, binHz)
                    val now = System.currentTimeMillis()
                    synchronized(tracker) { tracker.update(now, pk, floor) }
                    // waterfall: scroll down one row, newest at top; colour = dB above floor
                    val px = IntArray(cols) { c -> heat(((db[from + c] - floor + 6f) / 40f).coerceIn(0f, 1f)) }
                    synchronized(bmp) {
                        val all = IntArray(cols * 239); bmp.getPixels(all, 0, cols, 0, 0, cols, 239)
                        bmp.setPixels(all, 0, cols, 0, 1, cols, 239); bmp.setPixels(px, 0, cols, 0, 0, cols, 1)
                    }
                    val band = db.copyOfRange(from, to)
                    val lvl = (10 * log10(tot / (N.toDouble() * N / 4) + 1e-20)).toFloat()
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        spectrum = band; floorDb = floor; fullLevel = lvl; frame++
                        synchronized(tracker) { logs = tracker.log.toList(); live = tracker.persistent(now).map { it.copy() } }
                    }
                }
            } catch (t: Throwable) { err = t.message } finally { runCatching { rec.stop() }; rec.release() }
        }
        th.start()
        onDispose { stopFlag.set(true); th.join(500) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(onClick = { if (running) running = false else if (granted) running = true else launcher.launch(Manifest.permission.RECORD_AUDIO) },
                colors = if (running) ButtonDefaults.buttonColors(containerColor = Red) else ButtonDefaults.buttonColors()) { Text(if (running) "Stop listening" else "Start listening") }
            Spacer(Modifier.width(10.dp))
            Text(if (running) "● Mic on · $srcName · 48 kHz" else "Mic off", color = if (running) Red else Muted, fontSize = 13.sp)
        }
        Text("Looks for inaudible tones between 15 and 22 kHz, like the ultrasonic beacons some ads/apps use (SilverPush, Lisnr style) to link devices. " +
            "Audio is analysed in memory only and never recorded or saved; the mic is used only while this tab is open and you've tapped Start. " +
            "Phone mics roll off above ~20 kHz, so weak beacons may be missed.", color = Muted, fontSize = 12.sp)
        err?.let { Text(it, color = Amber) }
        Spacer(Modifier.height(8.dp))
        Text("Waterfall 15–22 kHz (newest at top)", color = Muted, fontSize = 12.sp)
        val img = remember(frame) { synchronized(bmp) { bmp.copy(Bitmap.Config.ARGB_8888, false) }.asImageBitmap() }
        Image(img, null, Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.FillBounds, filterQuality = FilterQuality.None)
        Row(Modifier.fillMaxWidth()) { listOf("15k", "16k", "17k", "18k", "19k", "20k", "21k", "22k").forEach { Text(it, fontSize = 10.sp, color = Muted, modifier = Modifier.weight(1f)) } }
        Text("Spectrum now (dBFS)", color = Muted, fontSize = 12.sp)
        Canvas(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x14FFFFFF))) {
            val s = spectrum; if (s.isEmpty()) return@Canvas
            val lo = -140f; val hi = -20f
            fun y(v: Float) = size.height * (1 - ((v - lo) / (hi - lo)).coerceIn(0f, 1f))
            for (i in 1 until s.size) drawLine(Cyan, Offset((i - 1) * size.width / s.size, y(s[i - 1])), Offset(i * size.width / s.size, y(s[i])), 2f)
            drawLine(Amber, Offset(0f, y(floorDb + 12)), Offset(size.width, y(floorDb + 12)), 1f)
            val p = android.graphics.Paint().apply { textSize = 24f; color = Color(0xAAE6EDF3).toArgb() }
            drawContext.canvas.nativeCanvas.drawText("detection threshold", 8f, y(floorDb + 12) - 6f, p)
        }
        Section("Levels") {
            KV("Noise floor 15–22 kHz", if (running || frame > 0) "%.0f dBFS per bin".format(floorDb) else "—")
            KV("Overall level", if (running || frame > 0) "%.0f dBFS".format(fullLevel) else "—")
            KV("Resolution", "%.1f Hz per bin, %d ms frames".format(binHz, 1000 * N / 2 / FS))
        }
        Section("Tones now (lasting > 1 s)") {
            if (live.isEmpty()) Text(if (running) "None – no persistent narrowband tone" else "—", color = Muted, fontSize = 13.sp)
            live.forEach { t -> KV("%.0f Hz".format(t.freqHz), "%.0f dB above floor · %.1f s so far".format(t.peakDb - floorDb, (System.currentTimeMillis() - t.start) / 1000.0), color = Orange) }
        }
        Section("Peak log (this session, in memory)") {
            if (logs.isEmpty()) Text("No persistent tones yet", color = Muted, fontSize = 13.sp)
            logs.take(30).forEach { l -> KV("${Fmt.time(l.start)}  %.0f Hz".format(l.freqHz), "%.1f s · peak %.0f dBFS (%.0f dB above floor)".format(l.durationMs / 1000.0, l.peakDb, l.aboveFloorDb)) }
            if (logs.isNotEmpty()) TextButton(onClick = {
                val csv = "start,frequency_hz,duration_s,peak_dbfs,above_floor_db\n" + logs.joinToString("\n") { "%s,%.0f,%.2f,%.1f,%.1f".format(Fmt.dateTime(it.start), it.freqHz, it.durationMs / 1000.0, it.peakDb, it.aboveFloorDb) }
                runCatching { Export.save(ctx, "ultrasonic_peaks", "csv", "text/csv", csv) }
            }) { Text("Save peak log (CSV)") }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun heat(v: Float): Int {
    val r = (255 * min(1f, v * 2)).toInt(); val g = (255 * max(0f, v * 2 - 0.6f).coerceAtMost(1f)).toInt(); val b = (255 * (if (v < 0.3f) v * 2.5f else max(0f, 1 - v * 2))).toInt().coerceIn(0, 255)
    return android.graphics.Color.rgb(r, g, b)
}
