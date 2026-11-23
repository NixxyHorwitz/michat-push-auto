package com.michat.automator.license

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.michat.automator.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors

/**
 * Lisensi berbasis key.
 *
 * - Server membalas token bertanda tangan (ECDSA P-256). App hanya percaya token yang tanda tangannya
 *   cocok dengan public key yang ditanam di app, jadi token tidak bisa dipalsukan/diedit.
 * - Token terikat ke Device ID HP ini.
 * - Waktu kadaluarsa dicek memakai "jam server" (dihitung dari waktu server terakhir + waktu sejak itu),
 *   jadi memundurkan jam HP tidak memperpanjang lisensi.
 * - Wajib online minimal sekali per [OFFLINE_GRACE] (field `chk` di token), selain itu app terkunci.
 */
object LicenseManager {

    private const val TAG = "License"
    private const val PREFS = "nx_license"
    private const val REFRESH_INTERVAL_MS = 6 * 60 * 60 * 1000L // cek ulang terjadwal tiap 6 jam
    private const val MIN_FORCED_INTERVAL_MS = 20_000L // jeda minimum antar cek paksa (hindari spam server)

    data class Features(
        val customMessage: Boolean,
        val genderIcon: Boolean,
        val maxTarget: Int
    )

    data class License(
        val key: String,
        val planId: String,
        val planName: String,
        val features: Features,
        val expiresAt: Long?, // null = permanen
        val issuedAt: Long,
        val checkBy: Long
    )

    enum class Status { VALID, NONE, EXPIRED, NEEDS_ONLINE, INVALID }

    sealed class Result {
        data class Ok(val license: License) : Result()
        data class Fail(val code: String, val message: String) : Result()
    }

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var cached: License? = null
    @Volatile private var checking = false
    @Volatile private var lastCheckAt = 0L
    /** Callback yang menunggu cek yang sedang berjalan (dipanggil di main thread) */
    private val waiters = mutableListOf<(Result?) -> Unit>()

    var onLicenseChanged: (() -> Unit)? = null

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @SuppressLint("HardwareIds")
    fun deviceId(ctx: Context): String {
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        return id.uppercase()
    }

    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80)

    fun savedKey(ctx: Context): String? = prefs(ctx).getString("key", null)

    fun lastError(ctx: Context): String? = prefs(ctx).getString("last_error", null)

    // ------------------------------------------------------------------ waktu tepercaya

    /** Perkiraan jam server saat ini; kebal terhadap jam HP yang dimundurkan */
    fun trustedNow(ctx: Context): Long {
        val p = prefs(ctx)
        val serverAt = p.getLong("server_time", 0L)
        val elapsedAt = p.getLong("elapsed_at", 0L)
        val wall = System.currentTimeMillis()
        if (serverAt == 0L) return wall
        val elapsedNow = SystemClock.elapsedRealtime()
        return if (elapsedNow >= elapsedAt) {
            // Belum reboot: hitung dari jam monotonic
            maxOf(serverAt + (elapsedNow - elapsedAt), serverAt)
        } else {
            // Sudah reboot: tidak boleh lebih kecil dari waktu server terakhir yang diketahui
            maxOf(wall, serverAt)
        }
    }

    private fun rememberServerTime(ctx: Context, serverTime: Long) {
        prefs(ctx).edit()
            .putLong("server_time", serverTime)
            .putLong("elapsed_at", SystemClock.elapsedRealtime())
            .apply()
    }

    // ------------------------------------------------------------------ status lokal

    fun current(ctx: Context): License? {
        cached?.let { return it }
        val token = prefs(ctx).getString("token", null) ?: return null
        return parseAndVerify(ctx, token).also { cached = it }
    }

    fun status(ctx: Context): Status {
        val lic = current(ctx) ?: return if (prefs(ctx).getString("token", null) != null) Status.INVALID
            else if (lastError(ctx) != null) Status.EXPIRED else Status.NONE
        val now = trustedNow(ctx)
        return when {
            lic.expiresAt != null && now >= lic.expiresAt -> Status.EXPIRED
            now >= lic.checkBy -> Status.NEEDS_ONLINE
            else -> Status.VALID
        }
    }

    fun isValid(ctx: Context): Boolean = status(ctx) == Status.VALID

    /** Fitur yang boleh dipakai; tanpa lisensi valid semuanya tertutup */
    fun features(ctx: Context): Features =
        if (isValid(ctx)) current(ctx)!!.features else Features(false, false, 0)

    fun remainingText(ctx: Context): String {
        val lic = current(ctx) ?: return "-"
        val exp = lic.expiresAt ?: return "Permanen"
        val ms = exp - trustedNow(ctx)
        if (ms <= 0) return "Habis"
        val d = ms / 86_400_000
        val h = (ms % 86_400_000) / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        return when {
            d > 0 -> "$d hari $h jam"
            h > 0 -> "$h jam $m mnt"
            else -> "$m menit"
        }
    }

    // ------------------------------------------------------------------ server

    fun activate(ctx: Context, rawKey: String, callback: (Result) -> Unit) {
        val key = rawKey.uppercase().replace(Regex("[^A-Z0-9-]"), "")
        val body = JSONObject()
            .put("key", key)
            .put("deviceId", deviceId(ctx))
            .put("deviceName", deviceName())
            .put("appVersion", BuildConfig.VERSION_NAME)
        request(ctx, "/api/activate", body, key, lockOnReject = false, callback = callback)
    }

    /** Cek ke server bila sudah waktunya (atau [force]). Hasil disampaikan lewat onLicenseChanged. */
    fun refreshIfDue(ctx: Context, force: Boolean = false, callback: ((Result?) -> Unit)? = null) {
        val key = savedKey(ctx)
        if (key == null) { callback?.invoke(null); return }
        // Sedang ada cek berjalan → tunggu hasilnya, jangan kirim request ganda
        // (dipanggil dari main thread, sama dengan thread yang mengosongkan waiters → tanpa race)
        if (checking) { callback?.let { waiters.add(it) }; return }
        val lic = current(ctx)
        val sinceLast = SystemClock.elapsedRealtime() - lastCheckAt
        val due = (force && sinceLast >= MIN_FORCED_INTERVAL_MS) || lic == null ||
            trustedNow(ctx) - lic.issuedAt >= REFRESH_INTERVAL_MS || status(ctx) != Status.VALID
        if (!due) { callback?.invoke(null); return }
        lastCheckAt = SystemClock.elapsedRealtime()
        val body = JSONObject()
            .put("key", key)
            .put("deviceId", deviceId(ctx))
            .put("appVersion", BuildConfig.VERSION_NAME)
        request(ctx, "/api/check", body, key, lockOnReject = true) { callback?.invoke(it) }
    }

    fun signOut(ctx: Context) {
        prefs(ctx).edit().remove("token").remove("key").remove("last_error").apply()
        cached = null
        notifyChanged()
    }

    private fun request(
        ctx: Context,
        path: String,
        body: JSONObject,
        key: String,
        lockOnReject: Boolean,
        callback: (Result) -> Unit
    ) {
        val app = ctx.applicationContext
        checking = true
        io.execute {
            val result: Result = try {
                val conn = (URL(BuildConfig.LICENSE_SERVER_URL.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: "{}"
                conn.disconnect()
                val json = JSONObject(text)
                json.optLong("serverTime").takeIf { it > 0 }?.let { rememberServerTime(app, it) }

                if (json.optBoolean("ok")) {
                    val token = json.getString("token")
                    val lic = parseAndVerify(app, token)
                    if (lic == null) {
                        Result.Fail("bad_token", "Respon server tidak valid")
                    } else {
                        prefs(app).edit().putString("token", token).putString("key", key)
                            .remove("last_error").apply()
                        cached = lic
                        Result.Ok(lic)
                    }
                } else {
                    val errCode = json.optString("code", "error")
                    val msg = json.optString("message", "Gagal (HTTP $code)")
                    // Ditolak server (dicabut/kadaluarsa/dll) → kunci app
                    if (lockOnReject && errCode in setOf("revoked", "expired", "invalid", "not_activated")) {
                        prefs(app).edit().remove("token").putString("last_error", msg).apply()
                        cached = null
                    }
                    Result.Fail(errCode, msg)
                }
            } catch (e: Exception) {
                Log.w(TAG, "request $path gagal: ${e.message}")
                // Gagal koneksi: token lama tetap dipakai sampai batas offline
                Result.Fail("network", "Tidak bisa terhubung ke server lisensi. Cek internet.")
            }
            checking = false
            main.post {
                callback(result)
                val pending = waiters.toList()
                waiters.clear()
                pending.forEach { it(result) }
                notifyChanged()
            }
        }
    }

    private fun notifyChanged() {
        main.post { onLicenseChanged?.invoke() }
    }

    // ------------------------------------------------------------------ verifikasi token

    private fun parseAndVerify(ctx: Context, token: String): License? {
        return try {
            val parts = token.split(".")
            if (parts.size != 2) return null
            val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            val sig = Base64.decode(parts[1], flags)
            val pub = KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(Base64.decode(BuildConfig.LICENSE_PUBLIC_KEY, Base64.DEFAULT))
            )
            val verifier = Signature.getInstance("SHA256withECDSA").apply {
                initVerify(pub)
                update(parts[0].toByteArray())
            }
            if (!verifier.verify(sig)) {
                Log.w(TAG, "Tanda tangan token tidak cocok")
                return null
            }
            val json = JSONObject(String(Base64.decode(parts[0], flags)))
            if (json.optString("d") != deviceId(ctx)) {
                Log.w(TAG, "Token milik HP lain")
                return null
            }
            val f = json.optJSONObject("f") ?: JSONObject()
            License(
                key = json.getString("k"),
                planId = json.getString("p"),
                planName = json.optString("pn", json.getString("p")),
                features = Features(
                    customMessage = f.optBoolean("custom_message", false),
                    genderIcon = f.optBoolean("gender_icon", false),
                    maxTarget = f.optInt("max_target", 20)
                ),
                expiresAt = if (json.isNull("exp")) null else json.getLong("exp"),
                issuedAt = json.getLong("iat"),
                checkBy = json.getLong("chk")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Token rusak: ${e.message}")
            null
        }
    }
}
