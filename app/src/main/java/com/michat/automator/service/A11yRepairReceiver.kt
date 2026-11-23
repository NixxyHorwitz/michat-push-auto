package com.michat.automator.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.michat.automator.util.AccessibilityHelper

/** Setelah update/install ulang, aktifkan ulang layanan aksesibilitas jika terlepas. */
class A11yRepairReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val app = context.applicationContext
        // Beri waktu sistem untuk mengikat ulang sendiri dulu
        Handler(Looper.getMainLooper()).postDelayed({
            val started = AccessibilityHelper.ensureRunning(app) { pending.finish() }
            if (!started) pending.finish()
        }, 2500)
    }
}
