package com.example.signaldetector

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

enum class Trend { UNKNOWN, APPROACHING, RECEDING, STABLE }

/** 방향 추정 결과. confidence: 0=낮음, 1=보통, 2=높음 */
class DirectionResult(
    val bearingDeg: Double,
    val coveredBins: Int,
    val contrastDb: Double,
    val confidence: Int
)

fun bearingName(deg: Double): String {
    val names = arrayOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")
    val norm = ((deg % 360.0) + 360.0) % 360.0
    val idx = ((norm + 22.5).toInt() / 45) % 8
    return names[idx]
}

/**
 * 선택한 신호 하나의 세기(RSSI)를 추적한다.
 *  - 지수이동평균으로 잡음을 줄이고
 *  - 최근 구간의 기울기(dB/초)로 가까워짐/멀어짐을 판단하고
 *  - 경로손실 모델로 대략적인 거리를 추정하며
 *  - 휴대폰이 향한 방위(30° 단위 12구간)별 세기를 모아 신호가 오는 방향을 추정한다.
 */
class SignalTracker(val type: SignalType) {

    class Sample(val t: Long, val smoothed: Double, val raw: Int)

    companion object {
        const val BINS = 12
        const val BIN_WIDTH = 360.0 / BINS
        const val BIN_STALE_MS = 60_000L
        private const val HISTORY_MS = 90_000L
    }

    val history = ArrayList<Sample>()
    val binEma = DoubleArray(BINS) { Double.NaN }
    val binTime = LongArray(BINS)

    private var ema = Double.NaN

    var lastUpdate = 0L
        private set
    var sampleCount = 0
        private set

    val smoothedRssi: Double get() = ema

    private val isFast: Boolean get() = type == SignalType.BLE

    fun binOf(headingDeg: Double): Int {
        val h = ((headingDeg % 360.0) + 360.0) % 360.0
        return minOf((h / BIN_WIDTH).toInt(), BINS - 1)
    }

    /** 새 RSSI 측정값 추가. headingDeg: 이때 휴대폰이 향한 방위(자북 기준, 모르면 null) */
    fun add(rssi: Int, now: Long, headingDeg: Double?) {
        val alpha = if (isFast) 0.25 else 0.5
        ema = if (ema.isNaN()) rssi.toDouble() else ema + alpha * (rssi - ema)
        lastUpdate = now
        sampleCount++

        history.add(Sample(now, ema, rssi))
        while (history.isNotEmpty() && now - history[0].t > HISTORY_MS) history.removeAt(0)

        if (headingDeg != null) {
            val i = binOf(headingDeg)
            val cur = binEma[i]
            binEma[i] = if (cur.isNaN() || now - binTime[i] > BIN_STALE_MS) {
                rssi.toDouble()
            } else {
                cur + 0.5 * (rssi - cur)
            }
            binTime[i] = now
        }
    }

    fun resetDirection() {
        binEma.fill(Double.NaN)
        binTime.fill(0L)
    }

    // ---- 추세 ----------------------------------------------------------

    fun staleMs(): Long = if (isFast) 6_000L else 45_000L

    fun isStale(now: Long): Boolean = sampleCount == 0 || now - lastUpdate > staleMs()

    /** 최근 구간 평활 RSSI의 기울기 (dB/초). 양수면 세기가 커지는 중(가까워짐). */
    fun slopeDbPerSec(now: Long): Double? {
        val windowMs = if (isFast) 10_000L else 80_000L
        val minPoints = if (isFast) 4 else 3
        val minSpanMs = if (isFast) 3_000L else 20_000L

        val pts = history.filter { now - it.t <= windowMs }
        if (pts.size < minPoints) return null
        val t0 = pts[0].t
        if (pts[pts.size - 1].t - t0 < minSpanMs) return null

        var sx = 0.0
        var sy = 0.0
        var sxx = 0.0
        var sxy = 0.0
        for (p in pts) {
            val x = (p.t - t0) / 1000.0
            sx += x
            sy += p.smoothed
            sxx += x * x
            sxy += x * p.smoothed
        }
        val n = pts.size.toDouble()
        val denom = n * sxx - sx * sx
        if (denom < 1e-6) return null
        return (n * sxy - sx * sy) / denom
    }

    fun trend(now: Long): Trend {
        if (isStale(now)) return Trend.UNKNOWN
        val s = slopeDbPerSec(now) ?: return Trend.UNKNOWN
        val threshold = if (isFast) 0.3 else 0.06
        return when {
            s > threshold -> Trend.APPROACHING
            s < -threshold -> Trend.RECEDING
            else -> Trend.STABLE
        }
    }

    // ---- 거리 ----------------------------------------------------------

    /** 로그거리 경로손실 모델 기반 대략적 거리(m). 환경에 따라 오차가 매우 큼. */
    fun distanceMeters(): Double? {
        if (ema.isNaN()) return null
        val txAt1m = if (type == SignalType.WIFI) -40.0 else -59.0
        val n = if (type == SignalType.WIFI) 3.0 else 2.2
        return 10.0.pow((txAt1m - ema) / (10.0 * n))
    }

    fun proximityLabel(): String {
        if (ema.isNaN()) return "-"
        val off = if (type == SignalType.WIFI) 10 else 0
        return when {
            ema >= -55 + off -> "매우 가까움"
            ema >= -70 + off -> "가까움"
            ema >= -85 + off -> "중간 거리"
            else -> "멀거나 약함"
        }
    }

    // ---- 방향 ----------------------------------------------------------

    /**
     * 방위별 세기에서 가장 강한 방향을 찾고, 이웃 구간과 전력 가중 평균으로 보정한다.
     * 측정된 방위 구간이 3개 미만이면 null.
     */
    fun direction(now: Long): DirectionResult? {
        val valid = BooleanArray(BINS) { i -> !binEma[i].isNaN() && now - binTime[i] <= BIN_STALE_MS }
        val covered = valid.count { it }
        if (covered < 3) return null

        var best = -1
        for (i in 0 until BINS) {
            if (valid[i] && (best == -1 || binEma[i] > binEma[best])) best = i
        }
        val maxV = binEma[best]

        var vx = 0.0
        var vy = 0.0
        for (d in -1..1) {
            val i = (best + d + BINS) % BINS
            if (!valid[i]) continue
            val w = 10.0.pow((binEma[i] - maxV) / 10.0)
            val ang = Math.toRadians((i + 0.5) * BIN_WIDTH)
            vx += w * sin(ang)
            vy += w * cos(ang)
        }
        val bearing = (Math.toDegrees(atan2(vx, vy)) + 360.0) % 360.0

        var sum = 0.0
        var cnt = 0
        for (i in 0 until BINS) {
            if (valid[i] && i != best) {
                sum += binEma[i]
                cnt++
            }
        }
        val contrast = if (cnt > 0) maxV - sum / cnt else 0.0
        val confidence = when {
            covered >= 9 && contrast >= 6.0 -> 2
            covered >= 6 && contrast >= 3.0 -> 1
            else -> 0
        }
        return DirectionResult(bearing, covered, contrast, confidence)
    }
}
