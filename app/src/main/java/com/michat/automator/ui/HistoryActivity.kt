package com.michat.automator.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.michat.automator.R
import com.michat.automator.data.HistoryManager
import com.michat.automator.data.SentRecord

class HistoryActivity : AppCompatActivity() {

    private lateinit var btnBack: Button
    private lateinit var btnClear: Button
    private lateinit var tvTotalBadge: TextView
    private lateinit var etSearch: EditText
    private lateinit var btnFilterAll: Button
    private lateinit var btnFilterFemale: Button
    private lateinit var btnFilterMale: Button
    private lateinit var tvEmpty: TextView
    private lateinit var lvHistory: ListView

    private var allRecords: List<SentRecord> = emptyList()
    private var displayedRecords: MutableList<SentRecord> = mutableListOf()
    private lateinit var adapter: HistoryAdapter

    private var selectedGenderFilter: String = "ALL" // "ALL", "Perempuan", "Laki-laki"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        btnBack = findViewById(R.id.btn_history_back)
        btnClear = findViewById(R.id.btn_clear_history)
        tvTotalBadge = findViewById(R.id.tv_history_total_badge)
        etSearch = findViewById(R.id.et_search_history)
        btnFilterAll = findViewById(R.id.btn_filter_all)
        btnFilterFemale = findViewById(R.id.btn_filter_female)
        btnFilterMale = findViewById(R.id.btn_filter_male)
        tvEmpty = findViewById(R.id.tv_empty_history)
        lvHistory = findViewById(R.id.lv_history)

        adapter = HistoryAdapter(this, displayedRecords)
        lvHistory.adapter = adapter

        btnBack.setOnClickListener {
            finish()
        }

        btnClear.setOnClickListener {
            if (allRecords.isEmpty()) {
                Toast.makeText(this, "Riwayat masih kosong.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AlertDialog.Builder(this)
                .setTitle("Hapus Riwayat")
                .setMessage("Yakin ingin menghapus seluruh daftar riwayat pengiriman?")
                .setPositiveButton("Hapus") { _, _ ->
                    HistoryManager.clearRecords(this)
                    loadData()
                    Toast.makeText(this, "Riwayat berhasil dibersihkan", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Batal", null)
                .show()
        }

        btnFilterAll.setOnClickListener {
            selectedGenderFilter = "ALL"
            highlightFilterButton(btnFilterAll)
            filterData()
        }

        btnFilterFemale.setOnClickListener {
            selectedGenderFilter = "Perempuan"
            highlightFilterButton(btnFilterFemale)
            filterData()
        }

        btnFilterMale.setOnClickListener {
            selectedGenderFilter = "Laki-laki"
            highlightFilterButton(btnFilterMale)
            filterData()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterData()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadData()
        highlightFilterButton(btnFilterAll)
    }

    private fun loadData() {
        allRecords = HistoryManager.getRecords(this)
        tvTotalBadge.text = "Total: ${allRecords.size} kontak dikirimi"
        filterData()
    }

    private fun filterData() {
        val query = etSearch.text.toString().trim().lowercase()
        displayedRecords.clear()

        for (record in allRecords) {
            val matchQuery = query.isEmpty() || record.nickname.lowercase().contains(query)
            val matchGender = when (selectedGenderFilter) {
                "Perempuan" -> record.gender.equals("Perempuan", ignoreCase = true)
                "Laki-laki" -> record.gender.equals("Laki-laki", ignoreCase = true)
                else -> true
            }

            if (matchQuery && matchGender) {
                displayedRecords.add(record)
            }
        }

        if (displayedRecords.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            lvHistory.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            lvHistory.visibility = View.VISIBLE
        }

        adapter.notifyDataSetChanged()
    }

    private fun highlightFilterButton(activeBtn: Button) {
        val buttons = listOf(btnFilterAll, btnFilterFemale, btnFilterMale)
        for (btn in buttons) {
            if (btn == activeBtn) {
                btn.alpha = 1.0f
                btn.setBackgroundColor(Color.parseColor("#36435C"))
            } else {
                btn.alpha = 0.6f
                btn.setBackgroundColor(Color.parseColor("#212736"))
            }
        }
    }

    private class HistoryAdapter(
        private val context: Context,
        private val items: List<SentRecord>
    ) : BaseAdapter() {

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): SentRecord = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_history, parent, false)
            val item = items[position]

            val tvGenderBadge = view.findViewById<TextView>(R.id.tv_item_gender_badge)
            val tvNickname = view.findViewById<TextView>(R.id.tv_item_nickname)
            val tvTime = view.findViewById<TextView>(R.id.tv_item_time)
            val tvType = view.findViewById<TextView>(R.id.tv_item_type)

            tvNickname.text = item.nickname
            tvTime.text = item.timeStr
            tvType.text = item.type

            when {
                item.gender.contains("Perempuan", ignoreCase = true) -> {
                    tvGenderBadge.text = "♀"
                    tvGenderBadge.setTextColor(Color.parseColor("#FF4081"))
                }
                item.gender.contains("Laki", ignoreCase = true) -> {
                    tvGenderBadge.text = "♂"
                    tvGenderBadge.setTextColor(Color.parseColor("#448AFF"))
                }
                else -> {
                    tvGenderBadge.text = "?"
                    tvGenderBadge.setTextColor(Color.parseColor("#B0BEC5"))
                }
            }

            return view
        }
    }
}
