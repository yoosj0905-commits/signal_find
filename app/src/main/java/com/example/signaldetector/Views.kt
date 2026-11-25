package com.example.signaldetector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 방향 표시 나침반.
 * 화면 위쪽 = 휴대폰이 향한 방향. 12개 부채꼴은 방위별로 측정된 신호 세기(빨강=강함, 파랑=약함),
 * 흰 화살표는 추정된 신호 방향이다. 화살표가 위쪽을 가리키면 그 방향으로 가면 된다.
 */
class CompassView(context: Context) : View(context) {

    var heading: Float = 0f
    var tracker: SignalTracker? = null
    var estimateBearing: Double? = null
    var nowMs: Long = 0L

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val gray = Color.parseColor("#2A2F3A")
    private val sector = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#3A4150")
        strokeWidth = 3f
    }
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0F1115")
    }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val arrowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val facing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFD166")
    }
    private val rect = RectF()
    private val path = Path()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = min(MeasureSpec.getSize(widthMeasureSpec), dp(340f).toInt())
        setMeasuredDimension(w, w)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - dp(16f)
        rect.set(cx - r, cy - r, cx + r, cy + r)

        val t = tracker
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        if (t != null) {
            for (i in 0 until SignalTracker.BINS) {
                val v = t.binEma[i]
                if (!v.isNaN() && nowMs - t.binTime[i] <= SignalTracker.BIN_STALE_MS) {
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
            }
        }

        val bw = SignalTracker.BIN_WIDTH.toFloat()
        for (i in 0 until SignalTracker.BINS) {
            val bearing = (i + 0.5f) * bw
            val screenAngle = bearing - heading
            sector.color = sectorColor(t, i, lo, hi)
            canvas.drawArc(rect, screenAngle - bw / 2f - 90f, bw - 1.5f, true, sector)
        }
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawCircle(cx, cy, r * 0.28f, hub)

        // 북쪽 표시
        northPaint.textSize = dp(16f)
        val na = Math.toRadians((0f - heading).toDouble())
        val nr = r * 0.88f
        canvas.drawText(
            "N",
            cx + (nr * sin(na)).toFloat(),
            cy - (nr * cos(na)).toFloat() + dp(6f),
            northPaint
        )

        // 추정 방향 화살표
        val est = estimateBearing
        if (est != null) {
            drawArrow(canvas, cx, cy, r * 0.82f, (est - heading).toFloat())
        }

        // 휴대폰이 향한 쪽(위) 표시
        path.reset()
        path.moveTo(cx, cy - r + dp(2f))
        path.lineTo(cx - dp(9f), cy - r - dp(11f))
        path.lineTo(cx + dp(9f), cy - r - dp(11f))
        path.close()
        canvas.drawPath(path, facing)
    }

    private fun sectorColor(t: SignalTracker?, i: Int, lo: Double, hi: Double): Int {
        if (t == null) return gray
        val v = t.binEma[i]
        if (v.isNaN() || nowMs - t.binTime[i] > SignalTracker.BIN_STALE_MS) return gray
        val f = ((v - lo) / max(6.0, hi - lo)).coerceIn(0.0, 1.0)
        val hue = (220.0 * (1.0 - f)).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.75f, 0.95f))
    }

    private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, len: Float, angleDeg: Float) {
        val a = Math.toRadians(angleDeg.toDouble())
        val dx = sin(a).toFloat()
        val dy = -cos(a).toFloat()
        val head = dp(30f)
        val half = dp(15f)
        val tipX = cx + dx * len
        val tipY = cy + dy * len
        val baseX = cx + dx * (len - head)
        val baseY = cy + dy * (len - head)
        val px = -dy
        val py = dx

        arrowLine.strokeWidth = dp(5f)
        canvas.drawLine(cx, cy, baseX, baseY, arrowLine)
        path.reset()
        path.moveTo(tipX, tipY)
        path.lineTo(baseX + px * half, baseY + py * half)
        path.lineTo(baseX - px * half, baseY - py * half)
        path.close()
        canvas.drawPath(path, arrowFill)
    }
}

/** 최근 60초 RSSI 그래프 (점=원시값, 선=평활값) */
class GraphView(context: Context) : View(context) {

    var samples: List<SignalTracker.Sample> = emptyList()
    var nowMs: Long = 0L

    private val density = resources.displayMetrics.density

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1B1F27")
    }
    private val grid = Paint().apply {
        color = Color.parseColor("#2C3340")
        strokeWidth = 1f
    }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7C8696")
        textSize = 10f * density
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#4DA3FF")
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#66FFFFFF")
    }
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, 12f * density, 12f * density, bg)

        val left = 40f * density
        val right = w - 10f * density
        val top = 12f * density
        val bottom = h - 12f * density
        val maxDb = -30f
        val minDb = -100f
        val windowMs = 60_000f

        for (db in intArrayOf(-40, -60, -80)) {
            val y = top + (bottom - top) * (maxDb - db) / (maxDb - minDb)
            canvas.drawLine(left, y, right, y, grid)
            canvas.drawText("$db", 8f * density, y + 4f * density, txt)
        }
        canvas.drawText("최근 60초", right - 56f * density, top + 2f * density, txt)

        path.reset()
        var started = false
        for (s in samples) {
            val age = (nowMs - s.t).toFloat()
            if (age > windowMs || age < 0f) continue
            val x = left + (right - left) * (1f - age / windowMs)
            val fs = ((maxDb - s.smoothed.toFloat()) / (maxDb - minDb)).coerceIn(0f, 1f)
            val fr = ((maxDb - s.raw.toFloat()) / (maxDb - minDb)).coerceIn(0f, 1f)
            canvas.drawCircle(x, top + (bottom - top) * fr, 2.5f * density, dot)
            val y = top + (bottom - top) * fs
            if (!started) {
                path.moveTo(x, y)
                started = true
            } else {
                path.lineTo(x, y)
            }
        }
        if (started) canvas.drawPath(path, line)
    }
}
