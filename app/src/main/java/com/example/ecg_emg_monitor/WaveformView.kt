package com.example.ecg_emg_monitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Five-second oscilloscope-style display. All methods are called on the UI thread.
 * The x-axis uses the configured nominal sample rate; this protocol has no source timestamps.
 */
class WaveformView(context: Context) : View(context) {

    private val nominalHz = 250
    private val visibleSeconds = 5
    private val capacity = nominalHz * visibleSeconds
    private val values = IntArray(capacity)
    private var nextWrite = 0
    private var count = 0
    private var autoScale = false
    private var mode = "ECG"

    private val bg = Paint().apply { color = Color.rgb(13, 20, 32) }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(48, 65, 82)
        strokeWidth = dp(1f)
    }
    private val graph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(64, 225, 142)
        strokeWidth = dp(1.7f)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val axes = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(194, 209, 224)
        textSize = dp(11f)
    }
    private val trace = Path()

    private fun dp(x: Float): Float = x * resources.displayMetrics.density

    fun appendSamples(newValues: List<Int>) {
        if (newValues.isEmpty()) return
        for (sample in newValues) {
            values[nextWrite] = sample.coerceIn(0, 1023)
            nextWrite = (nextWrite + 1) % capacity
            if (count < capacity) count++
        }
        invalidate() // Exactly one redraw per batch, not one per ADC sample.
    }

    fun clearWaveform() {
        nextWrite = 0
        count = 0
        invalidate()
    }

    fun setAutoScale(enabled: Boolean) {
        autoScale = enabled
        invalidate()
    }

    fun setMode(selected: String) {
        mode = selected
        graph.color = if (mode == "EMG") Color.rgb(255, 190, 83)
        else Color.rgb(64, 225, 142)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)

        val left = dp(53f)
        val right = w - dp(13f)
        val top = dp(14f)
        val bottom = h - dp(29f)
        if (right <= left || bottom <= top) return
        val plotW = right - left
        val plotH = bottom - top

        var low = 0f
        var high = 1023f
        if (autoScale && count > 1) {
            var minAdc = 1023
            var maxAdc = 0
            for (i in 0 until count) {
                val idx = (nextWrite - count + i + capacity) % capacity
                minAdc = min(minAdc, values[idx])
                maxAdc = max(maxAdc, values[idx])
            }
            val middle = (maxAdc + minAdc) / 2f
            val halfSpan = max(10f, (maxAdc - minAdc) * 0.6f)
            low = max(0f, middle - halfSpan)
            high = min(1023f, middle + halfSpan)
            if (high - low < 1f) high = low + 1f
        }

        axes.textAlign = Paint.Align.RIGHT
        for (i in 0..4) {
            val fraction = i / 4f
            val y = top + fraction * plotH
            canvas.drawLine(left, y, right, y, grid)
            val volts = (high - fraction * (high - low)) * 5f / 1023f
            canvas.drawText(String.format(Locale.US, "%.2fV", volts), left - dp(5f), y + dp(4f), axes)
        }

        axes.textAlign = Paint.Align.CENTER
        for (i in 0..visibleSeconds) {
            val x = left + i * plotW / visibleSeconds
            canvas.drawLine(x, top, x, bottom, grid)
            canvas.drawText("${i - visibleSeconds}s", x, bottom + dp(18f), axes)
        }

        if (count < 2) return
        trace.reset()
        val step = plotW / (capacity - 1).toFloat()
        for (i in 0 until count) {
            val idx = (nextWrite - count + i + capacity) % capacity
            val x = right - (count - 1 - i) * step
            val y = bottom - ((values[idx] - low) / (high - low)) * plotH
            if (i == 0) trace.moveTo(x, y) else trace.lineTo(x, y)
        }
        canvas.save()
        canvas.clipRect(left, top, right, bottom)
        canvas.drawPath(trace, graph)
        canvas.restore()
    }
}
