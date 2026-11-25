package com.example.signaldetector

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : Activity(), SignalScanner.Listener, SensorEventListener {

    companion object {
        private const val REQ_PERMS = 1001
        private const val BASELINE_MS = 15_000L
        private const val LIST_REFRESH_MS = 1_500L
        private const val TRACK_REFRESH_MS = 400L
        private const val STALE_MS = 30_000L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private val COLOR_BG = Color.parseColor("#0F1115")
    private val COLOR_CARD = Color.parseColor("#1B1F27")
    private val COLOR_TEXT = Color.parseColor("#E6E8EB")
    private val COLOR_SUB = Color.parseColor("#9AA4B2")
    private val COLOR_ALERT = Color.parseColor("#FF6B6B")
    private val COLOR_GOOD = Color.parseColor("#4CC38A")
    private val COLOR_NEW_BG = Color.parseColor("#2A1517")

    private val handler = Handler(Looper.getMainLooper())
    private val store = SignalStore()
    private lateinit var scanner: SignalScanner

    private var scanning = false
    private var baselineEnd = 0L
    private var onlyNew = false
    private var touching = false

    // 목록 화면
    private lateinit var listScreen: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var scanButton: Button
    private lateinit var listView: ListView
    private lateinit var adapter: SignalAdapter

    // 추적 화면
    private lateinit var trackScreen: ScrollView
    private lateinit var trackName: TextView
    private lateinit var trackInfo: TextView
    private lateinit var rssiText: TextView
    private lateinit var rssiSub: TextView
    private lateinit var trendText: TextView
    private lateinit var trendSub: TextView
    private lateinit var distText: TextView
    private lateinit var graph: GraphView
    private lateinit var compass: CompassView
    private lateinit var dirText: TextView
    private lateinit var dirHint: TextView
    private lateinit var slowNote: TextView

    private var trackedKey: String? = null
    private var tracker: SignalTracker? = null

    // 방향 센서
    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private val rot = FloatArray(9)
    private var headingSin = 0.0
    private var headingCos = 1.0
    private var hasHeading = false
    private var sensorAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH

    // ------------------------------------------------------------------
    // 생명주기
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scanner = SignalScanner(this, this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        val root = FrameLayout(this)
        root.setBackgroundColor(COLOR_BG)
        root.addView(buildListScreen(), FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(buildTrackScreen(), FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        showList()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(listLoop)
        handler.post(listLoop)
        if (trackedKey != null) {
            registerSensor()
            handler.removeCallbacks(trackLoop)
            handler.post(trackLoop)
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(listLoop)
        handler.removeCallbacks(trackLoop)
        unregisterSensor()
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.stop()
        handler.removeCallbacksAndMessages(null)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (trackScreen.visibility == View.VISIBLE) {
            closeTrack()
        } else {
            super.onBackPressed()
        }
    }

    // ------------------------------------------------------------------
    // 화면 구성 (코드로 직접 구성)
    // ------------------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun pill(color: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun label(size: Float, color: Int, bold: Boolean = false): TextView {
        val tv = TextView(this)
        tv.textSize = size
        tv.setTextColor(color)
        if (bold) tv.typeface = Typeface.DEFAULT_BOLD
        return tv
    }

    private fun buildListScreen(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setBackgroundColor(COLOR_BG)
        col.setPadding(dp(16), dp(12), dp(16), dp(8))
        listScreen = col

        col.addView(label(22f, COLOR_TEXT, true).apply { text = "신호 탐지기" })

        statusText = label(13f, COLOR_SUB).apply {
            text = "'탐색 시작'을 눌러 주변 블루투스·Wi-Fi 신호를 찾아보세요."
            setPadding(0, dp(4), 0, dp(8))
        }
        col.addView(statusText)

        scanButton = Button(this).apply {
            text = "탐색 시작"
            setOnClickListener { toggleScan() }
        }
        col.addView(scanButton, LinearLayout.LayoutParams(MATCH, WRAP))

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.addView(
            Button(this).apply {
                text = "기준 재설정"
                setOnClickListener {
                    store.markAllAsBaseline()
                    refreshList()
                    toast("현재 보이는 신호를 모두 '기존 신호'로 처리했습니다.")
                }
            },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        row.addView(
            Button(this).apply {
                text = "목록 비우기"
                setOnClickListener {
                    store.clear()
                    val now = SystemClock.elapsedRealtime()
                    baselineEnd = if (scanning) now + BASELINE_MS else 0L
                    store.baselineUntil = baselineEnd
                    refreshList()
                }
            },
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        col.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))

        val sw = Switch(this)
        sw.text = "새 신호만 표시"
        sw.setTextColor(COLOR_TEXT)
        sw.setPadding(dp(4), dp(8), dp(4), dp(8))
        sw.setOnCheckedChangeListener { _, checked ->
            onlyNew = checked
            refreshList()
        }
        col.addView(sw, LinearLayout.LayoutParams(MATCH, WRAP))

        adapter = SignalAdapter()
        listView = ListView(this)
        listView.adapter = adapter
        listView.divider = ColorDrawable(Color.parseColor("#262B35"))
        listView.dividerHeight = 1
        listView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> touching = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
            }
            false
        }
        listView.setOnItemClickListener { _, view, _, _ ->
            val key = view.tag as? String
            if (key != null) openTrack(key)
        }
        col.addView(listView, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return col
    }

    private fun buildTrackScreen(): View {
        val sv = ScrollView(this)
        sv.setBackgroundColor(COLOR_BG)
        sv.isFillViewport = true
        sv.visibility = View.GONE
        trackScreen = sv

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(12), dp(16), dp(24))
        sv.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))

        col.addView(
            Button(this).apply {
                text = "← 목록으로"
                setOnClickListener { closeTrack() }
            },
            LinearLayout.LayoutParams(WRAP, WRAP)
        )

        trackName = label(22f, COLOR_TEXT, true)
        col.addView(trackName)
        trackInfo = label(12f, COLOR_SUB)
        col.addView(trackInfo)

        rssiText = label(44f, COLOR_TEXT, true).apply { setPadding(0, dp(8), 0, 0) }
        col.addView(rssiText)
        rssiSub = label(12f, COLOR_SUB)
        col.addView(rssiSub)

        trendText = label(21f, COLOR_SUB, true).apply { setPadding(0, dp(10), 0, 0) }
        col.addView(trendText)
        trendSub = label(12f, COLOR_SUB)
        col.addView(trendSub)

        distText = label(15f, COLOR_TEXT).apply { setPadding(0, dp(10), 0, dp(8)) }
        col.addView(distText)

        graph = GraphView(this)
        col.addView(graph, LinearLayout.LayoutParams(MATCH, dp(120)))

        col.addView(label(16f, COLOR_TEXT, true).apply {
            text = "신호 방향"
            setPadding(0, dp(16), 0, dp(8))
        })
        compass = CompassView(this)
        col.addView(
            compass,
            LinearLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.CENTER_HORIZONTAL }
        )
        dirText = label(15f, COLOR_TEXT, true).apply { setPadding(0, dp(8), 0, 0) }
        col.addView(dirText)
        dirHint = label(12f, COLOR_SUB).apply { setPadding(0, dp(4), 0, 0) }
        col.addView(dirHint)

        col.addView(
            Button(this).apply {
                text = "방향 데이터 초기화"
                setOnClickListener { tracker?.resetDirection() }
            },
            LinearLayout.LayoutParams(WRAP, WRAP)
        )

        slowNote = label(12f, COLOR_SUB).apply { setPadding(0, dp(8), 0, 0) }
        col.addView(slowNote)
        return sv
    }

    // ------------------------------------------------------------------
    // 목록
    // ------------------------------------------------------------------

    private inner class SignalAdapter : BaseAdapter() {
        var items: List<Signal> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val s = items[position]
            val now = SystemClock.elapsedRealtime()
            val stale = now - s.lastSeen > STALE_MS
            val ctx = this@MainActivity

            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(8), dp(10), dp(8), dp(10))
            row.tag = s.key
            if (s.isNew && !stale) row.setBackgroundColor(COLOR_NEW_BG)
            row.alpha = if (stale) 0.45f else 1f

            // 왼쪽: 종류 배지 (+ NEW)
            val left = LinearLayout(ctx)
            left.orientation = LinearLayout.VERTICAL
            left.gravity = Gravity.CENTER_HORIZONTAL
            left.addView(badge(s.type.label, typeColor(s.type)))
            if (s.isNew) {
                left.addView(
                    badge("NEW", Color.parseColor("#E5484D")),
                    LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) }
                )
            }
            row.addView(left, LinearLayout.LayoutParams(dp(52), WRAP))

            // 가운데: 이름 / 주소 / 부가정보
            val mid = LinearLayout(ctx)
            mid.orientation = LinearLayout.VERTICAL
            mid.addView(label(16f, COLOR_TEXT, true).apply {
                text = displayName(s)
                maxLines = 1
            })
            val sub = StringBuilder(s.id)
            if (s.extra.isNotEmpty()) sub.append(" · ").append(s.extra)
            if (s.isNew) sub.append(" · ").append((now - s.firstSeen) / 1000).append("초 전 처음 감지")
            mid.addView(label(11f, COLOR_SUB).apply {
                text = sub.toString()
                maxLines = 2
            })
            row.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f).apply { leftMargin = dp(8) })

            // 오른쪽: dBm + 세기 막대
            val right = LinearLayout(ctx)
            right.orientation = LinearLayout.VERTICAL
            right.gravity = Gravity.END
            val color = rssiColor(s.rssi)
            right.addView(label(16f, color, true).apply { text = "${s.rssi} dBm" })

            val track = FrameLayout(ctx)
            track.background = pill(Color.parseColor("#2C3340"), 3)
            val fillW = maxOf(dp(3), dp(72) * rssiPercent(s.rssi) / 100)
            val fill = View(ctx)
            fill.background = pill(color, 3)
            track.addView(fill, FrameLayout.LayoutParams(fillW, MATCH))
            right.addView(track, LinearLayout.LayoutParams(dp(72), dp(6)).apply { topMargin = dp(6) })
            row.addView(right, LinearLayout.LayoutParams(dp(80), WRAP))

            return row
        }
    }

    private fun badge(text: String, color: Int): TextView {
        val tv = label(11f, Color.WHITE, true)
        tv.text = text
        tv.gravity = Gravity.CENTER
        tv.setPadding(dp(6), dp(2), dp(6), dp(2))
        tv.background = pill(color, 8)
        return tv
    }

    private fun typeColor(t: SignalType): Int = when (t) {
        SignalType.BLE -> Color.parseColor("#3D8BFF")
        SignalType.BT_CLASSIC -> Color.parseColor("#8E5CF6")
        SignalType.WIFI -> Color.parseColor("#22B07D")
    }

    private fun rssiColor(rssi: Int): Int = when {
        rssi >= -55 -> Color.parseColor("#FF5A5F")
        rssi >= -70 -> Color.parseColor("#FFB020")
        rssi >= -85 -> Color.parseColor("#4CC38A")
        else -> Color.parseColor("#7C8696")
    }

    private fun rssiPercent(rssi: Int): Int = ((rssi + 100) * 100 / 65).coerceIn(0, 100)

    private fun displayName(s: Signal): String {
        if (s.name.isNotBlank()) return s.name
        return when (s.type) {
            SignalType.BLE -> "BLE 장치"
            SignalType.BT_CLASSIC -> "블루투스 장치"
            SignalType.WIFI -> "(숨겨진 SSID)"
        }
    }

    private val listLoop = object : Runnable {
        override fun run() {
            if (listScreen.visibility == View.VISIBLE && !touching) refreshList()
            handler.postDelayed(this, LIST_REFRESH_MS)
        }
    }

    private fun refreshList() {
        val now = SystemClock.elapsedRealtime()
        val all = store.all.toList()
        val newCount = all.count { it.isNew }
        var shown = all
        if (onlyNew) shown = shown.filter { it.isNew }
        shown = shown.sortedWith(
            compareBy<Signal> { now - it.lastSeen > STALE_MS }
                .thenByDescending { it.isNew }
                .thenByDescending { it.rssi }
        )
        adapter.items = shown
        adapter.notifyDataSetChanged()
        updateStatus(all.size, newCount)
    }

    private fun updateStatus(total: Int, newCount: Int) {
        val now = SystemClock.elapsedRealtime()
        val text: String
        var alert = false
        if (!scanning) {
            text = if (total == 0) {
                "대기 중 · '탐색 시작'을 눌러 주세요."
            } else {
                "중지됨 · 전체 ${total}개 · 새 신호 ${newCount}개"
            }
        } else if (now < baselineEnd) {
            val sec = (baselineEnd - now) / 1000 + 1
            text = "기준 신호 수집 중… ${sec}초 (지금까지 ${total}개). 이 시간 동안 보인 신호는 '기존 신호'로 처리됩니다."
        } else {
            text = "탐색 중 · 전체 ${total}개 · 새 신호 ${newCount}개 · 항목을 누르면 추적합니다."
            alert = newCount > 0
        }
        statusText.text = text
        statusText.setTextColor(if (alert) COLOR_ALERT else COLOR_SUB)
    }

    // ------------------------------------------------------------------
    // 탐색 시작/중지, 권한
    // ------------------------------------------------------------------

    private fun requiredPermissions(): Array<String> {
        val list = ArrayList<String>()
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return list.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean {
        for (p in requiredPermissions()) {
            // Wi-Fi 근처 기기 권한은 위치 권한이 있으면 없어도 동작하므로 필수로 보지 않는다.
            if (p == Manifest.permission.NEARBY_WIFI_DEVICES) continue
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false
        }
        return true
    }

    private fun isLocationOn(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return if (Build.VERSION.SDK_INT >= 28) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    private fun toggleScan() {
        if (scanning) {
            stopScan()
            return
        }
        if (!hasAllPermissions()) {
            requestPermissions(requiredPermissions(), REQ_PERMS)
            return
        }
        if (!isLocationOn()) {
            toast("주변 신호를 찾으려면 위치(GPS) 서비스를 켜야 합니다.")
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        startScan()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (hasAllPermissions()) {
            toggleScan()
        } else {
            toast("권한이 허용되지 않아 탐색할 수 없습니다. 설정 > 앱 > 권한에서 '근처 기기'와 '위치'를 허용해 주세요.")
        }
    }

    private fun startScan() {
        scanning = true
        val now = SystemClock.elapsedRealtime()
        if (store.all.isEmpty()) {
            baselineEnd = now + BASELINE_MS
            store.baselineUntil = baselineEnd
        }
        scanButton.text = "탐색 중지"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scanner.start()
        refreshList()
    }

    private fun stopScan() {
        scanning = false
        scanner.stop()
        scanButton.text = "탐색 시작"
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        refreshList()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------
    // SignalScanner.Listener
    // ------------------------------------------------------------------

    override fun onReading(type: SignalType, id: String, name: String, rssi: Int, extra: String) {
        val now = SystemClock.elapsedRealtime()
        val s = store.update(type, id, name, rssi, extra, now)
        if (s.key == trackedKey) tracker?.add(rssi, now, currentHeading())
    }

    override fun onMessage(message: String) {
        toast(message)
    }

    // ------------------------------------------------------------------
    // 추적 화면
    // ------------------------------------------------------------------

    private fun showList() {
        trackScreen.visibility = View.GONE
        listScreen.visibility = View.VISIBLE
        refreshList()
    }

    private fun openTrack(key: String) {
        val s = store[key] ?: return
        trackedKey = key
        tracker = SignalTracker(s.type)
        compass.tracker = tracker
        compass.estimateBearing = null
        graph.samples = tracker!!.history
        hasHeading = false

        slowNote.text = if (s.type == SignalType.BLE) {
            ""
        } else {
            "※ Wi-Fi와 클래식 블루투스는 측정 간격이 길어(수 초~수십 초) 추세·방향이 천천히 반영됩니다. " +
                "정확한 추적은 BLE 신호가 가장 잘 됩니다. Wi-Fi는 안드로이드의 스캔 횟수 제한이 있어, " +
                "개발자 옵션의 'Wi-Fi 스캔 제한'을 끄면 더 자주 갱신됩니다."
        }

        listScreen.visibility = View.GONE
        trackScreen.visibility = View.VISIBLE
        trackScreen.scrollTo(0, 0)
        registerSensor()
        handler.removeCallbacks(trackLoop)
        handler.post(trackLoop)
    }

    private fun closeTrack() {
        trackedKey = null
        tracker = null
        compass.tracker = null
        handler.removeCallbacks(trackLoop)
        unregisterSensor()
        hasHeading = false
        showList()
    }

    private val trackLoop = object : Runnable {
        override fun run() {
            if (trackedKey == null) return
            updateTrackUi()
            handler.postDelayed(this, TRACK_REFRESH_MS)
        }
    }

    private fun updateTrackUi() {
        val s = trackedKey?.let { store[it] } ?: return
        val t = tracker ?: return
        val now = SystemClock.elapsedRealtime()

        trackName.text = displayName(s)
        trackInfo.text = "${s.type.label} · ${s.id}" + if (s.extra.isNotEmpty()) " · ${s.extra}" else ""

        graph.nowMs = now
        graph.invalidate()
        compass.nowMs = now

        if (t.sampleCount == 0) {
            rssiText.text = "-- dBm"
            rssiSub.text = "아직 측정값이 없습니다. 신호가 잡힐 때까지 기다려 주세요."
            trendText.text = "… 측정 중"
            trendText.setTextColor(COLOR_SUB)
            trendSub.text = ""
            distText.text = ""
            dirText.text = ""
            dirHint.text = ""
            compass.invalidate()
            return
        }

        val smooth = t.smoothedRssi
        rssiText.text = "${smooth.roundToInt()} dBm"
        rssiText.setTextColor(rssiColor(smooth.roundToInt()))
        val ageSec = (now - t.lastUpdate) / 1000
        rssiSub.text = "최근 측정값 ${s.rssi} dBm · ${ageSec}초 전 수신 · 총 ${t.sampleCount}회 측정"

        // 추세
        val stale = t.isStale(now)
        val slope = t.slopeDbPerSec(now)
        if (stale) {
            trendText.text = "✕ 신호 끊김"
            trendText.setTextColor(COLOR_ALERT)
            trendSub.text = "${ageSec}초째 수신되지 않습니다. 신호가 꺼졌거나 범위를 벗어났을 수 있습니다."
        } else {
            when (t.trend(now)) {
                Trend.APPROACHING -> {
                    trendText.text = "▲ 가까워지는 중"
                    trendText.setTextColor(COLOR_GOOD)
                }
                Trend.RECEDING -> {
                    trendText.text = "▼ 멀어지는 중"
                    trendText.setTextColor(COLOR_ALERT)
                }
                Trend.STABLE -> {
                    trendText.text = "● 변화 없음"
                    trendText.setTextColor(COLOR_SUB)
                }
                Trend.UNKNOWN -> {
                    trendText.text = "… 추세 계산 중"
                    trendText.setTextColor(COLOR_SUB)
                }
            }
            trendSub.text = if (slope != null) {
                String.format("세기 변화율 %+.2f dB/초 (양수=강해짐)", slope)
            } else {
                "측정값을 더 모으는 중입니다."
            }
        }

        // 거리
        val d = t.distanceMeters()
        distText.text = if (d != null) {
            val ds = if (d < 10) String.format("%.1f", d) else d.roundToInt().toString()
            "대략 ${ds}m · ${t.proximityLabel()}  (거리 추정은 오차가 매우 큽니다)"
        } else {
            ""
        }

        // 방향
        updateDirectionUi(t, now)
        compass.invalidate()
    }

    private fun updateDirectionUi(t: SignalTracker, now: Long) {
        if (rotationSensor == null) {
            compass.estimateBearing = null
            dirText.text = "방향 센서가 없는 기기입니다."
            dirHint.text = "방향 추정은 사용할 수 없고, 세기 변화(가까워짐/멀어짐)만 이용할 수 있습니다."
            return
        }
        val dir = t.direction(now)
        val covered = (0 until SignalTracker.BINS).count {
            !t.binEma[it].isNaN() && now - t.binTime[it] <= SignalTracker.BIN_STALE_MS
        }
        if (dir == null) {
            compass.estimateBearing = null
            dirText.text = "방향 측정 중… ($covered/${SignalTracker.BINS}방향)"
            dirHint.text = "휴대폰을 몸에서 조금 떨어뜨려 든 채, 제자리에서 천천히 한 바퀴(360°) 돌아보세요. " +
                "돌면서 방향별 신호 세기를 모읍니다."
            return
        }
        compass.estimateBearing = dir.bearingDeg

        val heading = currentHeading() ?: 0.0
        val diff = ((dir.bearingDeg - heading + 540.0) % 360.0) - 180.0
        val conf = when (dir.confidence) {
            2 -> "높음"
            1 -> "보통"
            else -> "낮음"
        }
        val guide = when {
            abs(diff) < 20.0 -> "지금 정면 쪽입니다. 그대로 앞으로 이동하세요."
            diff > 0 -> "오른쪽으로 약 ${diff.roundToInt()}° 돌아보세요."
            else -> "왼쪽으로 약 ${(-diff).roundToInt()}° 돌아보세요."
        }
        dirText.text = "${bearingName(dir.bearingDeg)}쪽 ${dir.bearingDeg.roundToInt()}° · 정확도 $conf · $guide"

        var hint = "측정된 방향 ${dir.coveredBins}/${SignalTracker.BINS} · 화면 위쪽이 휴대폰이 향한 방향, " +
            "빨강일수록 신호가 강한 방향입니다."
        if (dir.confidence == 0) {
            hint += " 방향 간 세기 차이가 작아 신뢰도가 낮습니다. 더 천천히 한 바퀴 더 돌아보세요."
        }
        if (sensorAccuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
            hint += " (나침반 보정이 필요합니다: 휴대폰을 8자 모양으로 흔들어 주세요.)"
        }
        dirHint.text = hint
    }

    // ------------------------------------------------------------------
    // 방향 센서 (회전 벡터 → 휴대폰이 향한 방위)
    // ------------------------------------------------------------------

    private fun registerSensor() {
        val sensor = rotationSensor ?: return
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun unregisterSensor() {
        sensorManager?.unregisterListener(this)
    }

    private fun currentHeading(): Double? {
        if (!hasHeading) return null
        return (Math.toDegrees(atan2(headingSin, headingCos)) + 360.0) % 360.0
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rot, event.values)

        // 휴대폰을 세워 들면 뒷면(카메라 방향), 눕혀 들면 윗면이 향한 방위를 사용한다.
        val upright = hypot(rot[2].toDouble(), rot[5].toDouble()) > 0.5
        val h = if (upright) {
            atan2(-rot[2].toDouble(), -rot[5].toDouble())
        } else {
            atan2(rot[1].toDouble(), rot[4].toDouble())
        }

        val a = if (hasHeading) 0.25 else 1.0
        headingSin += a * (sin(h) - headingSin)
        headingCos += a * (cos(h) - headingCos)
        hasHeading = true

        compass.heading = (currentHeading() ?: 0.0).toFloat()
        compass.invalidate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        sensorAccuracy = accuracy
    }
}
