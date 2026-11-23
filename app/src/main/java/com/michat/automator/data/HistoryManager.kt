package com.michat.automator.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SentRecord(
    val id: String,
    val nickname: String,
    val gender: String,
    val timestamp: Long,
    val timeStr: String,
    val type: String
)

object HistoryManager {
    private const val PREF_NAME = "michat_sent_history_pref"
    private const val KEY_HISTORY_JSON = "key_history_json"
    private const val KEY_SENT_NAMES = "key_sent_names_all"
    private const val MAX_RECORDS = 500

    /** Kunci pembanding nama: huruf kecil, spasi dirapikan */
    fun normalize(nickname: String): String =
        nickname.trim().replace(Regex("\\s+"), " ").lowercase()

    private val cacheList = mutableListOf<SentRecord>()
    private val nicknameSet = mutableSetOf<String>()
    private var isLoaded = false

    var onHistoryChanged: (() -> Unit)? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun getRecords(context: Context): List<SentRecord> {
        ensureLoaded(context)
        return cacheList.toList()
    }

    @Synchronized
    fun getSentNicknames(context: Context): Set<String> {
        ensureLoaded(context)
        return nicknameSet.toSet()
    }

    @Synchronized
    fun isAlreadySent(context: Context, nickname: String): Boolean {
        ensureLoaded(context)
        return nicknameSet.contains(normalize(nickname))
    }

    @Synchronized
    fun addRecord(
        context: Context,
        nickname: String,
        gender: String,
        type: String
    ): SentRecord {
        ensureLoaded(context)
        val now = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault()).format(Date(now))
        val id = "${now}_${nickname.hashCode()}"

        val record = SentRecord(
            id = id,
            nickname = nickname,
            gender = gender,
            timestamp = now,
            timeStr = timeStr,
            type = type
        )

        // Masukkan paling atas (terbaru)
        cacheList.add(0, record)
        nicknameSet.add(normalize(nickname))

        // Daftar tampilan dibatasi, tapi daftar nama yang sudah disapa TIDAK pernah dibuang
        // supaya kontak lama tidak disapa ulang.
        if (cacheList.size > MAX_RECORDS) {
            cacheList.removeAt(cacheList.size - 1)
        }

        saveToDisk(context)
        onHistoryChanged?.invoke()
        return record
    }

    @Synchronized
    fun clearRecords(context: Context) {
        cacheList.clear()
        nicknameSet.clear()
        getPrefs(context).edit().remove(KEY_HISTORY_JSON).remove(KEY_SENT_NAMES).apply()
        onHistoryChanged?.invoke()
    }

    @Synchronized
    fun getCount(context: Context): Int {
        ensureLoaded(context)
        return cacheList.size
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (isLoaded) return
        cacheList.clear()
        nicknameSet.clear()

        val jsonStr = getPrefs(context).getString(KEY_HISTORY_JSON, null)
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val record = SentRecord(
                        id = obj.optString("id", "${i}"),
                        nickname = obj.optString("nickname", "Unknown"),
                        gender = obj.optString("gender", "Unknown"),
                        timestamp = obj.optLong("timestamp", 0L),
                        timeStr = obj.optString("timeStr", ""),
                        type = obj.optString("type", "Sapaan")
                    )
                    cacheList.add(record)
                    nicknameSet.add(normalize(record.nickname))
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
        getPrefs(context).getStringSet(KEY_SENT_NAMES, null)?.let { nicknameSet.addAll(it) }
        isLoaded = true
    }

    @Synchronized
    private fun saveToDisk(context: Context) {
        try {
            val array = JSONArray()
            for (record in cacheList) {
                val obj = JSONObject().apply {
                    put("id", record.id)
                    put("nickname", record.nickname)
                    put("gender", record.gender)
                    put("timestamp", record.timestamp)
                    put("timeStr", record.timeStr)
                    put("type", record.type)
                }
                array.put(obj)
            }
            getPrefs(context).edit()
                .putString(KEY_HISTORY_JSON, array.toString())
                .putStringSet(KEY_SENT_NAMES, HashSet(nicknameSet))
                .apply()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
