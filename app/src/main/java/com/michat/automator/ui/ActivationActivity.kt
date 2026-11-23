package com.michat.automator.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.michat.automator.R
import com.michat.automator.license.LicenseManager

/** Layar kunci: tampil jika belum ada lisensi, lisensi habis, dicabut, atau perlu verifikasi online. */
class ActivationActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context) {
            ctx.startActivity(
                Intent(ctx, ActivationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
    }

    private lateinit var etKey: TextInputEditText
    private lateinit var tilKey: TextInputLayout
    private lateinit var btnActivate: MaterialButton
    private lateinit var pb: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var tvBanner: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_MiChatAutomator_Main)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_activation)

        etKey = findViewById(R.id.et_key)
        tilKey = findViewById(R.id.til_key)
        btnActivate = findViewById(R.id.btn_activate)
        pb = findViewById(R.id.pb_activation)
        tvError = findViewById(R.id.tv_activation_error)
        tvBanner = findViewById(R.id.tv_activation_banner)

        val deviceId = LicenseManager.deviceId(this)
        findViewById<TextView>(R.id.tv_device_id).text = deviceId
        findViewById<View>(R.id.row_device_id).setOnClickListener {
            copy("Device ID", deviceId)
        }

        LicenseManager.savedKey(this)?.let { etKey.setText(it) }

        findViewById<View>(R.id.btn_paste_key).setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
            val found = Regex("MCA-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}").find(text.uppercase())?.value
            if (found != null) etKey.setText(found) else Toast.makeText(this, "Tidak ada key di clipboard", Toast.LENGTH_SHORT).show()
        }

        btnActivate.setOnClickListener { activate() }
        etKey.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { activate(); true } else false
        }

        showStatusBanner()

        // Lisensi tersimpan tapi perlu verifikasi online → coba otomatis
        if (LicenseManager.status(this) == LicenseManager.Status.NEEDS_ONLINE) {
            setLoading(true)
            LicenseManager.refreshIfDue(this, force = true) { res ->
                setLoading(false)
                if (res is LicenseManager.Result.Ok) goToMain() else showStatusBanner()
            }
        }

        findViewById<View>(R.id.img_logo).apply {
            scaleX = 0.6f; scaleY = 0.6f; alpha = 0f
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(500)
                .setInterpolator(OvershootInterpolator(2f)).start()
        }
    }

    private fun showStatusBanner() {
        val msg = when (LicenseManager.status(this)) {
            LicenseManager.Status.EXPIRED ->
                "⏰ " + (LicenseManager.lastError(this) ?: "Masa aktif key sudah habis") + ". Masukkan key baru untuk lanjut."
            LicenseManager.Status.NEEDS_ONLINE ->
                "📶 Perlu internet untuk memverifikasi lisensi."
            LicenseManager.Status.INVALID ->
                "⚠️ Lisensi di HP ini tidak valid. Masukkan key lagi."
            else -> null
        }
        tvBanner.visibility = if (msg == null) View.GONE else View.VISIBLE
        tvBanner.text = msg
    }

    private fun activate() {
        val key = etKey.text?.toString()?.trim().orEmpty()
        if (!Regex("^MCA-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$").matches(key.uppercase())) {
            tilKey.error = "Format key tidak valid"
            shake(tilKey)
            return
        }
        tilKey.error = null
        tvError.visibility = View.GONE
        btnActivate.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        setLoading(true)
        LicenseManager.activate(this, key) { res ->
            setLoading(false)
            when (res) {
                is LicenseManager.Result.Ok -> {
                    btnActivate.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    Toast.makeText(this, "Aktif ✓ Paket ${res.license.planName}", Toast.LENGTH_SHORT).show()
                    goToMain()
                }
                is LicenseManager.Result.Fail -> {
                    tvError.text = res.message
                    tvError.visibility = View.VISIBLE
                    shake(btnActivate)
                }
            }
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    private fun setLoading(loading: Boolean) {
        pb.visibility = if (loading) View.VISIBLE else View.GONE
        btnActivate.isEnabled = !loading
        btnActivate.text = if (loading) "Memeriksa…" else "🔓  Aktifkan"
    }

    private fun copy(label: String, text: String) {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label disalin", Toast.LENGTH_SHORT).show()
    }

    private fun shake(v: View) {
        val d = resources.displayMetrics.density
        v.animate().cancel()
        v.translationX = 0f
        val seq = floatArrayOf(10f, -10f, 6f, -6f, 0f)
        fun step(i: Int) {
            if (i >= seq.size) return
            v.animate().translationX(seq[i] * d).setDuration(50).withEndAction { step(i + 1) }.start()
        }
        step(0)
    }
}
