package com.michat.automator.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import androidx.core.content.ContextCompat
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.michat.automator.R
import com.michat.automator.data.HistoryManager
import com.michat.automator.ui.HistoryActivity
import kotlin.math.abs

class FloatingOverlayService : Service() {

    companion object {
        var instance: FloatingOverlayService? = null

        fun showTargetMarker(rect: Rect, name: String, gender: String) {
            instance?.showMarker(rect, name, gender)
        }

        fun hideTargetMarker() {
            instance?.hideMarker()
        }

        /** Posisi widget di layar (untuk menghindari gesture bot menabrak widget) */
        fun widgetBoundsOnScreen(): Rect? = instance?.getWidgetBounds()
    }

    private fun getWidgetBounds(): Rect? {
        if (!::floatingView.isInitialized || !floatingView.isAttachedToWindow) return null
        val loc = IntArray(2)
        floatingView.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + floatingView.width, loc[1] + floatingView.height)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var windowParams: WindowManager.LayoutParams

    // Marker Overlay Views
    private lateinit var markerView: View
    private lateinit var markerBadge: TextView
    private lateinit var markerParams: WindowManager.LayoutParams

    // Container Views
    private lateinit var layoutExpanded: LinearLayout
    private lateinit var layoutBubble: LinearLayout

    // Header Views
    private lateinit var headerView: View
    private lateinit var btnMinimize: Button
    private lateinit var btnClose: Button

    // Status & Progress Views
    private lateinit var tvStatusDot: TextView
    private lateinit var tvStatusBadge: TextView
    private lateinit var tvCounter: TextView
    private lateinit var pbProgress: ProgressBar
    private lateinit var tvStatusTicker: TextView

    // Tags
    private lateinit var tvTagGender: TextView
    private lateinit var tvTagMode: TextView
    private lateinit var tvTagDelay: TextView

    // Expanded Controls
    private lateinit var btnToggleStart: Button
    private lateinit var btnWidgetHistory: Button
    private lateinit var btnWidgetReset: Button

    // Mini History Views
    private lateinit var layoutMiniHistory: LinearLayout
    private lateinit var tvMiniHistoryContent: TextView
    private lateinit var tvBtnOpenFullHistory: TextView

    // Bubble Views
    private lateinit var tvBubbleStatusDot: TextView
    private lateinit var tvBubbleCounter: TextView
    private lateinit var btnBubbleToggle: Button
    private lateinit var btnBubbleExpand: Button

    private var isExpanded = true
    private var isMiniHistoryVisible = false

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Jalankan sebagai foreground service (ada notifikasi) supaya sistem/MIUI tidak mudah
     * membunuh proses aplikasi. Layanan aksesibilitas berada di proses yang sama, jadi ikut terjaga.
     */
    private fun startKeepAliveForeground() {
        try {
            val channelId = "michat_auto_keepalive"
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    android.app.NotificationChannel(channelId, "Bot MiChat aktif",
                        android.app.NotificationManager.IMPORTANCE_LOW)
                )
            }
            val openIntent = android.app.PendingIntent.getActivity(
                this, 0,
                Intent(this, com.michat.automator.ui.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("MiChat Push Auto aktif")
                .setContentText("Kontrol melayang siap. Made by Nixxy")
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
                .build()
            val type = if (Build.VERSION.SDK_INT >= 34)
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            androidx.core.app.ServiceCompat.startForeground(this, 4201, notification, type)
        } catch (e: Throwable) {
            android.util.Log.w("FloatingOverlay", "startForeground gagal: ${e.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        startKeepAliveForeground()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_widget, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 180
        }

        windowManager.addView(floatingView, windowParams)

        initViews()
        initMarkerView()
        registerDebugMarkerReceiver()
        setupListeners()
        setupDragTouch()
        refreshFromSettings()

        // Callback listener dari Automation Service
        MiChatAutomationService.statusListener = { status, count ->
            floatingView.post {
                updateLiveStatus(status, count)
            }
        }

        // Listener perubahan riwayat
        HistoryManager.onHistoryChanged = {
            floatingView.post {
                updateHistoryDisplay()
            }
        }
    }

    private fun initViews() {
        layoutExpanded = floatingView.findViewById(R.id.layout_floating_expanded)
        layoutBubble = floatingView.findViewById(R.id.layout_floating_bubble)

        headerView = floatingView.findViewById(R.id.layout_drag_header)
        btnMinimize = floatingView.findViewById(R.id.btn_minimize_widget)
        btnClose = floatingView.findViewById(R.id.btn_close_widget)

        tvStatusDot = floatingView.findViewById(R.id.tv_widget_status_dot)
        tvStatusBadge = floatingView.findViewById(R.id.tv_widget_status_badge)
        tvCounter = floatingView.findViewById(R.id.tv_widget_counter)
        pbProgress = floatingView.findViewById(R.id.pb_widget_progress)
        tvStatusTicker = floatingView.findViewById(R.id.tv_widget_status_ticker)

        tvTagGender = floatingView.findViewById(R.id.tv_tag_gender)
        tvTagMode = floatingView.findViewById(R.id.tv_tag_mode)
        tvTagDelay = floatingView.findViewById(R.id.tv_tag_delay)

        btnToggleStart = floatingView.findViewById(R.id.btn_toggle_start)
        btnWidgetHistory = floatingView.findViewById(R.id.btn_widget_history)
        btnWidgetReset = floatingView.findViewById(R.id.btn_widget_reset)

        layoutMiniHistory = floatingView.findViewById(R.id.layout_widget_mini_history)
        tvMiniHistoryContent = floatingView.findViewById(R.id.tv_mini_history_content)
        tvBtnOpenFullHistory = floatingView.findViewById(R.id.tv_btn_open_full_history)

        tvBubbleStatusDot = floatingView.findViewById(R.id.tv_bubble_status_dot)
        tvBubbleCounter = floatingView.findViewById(R.id.tv_bubble_counter)
        btnBubbleToggle = floatingView.findViewById(R.id.btn_bubble_toggle)
        btnBubbleExpand = floatingView.findViewById(R.id.btn_bubble_expand)

        pbProgress.max = MiChatAutomationService.maxTargetCount
    }

    private fun setupListeners() {
        // Toggle Bot START / STOP
        val toggleAction = {
            val autoService = MiChatAutomationService.instance
            if (autoService == null) {
                val ctx = this@FloatingOverlayService
                if (com.michat.automator.util.AccessibilityHelper.canSelfRepair(ctx)) {
                    tvStatusTicker.text = "🔧 Mengaktifkan ulang aksesibilitas…"
                    com.michat.automator.util.AccessibilityHelper.repair(ctx) { ok ->
                        tvStatusTicker.text = if (ok) "Aksesibilitas aktif ✓ Tekan Mulai lagi" else
                            "⚠️ Gagal. Aktifkan aksesibilitas di Setelan HP"
                    }
                } else {
                    tvStatusTicker.text = "⚠️ Aktifkan Layanan Aksesibilitas di Setelan HP"
                }
            } else {
                if (MiChatAutomationService.isRunning) {
                    autoService.stopBot()
                } else {
                    autoService.startBot()
                }
                syncRunningState(MiChatAutomationService.isRunning)
            }
        }

        btnToggleStart.setOnClickListener { toggleAction() }
        btnBubbleToggle.setOnClickListener { toggleAction() }

        // Minimize ke Bubble
        btnMinimize.setOnClickListener {
            switchToBubble()
        }

        // Expand ke Panel Penuh
        btnBubbleExpand.setOnClickListener {
            switchToExpanded()
        }

        // Tutup Floating Widget
        btnClose.setOnClickListener {
            MiChatAutomationService.instance?.stopBot()
            stopSelf()
        }

        // Toggle Mini History
        btnWidgetHistory.setOnClickListener {
            isMiniHistoryVisible = !isMiniHistoryVisible
            layoutMiniHistory.visibility = if (isMiniHistoryVisible) View.VISIBLE else View.GONE
            updateHistoryDisplay()
        }

        // Reset Counter
        btnWidgetReset.setOnClickListener {
            MiChatAutomationService.sentCount = 0
            updateLiveStatus("Counter direset ke 0", 0)
        }

        // Buka Activity Riwayat Lengkap
        tvBtnOpenFullHistory.setOnClickListener {
            val intent = Intent(this, HistoryActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }
    }

    private fun switchToBubble() {
        isExpanded = false
        layoutExpanded.visibility = View.GONE
        layoutBubble.visibility = View.VISIBLE
        windowManager.updateViewLayout(floatingView, windowParams)
    }

    private fun switchToExpanded() {
        isExpanded = true
        layoutBubble.visibility = View.GONE
        layoutExpanded.visibility = View.VISIBLE
        windowManager.updateViewLayout(floatingView, windowParams)
    }

    private fun syncRunningState(running: Boolean) {
        if (running) {
            btnToggleStart.text = "⏹  STOP BOT"
            btnToggleStart.setBackgroundResource(R.drawable.btn_gradient_stop)
            btnBubbleToggle.text = "⏸"
            btnBubbleToggle.setTextColor(Color.parseColor("#FF5252"))

            tvStatusDot.setTextColor(Color.parseColor("#00E676"))
            tvBubbleStatusDot.setTextColor(Color.parseColor("#00E676"))
            tvStatusBadge.text = "BERJALAN"
            tvStatusBadge.setTextColor(Color.parseColor("#00E676"))
        } else {
            btnToggleStart.text = "▶  MULAI BOT"
            btnToggleStart.setBackgroundResource(R.drawable.btn_gradient_start)
            btnBubbleToggle.text = "▶"
            btnBubbleToggle.setTextColor(Color.parseColor("#00E676"))

            tvStatusDot.setTextColor(Color.parseColor("#78909C"))
            tvBubbleStatusDot.setTextColor(Color.parseColor("#78909C"))
            tvStatusBadge.text = "IDLE"
            tvStatusBadge.setTextColor(Color.parseColor("#CFD8DC"))
        }
    }

    private fun updateLiveStatus(status: String, count: Int) {
        tvStatusTicker.text = status
        val max = MiChatAutomationService.maxTargetCount
        tvCounter.text = "$count / $max"
        tvBubbleCounter.text = "$count/$max"
        pbProgress.max = max
        pbProgress.progress = count

        if (status.contains("Jeda", ignoreCase = true)) {
            tvStatusBadge.text = "JEDA"
            tvStatusBadge.setTextColor(Color.parseColor("#FFD54F"))
            tvStatusDot.setTextColor(Color.parseColor("#FFD54F"))
            tvBubbleStatusDot.setTextColor(Color.parseColor("#FFD54F"))
        } else if (MiChatAutomationService.isRunning) {
            tvStatusBadge.text = "BERJALAN"
            tvStatusBadge.setTextColor(Color.parseColor("#00E676"))
            tvStatusDot.setTextColor(Color.parseColor("#00E676"))
            tvBubbleStatusDot.setTextColor(Color.parseColor("#00E676"))
        } else {
            syncRunningState(false)
        }
    }

    /** Sinkronkan tampilan widget dengan pengaturan terbaru dari layar utama */
    private fun refreshFromSettings() {
        updateFilterTags()
        updateHistoryDisplay()
        val max = MiChatAutomationService.maxTargetCount
        val count = MiChatAutomationService.sentCount
        tvCounter.text = "$count / $max"
        tvBubbleCounter.text = "$count/$max"
        pbProgress.max = max
        pbProgress.progress = count
        syncRunningState(MiChatAutomationService.isRunning)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Dipanggil lagi saat tombol Mulai ditekan ketika widget sudah tampil
        if (::tvCounter.isInitialized) refreshFromSettings()
        return START_NOT_STICKY
    }

    private fun updateFilterTags() {
        val target = MiChatAutomationService.targetGender
        when (target) {
            MiChatAutomationService.TargetGender.FEMALE -> {
                tvTagGender.text = "♀ Perempuan"
                tvTagGender.setTextColor(Color.parseColor("#FF4081"))
            }
            MiChatAutomationService.TargetGender.MALE -> {
                tvTagGender.text = "♂ Laki-laki"
                tvTagGender.setTextColor(Color.parseColor("#448AFF"))
            }
            MiChatAutomationService.TargetGender.ALL -> {
                tvTagGender.text = "⚥ Semua"
                tvTagGender.setTextColor(Color.parseColor("#E0E0E0"))
            }
        }

        tvTagMode.text = if (MiChatAutomationService.defaultGreetingOnly) "⚡ Sapa Saja" else "✉ Custom"
        tvTagDelay.text = "⏱ ${MiChatAutomationService.minDelaySec}-${MiChatAutomationService.maxDelaySec}s"
    }

    private fun updateHistoryDisplay() {
        val totalCount = HistoryManager.getCount(this)
        btnWidgetHistory.text = "📜 Riwayat ($totalCount)"

        val records = HistoryManager.getRecords(this)
        if (records.isEmpty()) {
            tvMiniHistoryContent.text = "Belum ada kontak yang disapa."
        } else {
            val sb = StringBuilder()
            val recent = records.take(3)
            for (item in recent) {
                val icon = if (item.gender.contains("Perempuan", true)) "♀" else if (item.gender.contains("Laki", true)) "♂" else "•"
                sb.append("$icon ${item.nickname} (${item.timeStr.substringAfter(", ")})\n")
            }
            tvMiniHistoryContent.text = sb.toString().trimEnd()
        }
    }

    private fun setupDragTouch() {
        setupDragForView(headerView)
        val bubbleDrag = floatingView.findViewById<View>(R.id.layout_bubble_drag)
        if (bubbleDrag != null) {
            setupDragForView(bubbleDrag)
        }
    }

    private fun setupDragForView(targetView: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        targetView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = windowParams.x
                    initialY = windowParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        isDragging = true
                        windowParams.x = initialX + dx.toInt()
                        windowParams.y = initialY + dy.toInt()
                        windowManager.updateViewLayout(floatingView, windowParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Jika klik pada bubble, langsung expand
                        if (targetView.id == R.id.layout_bubble_drag) {
                            switchToExpanded()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun initMarkerView() {
        markerView = LayoutInflater.from(this).inflate(R.layout.layout_selection_marker, null)
        markerBadge = markerView.findViewById(R.id.tv_marker_badge)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        markerParams = WindowManager.LayoutParams(
            10,
            10,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // Koordinat window = koordinat layar penuh (termasuk area status bar / notch)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        markerView.visibility = View.GONE
        windowManager.addView(markerView, markerParams)
    }

    // Selisih antara koordinat window overlay dan koordinat layar (status bar, notch, dll).
    // Diukur dari posisi nyata marker di layar, lalu dipakai untuk mengoreksi posisi berikutnya.
    private var markerOffsetX = 0
    private var markerOffsetY = 0
    private var markerShowToken = 0

    fun showMarker(rect: Rect, name: String, gender: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                if (!::markerView.isInitialized) return@post
                val token = ++markerShowToken
                // Garis border digambar di DALAM view, jadi ukuran view = ukuran item persis
                val targetX = rect.left
                val targetY = rect.top
                markerParams.width = rect.width().coerceAtLeast(50)
                markerParams.height = rect.height().coerceAtLeast(50)
                markerParams.x = targetX - markerOffsetX
                markerParams.y = targetY - markerOffsetY

                val genderIcon = if (gender.contains("Perempuan", true)) "♀" else if (gender.contains("Laki", true)) "♂" else ""
                markerBadge.text = "🎯 $name $genderIcon"
                markerView.visibility = View.VISIBLE
                windowManager.updateViewLayout(markerView, markerParams)

                // Setelah ter-layout, cek posisi aslinya dan koreksi bila meleset
                markerView.post {
                    if (token != markerShowToken || markerView.visibility != View.VISIBLE) return@post
                    val loc = IntArray(2)
                    markerView.getLocationOnScreen(loc)
                    val dx = loc[0] - targetX
                    val dy = loc[1] - targetY
                    if (dx != 0 || dy != 0) {
                        markerOffsetX += dx
                        markerOffsetY += dy
                        markerParams.x = targetX - markerOffsetX
                        markerParams.y = targetY - markerOffsetY
                        windowManager.updateViewLayout(markerView, markerParams)
                    }
                    android.util.Log.d("MiChatMarker", "target=($targetX,$targetY) actual=(${loc[0]},${loc[1]}) offset=($markerOffsetX,$markerOffsetY)")
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    fun hideMarker() {
        Handler(Looper.getMainLooper()).post {
            try {
                if (::markerView.isInitialized && markerView.visibility != View.GONE) {
                    markerView.visibility = View.GONE
                    windowManager.updateViewLayout(markerView, markerParams)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    // Hanya untuk build debug: tampilkan marker lewat adb untuk cek posisi tanpa menjalankan bot.
    // adb shell am broadcast -a com.michat.automator.DEBUG_MARKER --ei l 0 --ei t 400 --ei r 1080 --ei b 576
    private var debugMarkerReceiver: BroadcastReceiver? = null

    private fun registerDebugMarkerReceiver() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                if (intent.getBooleanExtra("hide", false)) { hideMarker(); return }
                val r = Rect(intent.getIntExtra("l", 0), intent.getIntExtra("t", 0),
                    intent.getIntExtra("r", 0), intent.getIntExtra("b", 0))
                showMarker(r, "Debug", "")
            }
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter("com.michat.automator.DEBUG_MARKER"),
            ContextCompat.RECEIVER_EXPORTED)
        debugMarkerReceiver = receiver
    }

    override fun onDestroy() {
        debugMarkerReceiver?.let { try { unregisterReceiver(it) } catch (_: Throwable) {} }
        super.onDestroy()
        MiChatAutomationService.statusListener = null
        HistoryManager.onHistoryChanged = null
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
        if (::markerView.isInitialized) {
            try {
                windowManager.removeView(markerView)
            } catch (e: Throwable) {}
        }
        if (instance == this) {
            instance = null
        }
    }
}
