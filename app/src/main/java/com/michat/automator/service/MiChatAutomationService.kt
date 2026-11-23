package com.michat.automator.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.michat.automator.data.HistoryManager
import com.michat.automator.util.GenderAnalyzer
import java.util.Random

class MiChatAutomationService : AccessibilityService() {

    enum class TargetGender(val label: String) {
        FEMALE("Hanya Perempuan"),
        MALE("Hanya Laki-laki"),
        ALL("Semua Gender")
    }

    companion object {
        private const val TAG = "MiChatAutoService"
        private const val MAX_NO_PROGRESS_SCROLLS = 3
        private const val MAX_ICON_SHOT_ATTEMPTS = 4
        private const val MICHAT_PACKAGE = "com.michatapp.im"
        private const val UNKNOWN_SCREEN_TIMEOUT_MS = 2500L
        private val KNOWN_SCREEN_IDS = listOf(
            "com.michatapp.im:id/request_information",
            "com.michatapp.im:id/scv_user_detail",
            "com.michatapp.im:id/peopleNearbyContent",
            "com.michatapp.im:id/peopleNearby_item",
            "com.michatapp.im:id/tab_contacts"
        )
        var instance: MiChatAutomationService? = null
        var isRunning: Boolean = false

        // Target Gender & Toggle Analisis
        var targetGender: TargetGender = TargetGender.FEMALE
        var enableAnalyzeByName: Boolean = true
        var enableAnalyzeByIcon: Boolean = true

        // Filter Teman
        var skipFriends: Boolean = true
        var onlySapaDia: Boolean = true
        var skipAlreadySentInHistory: Boolean = true // selalu aktif

        // Opsi Pesan
        var defaultGreetingOnly: Boolean = true
        var messageTemplate: String = "{Halo|Hai|Pagi} kak, {salam kenal ya|lagi santai?}"

        // Delay & Limit
        var minDelaySec: Int = 3
        var maxDelaySec: Int = 6
        var maxTargetCount: Int = 20

        var sentCount: Int = 0
        var statusListener: ((String, Int) -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val sentUserSet = mutableSetOf<String>()

    private var currentTargetName: String? = null
    private var currentTargetGender: String = "Unknown"
    private var hasSentGreetingForCurrentTarget = false

    private var isWaitingCooldown = false
    private var lastActionTime = 0L
    private var unknownScreenSince = 0L

    // Deteksi akhir daftar Pengguna di Sekitar
    private var lastListSignature: String? = null
    private var noScrollProgressCount = 0


    private val botLoopRunnable = object : Runnable {
        override fun run() {
            if (isRunning) {
                if (!isWaitingCooldown) {
                    val root = rootInActiveWindow
                    if (root != null) {
                        evaluateAndAct(root)
                    }
                }
                handler.postDelayed(this, 300)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "MiChat Automation Service connected.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        isRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning || isWaitingCooldown) return
        val root = rootInActiveWindow ?: return
        if (event?.packageName?.toString() == "com.michatapp.im") {
            val now = System.currentTimeMillis()
            if (now - lastActionTime > 250) {
                evaluateAndAct(root)
            }
        }
    }

    override fun onInterrupt() {
        stopBot()
    }

    fun startBot() {
        if (isRunning) return
        isRunning = true
        sentCount = 0
        sentUserSet.clear()
        // Riwayat selalu dimuat: kontak yang sudah pernah disapa tidak dikirimi lagi
        sentUserSet.addAll(HistoryManager.getSentNicknames(applicationContext))
        profileGenderKey = null
        currentTargetName = null
        currentTargetGender = "Unknown"
        hasSentGreetingForCurrentTarget = false
        isWaitingCooldown = false
        lastActionTime = 0L
        lastListSignature = null
        noScrollProgressCount = 0
        unknownScreenSince = 0L

        updateStatus("Bot aktif! Target: ${targetGender.label}")
        handler.removeCallbacks(botLoopRunnable)
        handler.post(botLoopRunnable)
    }

    fun stopBot() {
        isRunning = false
        isWaitingCooldown = false
        handler.removeCallbacksAndMessages(null)
        FloatingOverlayService.hideTargetMarker()
        updateStatus("Bot dihentikan.")
    }

    private fun evaluateAndAct(root: AccessibilityNodeInfo) {
        if (!isRunning || isWaitingCooldown) return

        if (sentCount >= maxTargetCount) {
            updateStatus("Target tercapai ($sentCount/$maxTargetCount). Selesai!")
            stopBot()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastActionTime < 250) return

        if (isKnownScreen(root)) unknownScreenSince = 0L

        // 1. Layar Form Sapa (NewContactRequestSendActivity)
        val requestInfoInput = findNodeById(root, "com.michatapp.im:id/request_information")
        val sendBtn = findNodeById(root, "com.michatapp.im:id/action_button")
        if (requestInfoInput != null && sendBtn != null && sendBtn.text?.toString() == "Kirim") {
            if (hasSentGreetingForCurrentTarget) {
                return
            }
            lastActionTime = now
            handleSendMessageScreen(requestInfoInput, sendBtn)
            return
        }

        // 2. Layar Detail Profil Pengguna (UserDetailActivity)
        val actionText = findNodeById(root, "com.michatapp.im:id/action_textview")
        val userDetailRoot = findNodeById(root, "com.michatapp.im:id/scv_user_detail")
        if (actionText != null && userDetailRoot != null) {
            lastActionTime = now
            handleProfileDetailScreen(root, actionText)
            return
        }

        // 3. Layar Pengguna di Sekitar (PeopleNearbyActivity)
        val nearbyList = findNodeById(root, "com.michatapp.im:id/peopleNearbyContent")
        if (nearbyList != null) {
            hasSentGreetingForCurrentTarget = false
            lastActionTime = now
            profileGenderKey = null

            handleNearbyListScreen(root, nearbyList)
            return
        }

        // 4. Layar Beranda / Home (MainTabsActivity) -> Masuk Menu Otomatis
        val tabContacts = findNodeById(root, "com.michatapp.im:id/tab_contacts")
        val peopleNearbyItem = findNodeById(root, "com.michatapp.im:id/peopleNearby_item")

        if (peopleNearbyItem != null) {
            lastActionTime = now
            updateStatus("Buka Pengguna di Sekitar...")
            clickNode(peopleNearbyItem)
            return
        } else if (tabContacts != null) {
            lastActionTime = now
            updateStatus("Buka tab Teman...")
            clickNode(tabContacts)
            return
        }

        // 5. Layar tidak dikenal di MiChat (mis. layar chat setelah sapaan, iklan, popup).
        //    Tanpa ini bot diam di layar tersebut dan terlihat seperti berhenti.
        if (root.packageName?.toString() == MICHAT_PACKAGE) {
            if (unknownScreenSince == 0L) {
                unknownScreenSince = now
            } else if (now - unknownScreenSince > UNKNOWN_SCREEN_TIMEOUT_MS) {
                unknownScreenSince = 0L
                lastActionTime = now
                updateStatus("Layar lain terdeteksi, kembali ke daftar...")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }
    }

    private fun isKnownScreen(root: AccessibilityNodeInfo): Boolean =
        KNOWN_SCREEN_IDS.any { findNodeById(root, it) != null }

    /**
     * Memproses form sapaan
     */
    private fun handleSendMessageScreen(inputNode: AccessibilityNodeInfo, sendBtn: AccessibilityNodeInfo) {
        FloatingOverlayService.hideTargetMarker()
        val targetName = currentTargetName ?: "Pengguna"
        val genderStr = currentTargetGender

        if (defaultGreetingOnly) {
            updateStatus("Kirim sapaan default...")
            clickNode(sendBtn)
            hasSentGreetingForCurrentTarget = true
            sentCount++
            HistoryManager.addRecord(applicationContext, targetName, genderStr, "Sapaan Default")
            updateStatus("Terkirim: $targetName ($sentCount/$maxTargetCount)")

            handler.postDelayed({
                if (findNodeById(rootInActiveWindow ?: return@postDelayed, "com.michatapp.im:id/request_information") != null) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }, 300)
        } else {
            updateStatus("Ketik pesan custom...")
            val msg = parseSpintax(messageTemplate)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, msg)
            }
            inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            handler.postDelayed({
                if (!isRunning) return@postDelayed
                updateStatus("Tekan Kirim...")
                clickNode(sendBtn)
                hasSentGreetingForCurrentTarget = true
                sentCount++
                HistoryManager.addRecord(applicationContext, targetName, genderStr, "Pesan Custom")
                updateStatus("Terkirim: $targetName ($sentCount/$maxTargetCount)")

                handler.postDelayed({
                    if (findNodeById(rootInActiveWindow ?: return@postDelayed, "com.michatapp.im:id/request_information") != null) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }, 300)
            }, 200)
        }
    }

    /**
     * Layar profil pengguna
     */
    private fun handleProfileDetailScreen(root: AccessibilityNodeInfo, actionText: AccessibilityNodeInfo) {
        FloatingOverlayService.hideTargetMarker()
        val btnText = actionText.text?.toString() ?: ""

        if (hasSentGreetingForCurrentTarget) {
            updateStatus("Kembali ke Pengguna di Sekitar...")
            performGlobalAction(GLOBAL_ACTION_BACK)
            hasSentGreetingForCurrentTarget = false

            val cooldownSec = getRandomDelay()
            startCooldownCountdown(cooldownSec)
            return
        }

        // Nama asli di halaman profil (lebih akurat daripada nama di list)
        val profileName = findNodeById(root, "com.michatapp.im:id/nameMain")?.text?.toString()?.trim()
        if (!profileName.isNullOrEmpty()) {
            // Cek riwayat: nama yang sudah pernah disapa tidak dikirimi lagi
            if (HistoryManager.isAlreadySent(applicationContext, profileName)) {
                sentUserSet.add(key(profileName))
                updateStatus("Sudah ada di riwayat: $profileName. Lewati...")
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
            currentTargetName = profileName
            sentUserSet.add(key(profileName))
        }

        if (!btnText.contains("Sapa Dia", ignoreCase = true)) {
            if (onlySapaDia) {
                updateStatus("Sudah pernah chat/teman. Lewati...")
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        // Verifikasi gender dari ikon ♀/♂ di profil sebelum menyapa
        val gender = resolveProfileGender(root, profileName ?: currentTargetName ?: "") ?: return
        currentTargetGender = genderLabel(gender)
        val who = profileName ?: currentTargetName ?: "Pengguna"
        if (targetGender == TargetGender.FEMALE && gender == GenderAnalyzer.Gender.MALE) {
            updateStatus("Skip Laki-laki (ikon biru): $who")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }
        if (targetGender == TargetGender.MALE && gender == GenderAnalyzer.Gender.FEMALE) {
            updateStatus("Skip Perempuan (ikon pink): $who")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        updateStatus("Buka Sapa Dia: $who ($currentTargetGender)")
        clickNode(actionText.parent ?: actionText)
    }

    // State verifikasi gender per profil
    private var profileGenderKey: String? = null
    private var profileIconGender: GenderAnalyzer.Gender? = null
    private var profileShotAttempts = 0

    /**
     * Mengembalikan gender profil yang sedang dibuka.
     * null = masih menunggu screenshot (panggil lagi di loop berikutnya).
     */
    private fun resolveProfileGender(root: AccessibilityNodeInfo, name: String): GenderAnalyzer.Gender? {
        if (profileGenderKey != name) {
            profileGenderKey = name
            profileIconGender = null
            profileShotAttempts = 0
        }

        var gender = GenderAnalyzer.Gender.UNKNOWN

        if (enableAnalyzeByIcon && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val iconNode = findNodeById(root, "com.michatapp.im:id/img_gender")
            if (iconNode != null && iconNode.isVisibleToUser) {
                if (profileIconGender == null) {
                    if (profileShotAttempts < MAX_ICON_SHOT_ATTEMPTS) {
                        val rect = Rect()
                        iconNode.getBoundsInScreen(rect)
                        captureIconGender(rect, name)
                        return null
                    }
                    profileIconGender = GenderAnalyzer.Gender.UNKNOWN
                }
                gender = profileIconGender ?: GenderAnalyzer.Gender.UNKNOWN
            }
        }

        if (gender == GenderAnalyzer.Gender.UNKNOWN && enableAnalyzeByName) {
            val bio = findNodeById(root, "com.michatapp.im:id/signature_text")?.text?.toString()
            gender = GenderAnalyzer.analyzeGenderFromNameAndBio(name, bio)
        }
        return gender
    }

    private fun captureIconGender(iconRect: Rect, forKey: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        profileShotAttempts++
        isWaitingCooldown = true
        updateStatus("🔍 Cek ikon gender...")
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                applicationContext.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshotResult.hardwareBuffer
                            val bmp = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshotResult.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBuffer.close()
                            if (bmp != null) {
                                val g = GenderAnalyzer.analyzeGenderFromIconColor(bmp, iconRect)
                                bmp.recycle()
                                Log.d(TAG, "Icon gender for '$forKey' = $g (attempt $profileShotAttempts)")
                                if (profileGenderKey == forKey &&
                                    (g != GenderAnalyzer.Gender.UNKNOWN || profileShotAttempts >= MAX_ICON_SHOT_ATTEMPTS)) {
                                    profileIconGender = g
                                }
                            }
                        } catch (e: Throwable) {
                            Log.e(TAG, "Screenshot process error: ${e.message}")
                        }
                        isWaitingCooldown = false
                    }

                    override fun onFailure(errorCode: Int) {
                        // Biasanya karena rate-limit screenshot; coba lagi sebentar lagi
                        Log.w(TAG, "Screenshot onFailure: $errorCode")
                        handler.postDelayed({ isWaitingCooldown = false }, 400)
                    }
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Screenshot request error: ${e.message}")
            handler.postDelayed({ isWaitingCooldown = false }, 400)
        }
    }

    private fun genderLabel(g: GenderAnalyzer.Gender): String = when (g) {
        GenderAnalyzer.Gender.FEMALE -> "Perempuan"
        GenderAnalyzer.Gender.MALE -> "Laki-laki"
        else -> "Unknown"
    }

    private fun key(name: String): String = HistoryManager.normalize(name)

    /**
     * Memproses daftar pengguna sekitar dengan toggle analisis nama & icon
     */
    private fun handleNearbyListScreen(root: AccessibilityNodeInfo, listNode: AccessibilityNodeInfo) {
        val userItems = root.findAccessibilityNodeInfosByViewId("com.michatapp.im:id/nb_item")

        // Area list sebenarnya (di bawah toolbar, di atas navbar) - dipakai untuk cek item utuh & area swipe
        val listRect = Rect()
        listNode.getBoundsInScreen(listRect)

        var foundNextUser = false
        if (userItems != null && userItems.isNotEmpty()) {
            for (item in userItems) {
                // Pastikan item utuh di dalam area list. Item yang terpotong akan terlihat utuh
                // setelah scroll berikutnya karena scroll dibuat overlap (lihat scrollDown).
                val itemRect = Rect()
                item.getBoundsInScreen(itemRect)
                if (itemRect.top < listRect.top || itemRect.bottom > listRect.bottom || itemRect.height() < 60) {
                    continue
                }

                // 1. FILTER TEMAN
                if (skipFriends) {
                    val isFriendNode = findChildById(item, "com.michatapp.im:id/is_friends")
                    if (isFriendNode != null && isFriendNode.text?.toString()?.contains("Teman", ignoreCase = true) == true) {
                        continue
                    }
                }

                val nameNode = findChildById(item, "com.michatapp.im:id/nick_name")
                val nickname = nameNode?.text?.toString()?.trim()
                // Nama belum ter-render: jangan dicatat sebagai "Unknown" (bisa membuat kontak lain ikut ter-skip)
                if (nickname.isNullOrEmpty()) {
                    continue
                }

                val nameKey = key(nickname)
                if (sentUserSet.contains(nameKey)) {
                    continue
                }

                // Kontak yang sudah ada di riwayat tidak pernah disapa ulang
                if (HistoryManager.isAlreadySent(applicationContext, nickname)) {
                    sentUserSet.add(nameKey)
                    continue
                }

                // List MiChat tidak menampilkan ikon gender. Jika analisis ikon aktif, keputusan gender
                // dibuat di halaman profil (ikon ♀ pink / ♂ biru). Pra-filter nama hanya dipakai jika ikon nonaktif.
                var detectedGender = GenderAnalyzer.Gender.UNKNOWN
                val iconCheckOnProfile = enableAnalyzeByIcon && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                if (enableAnalyzeByName && !iconCheckOnProfile && targetGender != TargetGender.ALL) {
                    val bio = findChildById(item, "com.michatapp.im:id/signature")?.text?.toString()
                    detectedGender = GenderAnalyzer.analyzeGenderFromNameAndBio(nickname, bio)

                    if (targetGender == TargetGender.FEMALE && detectedGender == GenderAnalyzer.Gender.MALE) {
                        sentUserSet.add(nameKey)
                        updateStatus("Skip Laki-laki: $nickname")
                        continue
                    }
                    if (targetGender == TargetGender.MALE && detectedGender == GenderAnalyzer.Gender.FEMALE) {
                        sentUserSet.add(nameKey)
                        updateStatus("Skip Perempuan: $nickname")
                        continue
                    }
                }

                // Kandidat lolos -> buka profil untuk verifikasi akhir
                sentUserSet.add(nameKey)
                currentTargetName = nickname
                currentTargetGender = genderLabel(detectedGender)
                hasSentGreetingForCurrentTarget = false
                updateStatus("🎯 Memilih: $nickname ($currentTargetGender)")
                foundNextUser = true

                // Tampilkan Marker Border Seleksi visual tepat di atas profil yang dipilih
                FloatingOverlayService.showTargetMarker(itemRect, nickname, currentTargetGender)

                // Berikan jeda 350ms agar marker terlihat jelas sebelum profil dibuka
                isWaitingCooldown = true
                handler.postDelayed({
                    isWaitingCooldown = false
                    if (isRunning) {
                        clickNode(item)
                    }
                }, 350)
                break
            }
        }

        if (foundNextUser) {
            noScrollProgressCount = 0
            lastListSignature = null
            return
        }

        // Deteksi akhir daftar: jika isi list tidak berubah setelah beberapa kali scroll, berarti sudah mentok bawah
        val signature = userItems?.joinToString("|") { item ->
            val r = Rect()
            item.getBoundsInScreen(r)
            val name = findChildById(item, "com.michatapp.im:id/nick_name")?.text?.toString() ?: ""
            "$name@${r.top}"
        } ?: ""
        if (signature == lastListSignature) {
            noScrollProgressCount++
        } else {
            noScrollProgressCount = 0
        }
        lastListSignature = signature

        if (noScrollProgressCount >= MAX_NO_PROGRESS_SCROLLS) {
            updateStatus("Semua kontak sudah diproses sampai bawah ($sentCount terkirim). Selesai!")
            stopBot()
            return
        }

        updateStatus("Gulir mencari target baru...")
        scrollDown(listRect)
    }

    private fun startCooldownCountdown(secondsRemaining: Int) {
        isWaitingCooldown = true
        var current = secondsRemaining

        val countdownRunnable = object : Runnable {
            override fun run() {
                if (!isRunning) {
                    isWaitingCooldown = false
                    return
                }

                if (current > 0) {
                    updateStatus("Jeda aman: ${current}s...")
                    current--
                    handler.postDelayed(this, 1000)
                } else {
                    isWaitingCooldown = false
                    updateStatus("Mencari target berikutnya...")
                }
            }
        }
        handler.post(countdownRunnable)
    }

    /**
     * Scroll terkontrol tanpa fling (momentum), sejauh ~50% tinggi list.
     *
     * Karena jarak scroll < tinggi list, layar sebelum & sesudah scroll selalu overlap.
     * Item yang terpotong di bawah pada layar sebelumnya pasti tampil utuh setelah scroll,
     * jadi tidak ada kontak yang terlewat. Swipe cepat lama (350ms) memicu fling sehingga
     * list meloncat jauh dan beberapa kontak tidak pernah terlihat utuh.
     */
    private fun scrollDown(listRect: Rect) {
        FloatingOverlayService.hideTargetMarker()
        isWaitingCooldown = true
        updateStatus("📜 Menggulir mencari profil baru...")

        val listHeight = listRect.height().toFloat()
        val startY = listRect.top + listHeight * 0.75f
        val endY = listRect.top + listHeight * 0.25f
        val centerX = pickSwipeX(listRect, endY, startY)

        val path = Path().apply {
            moveTo(centerX, startY)
            lineTo(centerX, endY)
        }

        val gesture = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Geser pelan, lalu tahan jari diam sebentar sebelum diangkat -> velocity 0 -> tidak ada fling
            val drag = GestureDescription.StrokeDescription(path, 0, 900, true)
            val holdPath = Path().apply { moveTo(centerX, endY) }
            val hold = drag.continueStroke(holdPath, 0, 400, false)
            pendingHoldStroke = hold
            GestureDescription.Builder().addStroke(drag).build()
        } else {
            pendingHoldStroke = null
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 1500))
                .build()
        }

        dispatchScrollGesture(gesture)
    }

    /**
     * Pilih posisi X untuk swipe yang tidak tertutup widget melayang.
     * Kalau jalur swipe menabrak widget, sentuhan masuk ke widget dan list tidak ter-scroll.
     */
    private fun pickSwipeX(listRect: Rect, top: Float, bottom: Float): Float {
        val w = listRect.width().toFloat()
        val candidates = listOf(0.5f, 0.85f, 0.15f, 0.7f, 0.3f, 0.95f, 0.05f).map { listRect.left + w * it }
        val widget = FloatingOverlayService.widgetBoundsOnScreen() ?: return candidates[0]
        val margin = 24
        return candidates.firstOrNull { x ->
            val hitsX = x >= widget.left - margin && x <= widget.right + margin
            val hitsY = bottom >= widget.top - margin && top <= widget.bottom + margin
            !(hitsX && hitsY)
        } ?: candidates[0]
    }

    private var pendingHoldStroke: GestureDescription.StrokeDescription? = null

    private fun dispatchScrollGesture(gesture: GestureDescription) {
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                val hold = pendingHoldStroke
                pendingHoldStroke = null
                if (hold != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Lanjutkan stroke: jari diam di posisi akhir lalu diangkat
                    dispatchScrollGesture(GestureDescription.Builder().addStroke(hold).build())
                    return
                }
                // Beri waktu list settle & item baru ter-render (juga untuk load-more di bawah)
                handler.postDelayed({
                    isWaitingCooldown = false
                }, 700)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                pendingHoldStroke = null
                isWaitingCooldown = false
            }
        }, null)
        if (!dispatched) {
            pendingHoldStroke = null
            isWaitingCooldown = false
        }
    }

    private fun clickNode(node: AccessibilityNodeInfo) {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) {
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            current = current.parent
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val path = Path().apply {
            moveTo(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun findNodeById(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val list = root.findAccessibilityNodeInfosByViewId(id)
        return list?.firstOrNull()
    }

    private fun findChildById(parent: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val list = parent.findAccessibilityNodeInfosByViewId(id)
        return list?.firstOrNull()
    }

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val list = root.findAccessibilityNodeInfosByText(text)
        return list?.firstOrNull()
    }

    private fun getRandomDelay(): Int {
        val random = Random()
        return if (maxDelaySec > minDelaySec) {
            minDelaySec + random.nextInt(maxDelaySec - minDelaySec + 1)
        } else {
            minDelaySec
        }
    }

    private fun parseSpintax(text: String): String {
        var result = text
        val regex = Regex("\\{([^{}]+)\\}")
        val random = Random()
        while (regex.containsMatchIn(result)) {
            result = regex.replace(result) { match ->
                val choices = match.groupValues[1].split("|")
                choices[random.nextInt(choices.size)]
            }
        }
        return result
    }

    private fun updateStatus(status: String) {
        Log.d(TAG, status)
        statusListener?.invoke(status, sentCount)
    }
}
