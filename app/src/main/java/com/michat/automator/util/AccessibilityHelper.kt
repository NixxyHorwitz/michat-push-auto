package com.michat.automator.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.michat.automator.service.MiChatAutomationService

/**
 * Mengurus status layanan aksesibilitas.
 *
 * Masalah: saat aplikasi ditutup paksa (di MIUI: swipe dari Recent = force stop), Android
 * otomatis mencabut layanan aksesibilitas aplikasi tsb. Hasilnya pengguna harus mematikan
 * lalu menghidupkan lagi layanan di Setelan setiap kali membuka aplikasi.
 *
 * Solusi: jika aplikasi punya izin WRITE_SECURE_SETTINGS (diberikan sekali lewat adb),
 * aplikasi bisa mendaftarkan ulang layanannya sendiri saat dibuka kembali.
 */
object AccessibilityHelper {

    private const val TAG = "A11yHelper"

    enum class State {
        /** Layanan terikat dan berjalan */
        RUNNING,
        /** Tercatat aktif di Setelan, tapi tidak berjalan (perlu dimatikan-hidupkan) */
        STUCK,
        /** Tidak aktif di Setelan */
        OFF
    }

    fun component(ctx: Context) = ComponentName(ctx, MiChatAutomationService::class.java)

    fun isRunning(): Boolean = MiChatAutomationService.instance != null

    fun isEnabledInSettings(ctx: Context): Boolean {
        val cn = component(ctx)
        return enabledList(ctx).any { matches(it, cn) }
    }

    fun state(ctx: Context): State = when {
        isRunning() -> State.RUNNING
        isEnabledInSettings(ctx) -> State.STUCK
        else -> State.OFF
    }

    fun canSelfRepair(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * Daftarkan ulang layanan (setara mematikan lalu menghidupkan di Setelan).
     * Mengembalikan false jika izin WRITE_SECURE_SETTINGS belum diberikan.
     */
    fun repair(ctx: Context, onDone: ((Boolean) -> Unit)? = null): Boolean {
        if (!canSelfRepair(ctx)) return false
        val app = ctx.applicationContext
        val cn = component(app)
        val me = cn.flattenToString()
        val others = enabledList(app).filterNot { matches(it, cn) }

        return try {
            // 1. Lepas layanan kita (memaksa sistem melepas ikatan lama yang macet)
            putList(app, others)
            // 2. Daftarkan lagi setelah jeda singkat, lalu cek apakah sudah terikat
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    putList(app, others + me)
                    Settings.Secure.putInt(app.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                } catch (e: Throwable) {
                    Log.e(TAG, "re-enable failed: ${e.message}")
                }
                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d(TAG, "repair done, running=${isRunning()}")
                    onDone?.invoke(isRunning())
                }, 1200)
            }, 400)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "repair failed: ${e.message}")
            false
        }
    }

    /** Perbaiki otomatis hanya jika memang tidak berjalan */
    fun ensureRunning(ctx: Context, onDone: ((Boolean) -> Unit)? = null): Boolean {
        if (isRunning()) {
            onDone?.invoke(true)
            return true
        }
        return repair(ctx, onDone)
    }

    fun adbGrantCommand(ctx: Context): String =
        "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"

    private fun enabledList(ctx: Context): List<String> =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').map { it.trim() }.filter { it.isNotEmpty() }

    private fun putList(ctx: Context, list: List<String>) {
        Settings.Secure.putString(
            ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            list.distinct().joinToString(":")
        )
    }

    private fun matches(entry: String, cn: ComponentName): Boolean =
        entry.equals(cn.flattenToString(), true) || entry.equals(cn.flattenToShortString(), true)
}
