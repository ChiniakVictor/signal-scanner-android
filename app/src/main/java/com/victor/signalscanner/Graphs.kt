package com.victor.signalscanner

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import kotlin.math.abs

val Cyan = Color(0xFF4DD0E1)
val Orange = Color(0xFFFFA94D)
val Green = Color(0xFF5BD47A)
val Amber = Color(0xFFFFC857)
val Red = Color(0xFFFF6B6B)
val Muted = Color(0xFF8B97A6)
val Purple = Color(0xFFB39DFF)

fun rssiColor(r: Int?): Color = when {
    r == null -> Muted; r >= -55 -> Green; r >= -67 -> Color(0xFFA5DE6A); r >= -75 -> Amber; r >= -85 -> Orange; else -> Red
}

/** Four signal bars. */
@Composable
fun SignalBars(rssi: Int?, modifier: Modifier = Modifier.width(22.dp).height(16.dp)) {
    val n = if (rssi == null) 0 else WifiParse.bars(rssi)
    val c = rssiColor(rssi)
    Canvas(modifier) {
        val bw = size.width / 5.2f
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4f
            drawRoundRect(if (i < n) c else Color(0x33FFFFFF), Offset(i * bw * 1.35f, size.height - h), Size(bw, h), CornerRadius(2f, 2f))
        }
    }
}

/** RSSI history over time. x = time (gaps show as breaks), y = dBm. */
@Composable
fun Sparkline(samples: List<Sample>, modifier: Modifier, color: Color = Cyan, axes: Boolean = false, windowMs: Long? = null, gapMs: Long = 0, low: Int = -100, high: Int = -30, unit: String = "") {
    Canvas(modifier) {
        val padL = if (axes) 70f else 0f
        val padB = if (axes) 30f else 0f
        val w = size.width - padL; val h = size.height - padB
        val lo = low.toFloat(); val hi = high.toFloat()
        fun y(v: Int) = h - ((v.coerceIn(low, high) - lo) / (hi - lo)) * h
        val paint = Paint().apply { this.color = Color(0xAAE6EDF3).toArgb(); textSize = 26f; isAntiAlias = true }
        if (axes) {
            val step = (high - low) / 3.5f
            for (db in (0..3).map { (low + it * step).toInt() }) {
                val yy = y(db)
                drawLine(Color(0x33FFFFFF), Offset(padL, yy), Offset(size.width, yy), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                drawContext.canvas.nativeCanvas.drawText("$db$unit", 4f, yy + 9f, paint)
            }
        }
        if (samples.isEmpty()) return@Canvas
        val t1 = samples.last().t
        val t0 = windowMs?.let { t1 - it } ?: samples.first().t
        val span = (t1 - t0).coerceAtLeast(1L).toFloat()
        fun x(t: Long) = padL + ((t - t0) / span) * w
        if (axes) {
            val mins = (t1 - t0) / 60000.0
            val label = if (mins >= 1.5) "-%.0f min".format(mins) else "-${(t1 - t0) / 1000}s"
            drawContext.canvas.nativeCanvas.drawText(label, padL, size.height - 4f, paint)
            drawContext.canvas.nativeCanvas.drawText("now", size.width - 50f, size.height - 4f, paint)
        }
        val pts = samples.filter { it.t >= t0 }
        if (pts.size == 1) { drawCircle(color, 5f, Offset(x(pts[0].t), y(pts[0].rssi))); return@Canvas }
        val line = Path(); val fill = Path()
        var started = false; var prevT = 0L
        pts.forEach { s ->
            val px = x(s.t); val py = y(s.rssi)
            if (!started || (gapMs > 0 && s.t - prevT > gapMs)) {
                if (started) { fill.lineTo(x(prevT), h) }
                line.moveTo(px, py); fill.moveTo(px, h); fill.lineTo(px, py); started = true
            } else { line.lineTo(px, py); fill.lineTo(px, py) }
            prevT = s.t
        }
        fill.lineTo(x(prevT), h); fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.02f)), 0f, h))
        drawPath(line, color, style = Stroke(width = if (axes) 4f else 2.5f))
        if (axes) pts.forEach { drawCircle(color, 3.5f, Offset(x(it.t), y(it.rssi))) }
    }
}

private val palette = listOf(Color(0xFF4DD0E1), Color(0xFFFFA94D), Color(0xFF5BD47A), Color(0xFFB39DFF), Color(0xFFFF6B9A), Color(0xFFFFE066),
    Color(0xFF64B5F6), Color(0xFFA1887F), Color(0xFF80CBC4), Color(0xFFE57373), Color(0xFFAED581), Color(0xFFF06292))
fun apColor(bssid: String) = palette[abs(bssid.hashCode()) % palette.size]

/** Classic Wi-Fi analyzer channel graph: each AP drawn as a trapezoid spanning its channel width. */
@Composable
fun ChannelGraph(aps: List<WifiAp>, band: String, modifier: Modifier, selected: String?) {
    val (fLo, fHi, ticks) = when (band) {
        "2.4 GHz" -> Triple(2397f, 2497f, (1..14).map { it to (if (it == 14) 2484 else 2407 + 5 * it) })
        "5 GHz" -> Triple(5150f, 5895f, listOf(36, 52, 64, 100, 116, 132, 149, 165, 177).map { it to 5000 + 5 * it })
        else -> Triple(5925f, 7125f, listOf(1, 33, 65, 97, 129, 161, 193, 225).map { it to 5950 + 5 * it })
    }
    Canvas(modifier) {
        val padL = 70f; val padB = 34f; val padT = 10f
        val w = size.width - padL; val h = size.height - padB - padT
        fun x(f: Float) = padL + (f - fLo) / (fHi - fLo) * w
        fun y(db: Int) = padT + h - ((db.coerceIn(-100, -20) + 100) / 80f) * h
        val paint = Paint().apply { color = Color(0xAAE6EDF3).toArgb(); textSize = 24f; isAntiAlias = true }
        for (db in listOf(-30, -50, -70, -90)) {
            drawLine(Color(0x22FFFFFF), Offset(padL, y(db)), Offset(size.width, y(db)), 1f)
            drawContext.canvas.nativeCanvas.drawText("$db", 6f, y(db) + 8f, paint)
        }
        ticks.forEach { (ch, f) ->
            val xx = x(f.toFloat())
            drawLine(Color(0x22FFFFFF), Offset(xx, padT), Offset(xx, padT + h), 1f)
            val t = ch.toString(); drawContext.canvas.nativeCanvas.drawText(t, xx - paint.measureText(t) / 2, size.height - 6f, paint)
        }
        val list = aps.filter { it.band == band && it.inRange }.sortedBy { it.rssi }
        val label = Paint().apply { textSize = 24f; isAntiAlias = true; isFakeBoldText = true }
        val placed = ArrayList<android.graphics.RectF>()
        list.forEach { a ->
            val center = (if (a.center1 != 0 && a.widthMhz >= 160 && a.widthMhz != 320) a.center1 else a.center0.takeIf { it != 0 } ?: a.freq).toFloat()
            val half = a.widthMhz / 2f
            val c = apColor(a.bssid)
            val sel = a.bssid == selected
            val top = y(a.rssi); val base = y(-100)
            val p = Path().apply {
                moveTo(x(center - half), base); lineTo(x(center - half + 2), top); lineTo(x(center + half - 2), top); lineTo(x(center + half), base)
            }
            drawPath(p, c.copy(alpha = if (sel) 0.35f else 0.12f))
            drawPath(p, c, style = Stroke(width = if (sel) 5f else 2.5f))
            label.color = c.toArgb()
            val t = a.name.take(18)
            val tw = label.measureText(t)
            val lx = (x(center) - tw / 2).coerceIn(padL, size.width - tw)
            var ly = top - 6f
            while (placed.any { android.graphics.RectF.intersects(it, android.graphics.RectF(lx, ly - 24f, lx + tw, ly + 2f)) } && ly > 30f) ly -= 26f
            placed += android.graphics.RectF(lx, ly - 24f, lx + tw, ly + 2f)
            drawContext.canvas.nativeCanvas.drawText(t, lx, ly, label)
        }
    }
}

@Composable
fun Legend(color: Color, text: String) = Row { Text("■ ", color = color); Text(text, color = Muted, modifier = Modifier.padding(end = 8.dp)) }
