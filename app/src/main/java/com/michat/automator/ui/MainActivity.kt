package com.michat.automator.ui

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.michat.automator.R
import com.michat.automator.data.HistoryManager
import com.michat.automator.service.FloatingOverlayService
import com.michat.automator.service.MiChatAutomationService
import com.michat.automator.service.MiChatAutomationService.TargetGender
import com.michat.automator.util.AccessibilityHelper
import com.michat.automator.license.LicenseManager
import java.util.Random

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "nx_main_settings"
        private const val DEFAULT_TEMPLATE = "{Halo|Hai|Pagi} kak, {salam kenal ya|lagi santai?}"
    }

    private lateinit var prefs: SharedPreferences

    private lateinit var contentMain: LinearLayout
    private lateinit var tvStatusChip: TextView
    private lateinit var tvStatHistory: TextView
    private lateinit var tvStatTarget: TextView
    private lateinit var tvStatTargetLabel: TextView
    private lateinit var tvStatLimit: TextView

    private lateinit var tvPermSub: TextView
    private lateinit var tvPermOverlayIcon: TextView
    private lateinit var tvPermOverlayDesc: TextView
    private lateinit var tvPermAccessIcon: TextView
    private lateinit var tvPermAccessDesc: TextView

    private lateinit var toggleGender: MaterialButtonToggleGroup
    private lateinit var swAnalyzeIcon: SwitchMaterial
    private lateinit var swAnalyzeName: SwitchMaterial
    private lateinit var swSkipFriends: SwitchMaterial
    private lateinit var swOnlySapa: SwitchMaterial
    private lateinit var swDefaultGreeting: SwitchMaterial

    private lateinit var cardMessage: LinearLayout
    private lateinit var layoutCustomMessage: LinearLayout
    private lateinit var etMessageTemplate: TextInputEditText
    private lateinit var tvMessagePreview: TextView

    private lateinit var sliderDelay: RangeSlider
    private lateinit var sliderTarget: Slider
    private lateinit var tvDelayValue: TextView
    private lateinit var tvTargetValue: TextView

    private lateinit var tvHistoryCount: TextView
    private lateinit var btnLaunch: MaterialButton

    private var shownHistoryCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_MiChatAutomator_Main)
        super.onCreate(savedInstanceState)
        // Gerbang lisensi: tanpa lisensi valid, app hanya menampilkan layar aktivasi
        if (!LicenseManager.isValid(this)) {
            ActivationActivity.open(this)
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        bindViews()
        applyResponsiveWidth()
        restoreSettings()
        setupInteractions()
        applyLicense()
        refreshDerivedLabels()
        playEntranceAnimation()
    }

    override fun onResume() {
        super.onResume()
        if (!::btnLaunch.isInitialized) return
        LicenseManager.onLicenseChanged = { onLicenseChanged() }
        if (!LicenseManager.isValid(this)) { lockApp(); return }
        applyLicense()
        LicenseManager.refreshIfDue(this)
        refreshPermissionState()
        // Beri sistem waktu mengikat layanan sendiri dulu; jika tetap tidak jalan, pulihkan otomatis
        contentMain.postDelayed({
            if (!isFinishing) {
                refreshPermissionState()
                autoRepairAccessibility()
            }
        }, 1200)
        animateHistoryCount(HistoryManager.getCount(this))
    }

    // ---------------------------------------------------------------- setup

    private fun bindViews() {
        contentMain = findViewById(R.id.content_main)
        tvStatusChip = findViewById(R.id.tv_status_chip)
        tvStatHistory = findViewById(R.id.tv_stat_history)
        tvStatTarget = findViewById(R.id.tv_stat_target)
        tvStatTargetLabel = findViewById(R.id.tv_stat_target_label)
        tvStatLimit = findViewById(R.id.tv_stat_limit)

        tvPermSub = findViewById(R.id.tv_perm_sub)
        tvPermOverlayIcon = findViewById(R.id.tv_perm_overlay_icon)
        tvPermOverlayDesc = findViewById(R.id.tv_perm_overlay_desc)
        tvPermAccessIcon = findViewById(R.id.tv_perm_access_icon)
        tvPermAccessDesc = findViewById(R.id.tv_perm_access_desc)

        toggleGender = findViewById(R.id.toggle_gender)
        swAnalyzeIcon = findViewById(R.id.sw_analyze_icon)
        swAnalyzeName = findViewById(R.id.sw_analyze_name)
        swSkipFriends = findViewById(R.id.sw_skip_friends)
        swOnlySapa = findViewById(R.id.sw_only_sapa)
        swDefaultGreeting = findViewById(R.id.sw_default_greeting)

        cardMessage = findViewById(R.id.card_message)
        layoutCustomMessage = findViewById(R.id.layout_custom_message)
        etMessageTemplate = findViewById(R.id.et_message_template)
        tvMessagePreview = findViewById(R.id.tv_message_preview)

        sliderDelay = findViewById(R.id.slider_delay)
        sliderTarget = findViewById(R.id.slider_target)
        tvDelayValue = findViewById(R.id.tv_delay_value)
        tvTargetValue = findViewById(R.id.tv_target_value)

        tvHistoryCount = findViewById(R.id.tv_main_history_count)
        btnLaunch = findViewById(R.id.btn_launch_overlay)

        findViewById<TextView>(R.id.tv_version).text = try {
            "v" + packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "v1.0"
        }
    }

    /** Tablet / landscape: konten dibatasi max 640dp dan di tengah supaya tetap enak dibaca */
    private fun applyResponsiveWidth() {
        val cfg = resources.configuration
        val maxWidthDp = 640
        if (cfg.screenWidthDp > maxWidthDp + 32) {
            val px = (maxWidthDp * resources.displayMetrics.density).toInt()
            contentMain.layoutParams = (contentMain.layoutParams as FrameLayout.LayoutParams).apply {
                width = px
            }
            val btnParent = btnLaunch.layoutParams as FrameLayout.LayoutParams
            btnParent.width = px - (32 * resources.displayMetrics.density).toInt()
            btnLaunch.layoutParams = btnParent
        }
    }

    private fun restoreSettings() {
        when (prefs.getString("target", TargetGender.FEMALE.name)) {
            TargetGender.MALE.name -> toggleGender.check(R.id.btn_gender_male)
            TargetGender.ALL.name -> toggleGender.check(R.id.btn_gender_all)
            else -> toggleGender.check(R.id.btn_gender_female)
        }
        swAnalyzeIcon.isChecked = prefs.getBoolean("icon", true)
        swAnalyzeName.isChecked = prefs.getBoolean("name", true)
        swSkipFriends.isChecked = prefs.getBoolean("skip_friends", true)
        swOnlySapa.isChecked = prefs.getBoolean("only_sapa", true)
        swDefaultGreeting.isChecked = prefs.getBoolean("default_greeting", true)
        layoutCustomMessage.visibility = if (swDefaultGreeting.isChecked) View.GONE else View.VISIBLE
        etMessageTemplate.setText(prefs.getString("template", DEFAULT_TEMPLATE))

        val min = prefs.getInt("min_delay", 3).coerceIn(1, 30).toFloat()
        val max = prefs.getInt("max_delay", 6).coerceIn(1, 30).toFloat()
        sliderDelay.values = listOf(minOf(min, max), maxOf(min, max))
        sliderTarget.value = prefs.getInt("max_target", 20).coerceIn(1, 200).toFloat()
    }

    private fun setupInteractions() {
        toggleGender.addOnButtonCheckedListener { group, _, isChecked ->
            if (isChecked) {
                group.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                refreshDerivedLabels()
                pop(tvStatTarget)
                saveSettings()
            }
        }

        listOf(swAnalyzeIcon, swAnalyzeName, swSkipFriends, swOnlySapa).forEach { sw ->
            sw.setOnCheckedChangeListener { v, _ ->
                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                saveSettings()
            }
        }

        swDefaultGreeting.setOnCheckedChangeListener { v, isChecked ->
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            TransitionManager.beginDelayedTransition(
                findViewById<ViewGroup>(R.id.content_main),
                AutoTransition().setDuration(220)
            )
            layoutCustomMessage.visibility = if (isChecked) View.GONE else View.VISIBLE
            saveSettings()
        }

        etMessageTemplate.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateMessagePreview()
                saveSettings()
            }
        })
        findViewById<View>(R.id.btn_shuffle).setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            updateMessagePreview()
            pop(tvMessagePreview)
        }

        sliderDelay.addOnChangeListener { slider, _, fromUser ->
            refreshDerivedLabels()
            if (fromUser) slider.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        sliderDelay.addOnSliderTouchListener(object : RangeSlider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: RangeSlider) {}
            override fun onStopTrackingTouch(slider: RangeSlider) = saveSettings()
        })
        sliderTarget.addOnChangeListener { slider, _, fromUser ->
            refreshDerivedLabels()
            if (fromUser) slider.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        sliderTarget.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) = saveSettings()
        })

        findViewById<View>(R.id.row_perm_overlay).setOnClickListener { openOverlaySettings() }
        findViewById<View>(R.id.row_perm_access).setOnClickListener { openAccessibilitySettings() }

        val openHistory = View.OnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<View>(R.id.layout_btn_history).setOnClickListener(openHistory)
        findViewById<View>(R.id.tile_history).setOnClickListener(openHistory)

        findViewById<View>(R.id.layout_credit).setOnClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val credit = findViewById<TextView>(R.id.tv_credit)
            credit.setTextColor(ContextCompat.getColor(this, R.color.nx_primary))
            credit.text = "Made with 💚 by Nixxy"
            pop(credit)
        }

        btnLaunch.setOnClickListener { launchBot() }
        findViewById<View>(R.id.row_license).setOnClickListener { showLicenseDialog() }
        updateMessagePreview()
    }

    // ---------------------------------------------------------------- lisensi

    override fun onPause() {
        super.onPause()
        LicenseManager.onLicenseChanged = null
    }

    private fun onLicenseChanged() {
        if (isFinishing) return
        if (!LicenseManager.isValid(this)) lockApp() else applyLicense()
    }

    private fun lockApp() {
        MiChatAutomationService.instance?.stopBot()
        ActivationActivity.open(this)
        finish()
    }

    /** Terapkan fitur paket: kunci opsi yang tidak termasuk & batasi limit sapaan */
    private fun applyLicense() {
        val lic = LicenseManager.current(this) ?: return
        val f = lic.features
        findViewById<TextView>(R.id.tv_license_plan).text = "💎 ${lic.planName}"
        findViewById<TextView>(R.id.tv_license_remaining).text = LicenseManager.remainingText(this)

        swAnalyzeIcon.isEnabled = f.genderIcon
        swAnalyzeIcon.text = if (f.genderIcon) "Cek ikon gender di profil" else "Cek ikon gender di profil 🔒"
        if (!f.genderIcon) swAnalyzeIcon.isChecked = false

        swDefaultGreeting.isEnabled = f.customMessage
        swDefaultGreeting.text = if (f.customMessage) "Pakai sapaan bawaan MiChat" else "Pakai sapaan bawaan (pesan custom 🔒)"
        if (!f.customMessage) swDefaultGreeting.isChecked = true

        val max = f.maxTarget.coerceAtLeast(2).toFloat()
        if (sliderTarget.value > max) sliderTarget.value = max
        sliderTarget.valueTo = max
        refreshDerivedLabels()
    }

    private fun showLicenseDialog() {
        val lic = LicenseManager.current(this) ?: return
        val f = lic.features
        val masked = lic.key.take(8) + "••••-" + lic.key.takeLast(4)
        val exp = lic.expiresAt?.let {
            java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale("id", "ID")).format(java.util.Date(it))
        } ?: "Permanen"
        val yes = "✓"; val no = "🔒"
        val msg = "Key: $masked\n" +
            "Paket: ${lic.planName}\n" +
            "Berlaku s/d: $exp\n" +
            "Sisa: ${LicenseManager.remainingText(this)}\n\n" +
            "Fitur:\n" +
            "${if (f.genderIcon) yes else no} Cek ikon gender\n" +
            "${if (f.customMessage) yes else no} Pesan custom\n" +
            "• Maks. ${f.maxTarget} sapaan/sesi\n\n" +
            "Device ID: ${LicenseManager.deviceId(this)}"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Lisensi")
            .setMessage(msg)
            .setPositiveButton("Cek sekarang") { _, _ ->
                LicenseManager.refreshIfDue(this, force = true) { res ->
                    val text = when (res) {
                        is LicenseManager.Result.Ok -> "Lisensi diperbarui ✓"
                        is LicenseManager.Result.Fail -> res.message
                        null -> "Sedang memeriksa…"
                    }
                    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Ganti key") { _, _ ->
                LicenseManager.signOut(this)
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    // ---------------------------------------------------------------- state

    private fun selectedTarget(): TargetGender = when (toggleGender.checkedButtonId) {
        R.id.btn_gender_male -> TargetGender.MALE
        R.id.btn_gender_all -> TargetGender.ALL
        else -> TargetGender.FEMALE
    }

    private fun refreshDerivedLabels() {
        val values = sliderDelay.values
        val min = values[0].toInt()
        val max = values.getOrElse(1) { values[0] }.toInt()
        tvDelayValue.text = if (min == max) "$min dtk" else "$min – $max dtk"

        val limit = sliderTarget.value.toInt()
        tvTargetValue.text = "$limit kontak"
        tvStatLimit.text = limit.toString()

        when (selectedTarget()) {
            TargetGender.FEMALE -> {
                tvStatTarget.text = "♀"
                tvStatTarget.setTextColor(ContextCompat.getColor(this, R.color.nx_pink))
                tvStatTargetLabel.text = "Target cewek"
            }
            TargetGender.MALE -> {
                tvStatTarget.text = "♂"
                tvStatTarget.setTextColor(ContextCompat.getColor(this, R.color.nx_blue))
                tvStatTargetLabel.text = "Target cowok"
            }
            TargetGender.ALL -> {
                tvStatTarget.text = "⚥"
                tvStatTarget.setTextColor(ContextCompat.getColor(this, R.color.nx_accent))
                tvStatTargetLabel.text = "Semua gender"
            }
        }
    }

    private fun saveSettings() {
        val values = sliderDelay.values
        prefs.edit()
            .putString("target", selectedTarget().name)
            .putBoolean("icon", swAnalyzeIcon.isChecked)
            .putBoolean("name", swAnalyzeName.isChecked)
            .putBoolean("skip_friends", swSkipFriends.isChecked)
            .putBoolean("only_sapa", swOnlySapa.isChecked)
            .putBoolean("default_greeting", swDefaultGreeting.isChecked)
            .putString("template", etMessageTemplate.text?.toString() ?: DEFAULT_TEMPLATE)
            .putInt("min_delay", values[0].toInt())
            .putInt("max_delay", values.getOrElse(1) { values[0] }.toInt())
            .putInt("max_target", sliderTarget.value.toInt())
            .apply()
    }

    private fun updateMessagePreview() {
        val tpl = etMessageTemplate.text?.toString()?.trim().orEmpty()
        tvMessagePreview.text = if (tpl.isEmpty()) "Tulis template dulu…" else "“${parseSpintax(tpl)}”"
    }

    private fun hasOverlayPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    /** Benar hanya jika layanan BENAR-BENAR berjalan (bukan sekadar tercatat aktif di Setelan) */
    private fun isAccessibilityEnabled(): Boolean = AccessibilityHelper.isRunning()

    private var repairInFlight = false
    private var lastRepairAt = 0L

    /**
     * Dipanggil saat app dibuka/kembali. Jika layanan tidak berjalan (mis. setelah app ditutup paksa),
     * aktifkan ulang otomatis bila izin WRITE_SECURE_SETTINGS sudah diberikan.
     */
    private fun autoRepairAccessibility() {
        if (AccessibilityHelper.isRunning() || repairInFlight) return
        if (!AccessibilityHelper.canSelfRepair(this)) return
        val now = System.currentTimeMillis()
        if (now - lastRepairAt < 5000) return
        lastRepairAt = now
        repairInFlight = true
        tvPermAccessDesc.text = "Mengaktifkan ulang otomatis…"
        tvPermAccessDesc.setTextColor(ContextCompat.getColor(this, R.color.nx_warn))
        AccessibilityHelper.repair(this) { ok ->
            repairInFlight = false
            if (isFinishing) return@repair
            refreshPermissionState()
            if (ok) Toast.makeText(this, "Aksesibilitas aktif kembali ✓", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshPermissionState() {
        val overlay = hasOverlayPermission()
        val a11yState = AccessibilityHelper.state(this)
        val access = a11yState == AccessibilityHelper.State.RUNNING
        stylePermission(tvPermOverlayIcon, tvPermOverlayDesc, overlay, "Untuk tombol kontrol melayang")
        val accessHint = when {
            a11yState == AccessibilityHelper.State.STUCK && !AccessibilityHelper.canSelfRepair(this) ->
                "Terputus setelah app ditutup. Ketuk untuk memperbaiki"
            a11yState == AccessibilityHelper.State.STUCK -> "Terputus, mengaktifkan ulang…"
            else -> "Untuk membaca & menekan layar MiChat"
        }
        stylePermission(tvPermAccessIcon, tvPermAccessDesc, access, accessHint)
        if (access) {
            tvPermAccessDesc.text = if (AccessibilityHelper.canSelfRepair(this))
                "Aktif · pulih otomatis jika app ditutup" else "Aktif"
        }

        val ready = overlay && access
        val doneCount = listOf(overlay, access).count { it }
        tvPermSub.text = if (ready) "Semua izin aktif — siap jalan ✨" else "$doneCount dari 2 izin aktif. Ketuk untuk mengaktifkan."

        val chipColor = ContextCompat.getColor(this, if (ready) R.color.nx_primary else R.color.nx_warn)
        tvStatusChip.text = if (MiChatAutomationService.isRunning) "● Berjalan" else if (ready) "● Siap" else "● Perlu izin"
        tvStatusChip.setTextColor(chipColor)
        tvStatusChip.backgroundTintList = ColorStateList.valueOf(withAlpha(chipColor, 0x26))
    }

    private fun stylePermission(icon: TextView, desc: TextView, granted: Boolean, defaultDesc: String) {
        val color = ContextCompat.getColor(this, if (granted) R.color.nx_primary else R.color.nx_warn)
        icon.text = if (granted) "✓" else "!"
        icon.setTextColor(color)
        icon.backgroundTintList = ColorStateList.valueOf(withAlpha(color, 0x2E))
        desc.text = if (granted) "Aktif" else defaultDesc
        desc.setTextColor(
            ContextCompat.getColor(this, if (granted) R.color.nx_primary else R.color.nx_text_3)
        )
    }

    // ---------------------------------------------------------------- actions

    private fun openOverlaySettings() {
        if (hasOverlayPermission()) {
            Toast.makeText(this, "Izin tampil di atas aplikasi sudah aktif ✓", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun openAccessibilitySettings() {
        if (isAccessibilityEnabled()) {
            Toast.makeText(this, "Layanan aksesibilitas sudah aktif ✓", Toast.LENGTH_SHORT).show()
            return
        }
        if (AccessibilityHelper.canSelfRepair(this)) {
            lastRepairAt = 0L
            autoRepairAccessibility()
            return
        }
        showAutoRepairSetupDialog()
    }

    /** Jelaskan cara mengaktifkan pemulihan otomatis (sekali saja lewat adb) atau buka Setelan manual */
    private fun showAutoRepairSetupDialog() {
        val cmd = AccessibilityHelper.adbGrantCommand(this)
        val stuck = AccessibilityHelper.state(this) == AccessibilityHelper.State.STUCK
        val manual = if (stuck)
            "Layanan tercatat aktif tapi terputus. Di Setelan, MATIKAN lalu HIDUPKAN lagi 'MiChat Push Auto'."
        else
            "Di Setelan, aktifkan 'MiChat Push Auto'."
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Aksesibilitas tidak berjalan")
            .setMessage(
                "$manual\n\n" +
                "Supaya tidak perlu begini lagi setiap app ditutup, jalankan perintah ini SEKALI dari komputer " +
                "(HP tersambung USB debugging):\n\n$cmd\n\n" +
                "Setelah itu app akan mengaktifkan ulang aksesibilitas sendiri saat dibuka."
            )
            .setPositiveButton("Buka Setelan") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNeutralButton("Salin perintah") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd))
                Toast.makeText(this, "Perintah disalin", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun launchBot() {
        btnLaunch.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        pop(btnLaunch)

        if (!LicenseManager.isValid(this)) { lockApp(); return }

        if (!hasOverlayPermission()) {
            shakeAndScrollTo(R.id.card_permissions)
            Toast.makeText(this, "Aktifkan izin tampil di atas aplikasi dulu", Toast.LENGTH_SHORT).show()
            openOverlaySettings()
            return
        }
        if (!isAccessibilityEnabled()) {
            shakeAndScrollTo(R.id.card_permissions)
            if (AccessibilityHelper.canSelfRepair(this)) {
                Toast.makeText(this, "Mengaktifkan ulang aksesibilitas, coba lagi sebentar…", Toast.LENGTH_SHORT).show()
                lastRepairAt = 0L
                autoRepairAccessibility()
            } else {
                showAutoRepairSetupDialog()
            }
            return
        }
        if (!swDefaultGreeting.isChecked && etMessageTemplate.text.isNullOrBlank()) {
            shakeAndScrollTo(R.id.card_message)
            etMessageTemplate.error = "Template tidak boleh kosong"
            return
        }

        saveSettings()
        val values = sliderDelay.values
        MiChatAutomationService.targetGender = selectedTarget()
        MiChatAutomationService.enableAnalyzeByIcon = swAnalyzeIcon.isChecked
        MiChatAutomationService.enableAnalyzeByName = swAnalyzeName.isChecked
        MiChatAutomationService.skipFriends = swSkipFriends.isChecked
        MiChatAutomationService.onlySapaDia = swOnlySapa.isChecked
        MiChatAutomationService.skipAlreadySentInHistory = true
        MiChatAutomationService.defaultGreetingOnly = swDefaultGreeting.isChecked
        MiChatAutomationService.messageTemplate =
            etMessageTemplate.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_TEMPLATE
        MiChatAutomationService.minDelaySec = values[0].toInt()
        MiChatAutomationService.maxDelaySec = values.getOrElse(1) { values[0] }.toInt()
        MiChatAutomationService.maxTargetCount = sliderTarget.value.toInt()

        startService(Intent(this, FloatingOverlayService::class.java))
        Toast.makeText(this, "Kontrol melayang aktif — tekan ▶ di MiChat", Toast.LENGTH_SHORT).show()

        packageManager.getLaunchIntentForPackage("com.michatapp.im")?.let { startActivity(it) }
            ?: Toast.makeText(this, "MiChat belum terpasang", Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------- animation helpers

    private fun playEntranceAnimation() {
        val d = resources.displayMetrics.density
        for (i in 0 until contentMain.childCount) {
            val child = contentMain.getChildAt(i)
            child.alpha = 0f
            child.translationY = 24 * d
            child.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(60L * i)
                .setDuration(380)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        btnLaunch.scaleX = 0.9f
        btnLaunch.scaleY = 0.9f
        btnLaunch.alpha = 0f
        btnLaunch.animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setStartDelay(250).setDuration(420)
            .setInterpolator(OvershootInterpolator(1.6f)).start()
    }

    private fun animateHistoryCount(target: Int) {
        val from = shownHistoryCount
        shownHistoryCount = target
        tvHistoryCount.text = if (target == 0) "Belum ada kontak disapa" else "$target kontak telah disapa"
        if (from == target) {
            tvStatHistory.text = target.toString()
            return
        }
        ValueAnimator.ofInt(from, target).apply {
            duration = 700
            interpolator = DecelerateInterpolator()
            addUpdateListener { tvStatHistory.text = (it.animatedValue as Int).toString() }
            start()
        }
    }

    private fun pop(v: View) {
        v.animate().cancel()
        v.scaleX = 0.92f
        v.scaleY = 0.92f
        v.animate().scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(OvershootInterpolator(3f)).start()
    }

    private fun shakeAndScrollTo(viewId: Int) {
        val target = findViewById<View>(viewId)
        findViewById<NestedScrollView>(R.id.scroll_main).smoothScrollTo(0, (target.top - 16).coerceAtLeast(0))
        val d = resources.displayMetrics.density
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            addUpdateListener {
                val t = it.animatedFraction
                target.translationX = (Math.sin(t * Math.PI * 6) * (1 - t) * 10 * d).toFloat()
            }
            start()
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun parseSpintax(text: String): String {
        var result = text
        val regex = Regex("\\{([^{}]+)\\}")
        val random = Random()
        var guard = 0
        while (regex.containsMatchIn(result) && guard++ < 20) {
            result = regex.replace(result) { match ->
                val choices = match.groupValues[1].split("|")
                choices[random.nextInt(choices.size)]
            }
        }
        return result
    }
}
