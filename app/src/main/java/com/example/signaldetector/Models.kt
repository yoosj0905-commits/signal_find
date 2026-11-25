package com.example.signaldetector

/** 신호 종류 */
enum class SignalType(val label: String) {
    BLE("BLE"),
    BT_CLASSIC("BT"),
    WIFI("Wi-Fi")
}

/** 탐지된 신호 하나 (블루투스 장치 또는 Wi-Fi 공유기/핫스팟) */
class Signal(
    val key: String,
    val type: SignalType,
    val id: String,
    var name: String,
    var rssi: Int,
    var extra: String,
    val firstSeen: Long
) {
    var lastSeen: Long = firstSeen
    var isNew: Boolean = false
    var hits: Int = 1
}

/**
 * 탐지된 신호 저장소.
 * baselineUntil 이전에 처음 보인 신호는 '기존 신호'(기준), 이후에 처음 보인 신호는 '새 신호'로 표시한다.
 * 모든 시간은 SystemClock.elapsedRealtime() 기준 밀리초.
 */
class SignalStore {
    private val map = LinkedHashMap<String, Signal>()

    var baselineUntil: Long = 0L

    val all: Collection<Signal> get() = map.values

    operator fun get(key: String): Signal? = map[key]

    fun update(type: SignalType, id: String, name: String, rssi: Int, extra: String, now: Long): Signal {
        val key = keyOf(type, id)
        val existing = map[key]
        if (existing != null) {
            existing.rssi = rssi
            existing.lastSeen = now
            existing.hits++
            if (name.isNotEmpty()) existing.name = name
            if (extra.isNotEmpty()) existing.extra = extra
            return existing
        }
        val s = Signal(key, type, id, name, rssi, extra, now)
        s.isNew = now >= baselineUntil
        map[key] = s
        return s
    }

    /** 현재 목록에 있는 모든 신호를 '기존 신호'로 처리 */
    fun markAllAsBaseline() {
        for (s in map.values) s.isNew = false
    }

    fun clear() {
        map.clear()
    }

    companion object {
        fun keyOf(type: SignalType, id: String): String = "${type.name}|$id"
    }
}
