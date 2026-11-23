# MiChat Push Auto Bot (Standalone Android App)

Aplikasi otomasi Android standalone untuk mengotomasi pengiriman sapaan / push chat pada fitur **Pengguna di Sekitar** (*People Nearby*) di MiChat (`com.michatapp.im`).

Aplikasi ini dirancang menggunakan **Android AccessibilityService** dan **Floating Overlay Widget**, sehingga dapat berjalan langsung di HP **tanpa perlu kabel USB / PC**.

---

## 1. Hasil Analisis Aplikasi MiChat (via Mobile MCP)

| Layar / Halaman | Nama Activity | Elemen Kunci & Resource ID |
| :--- | :--- | :--- |
| **Layar Utama / Home** | `com.zenmen.palmchat.MainTabsActivity` | - Tab Teman: `com.michatapp.im:id/tab_contacts`<br>- Menu Pengguna Sekitar: `com.michatapp.im:id/peopleNearby_item` |
| **Pengguna di Sekitar** | `com.zenmen.palmchat.peoplenearby.PeopleNearbyActivity` | - List Pengguna: `com.michatapp.im:id/peopleNearbyContent` (`RecyclerView`)<br>- Item Akun: `com.michatapp.im:id/nb_item`<br>- Nama Akun: `com.michatapp.im:id/nick_name`<br>- Jarak: `com.michatapp.im:id/distance`<br>- Status Teman: `com.michatapp.im:id/is_friends` *(text: `(Teman)`)*<br>- Menu Filter: `com.michatapp.im:id/action_button` |
| **Detail Profil Pengguna** | `com.zenmen.palmchat.contacts.UserDetailActivity` | - Tombol Sapa: `com.michatapp.im:id/action_textview` *(text: `Sapa Dia`)* |
| **Form Kirim Sapaan** | `com.zenmen.palmchat.contacts.NewContactRequestSendActivity` | - Input Teks: `com.michatapp.im:id/request_information` (`EditText`)<br>- Tombol Kirim: `com.michatapp.im:id/action_button` *(text: `Kirim`)* |

---

## 2. Fitur & Logika Otomasi

1. **Auto-Route / Masuk Menu Otomatis**:
   - Jika aplikasi MiChat masih berada di luar (Home / Chat / Momen), bot secara otomatis mengklik tab **Teman** (`tab_contacts`), lalu mengklik **Pengguna di Sekitar** (`peopleNearby_item`).
2. **Filter & Anti-Duplikasi**:
   - Melewati pengguna yang sudah berteman (`is_friends` = `(Teman)`).
   - Menyimpan riwayat nama yang sudah disapa dalam sesi agar tidak menyapa orang yang sama berulang kali.
   - Melewati profil yang status tombolnya bukan *"Sapa Dia"* (misal sudah pernah chat).
3. **Spintax Engine (Pesan Bervariasi)**:
   - Mendukung format `{Halo|Hai|Pagi} kak {salam kenal|lagi apa?}` untuk memvariasikan isi pesan secara acak.
4. **Proteksi Anti-Banned & Shadowban**:
   - **Random Delay**: jeda acak (default 8–15 detik) antar sapaan agar tidak dicurigai sebagai bot oleh sistem MiChat.
   - **Max Limit**: membatasi jumlah target per sesi (misal 20 orang) untuk menjaga reputasi akun.
5. **Floating Overlay Control**:
   - Tombol mengambang di atas layar MiChat dengan tombol **START**, **STOP**, counter pengiriman, dan status live.

---

## 3. Struktur Project

```
c:\laragon\www\michat\
├── app\
│   ├── build.gradle
│   └── src\main\
│       ├── AndroidManifest.xml
│       ├── java\com\michat\automator\
│       │   ├── service\
│       │   │   ├── MiChatAutomationService.kt  # Logika utama Accessibility
│       │   │   └── FloatingOverlayService.kt    # Floating Widget UI
│       │   └── ui\
│       │       └── MainActivity.kt             # Layar setting template & izin
│       └── res\
│           ├── layout\
│           │   ├── activity_main.xml
│           │   └── layout_floating_widget.xml
│           ├── values\strings.xml
│           └── xml\accessibility_service_config.xml
├── tester\
│   └── test_flow.py                           # Python ADB tester script
├── build.gradle
└── settings.gradle
```

---

## 4. Cara Build & Menjalankan di HP

1. Buka folder `c:\laragon\www\michat` di **Android Studio**.
2. Hubungkan HP kamu atau gunakan USB Debugging yang sudah aktif.
3. Klik **Run** atau **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
4. Install APK ke HP kamu.
5. Saat aplikasi pertama kali dibuka:
   - Berikan izin **Tampilkan di atas aplikasi lain (Display Over Other Apps)**.
   - Aktifkan layanan aksesibilitas **MiChat Push Auto** di menu Pengaturan Aksesibilitas.
6. Tekan tombol **Buka Floating Controller & MiChat**.
7. Buka MiChat, lalu tekan tombol **START** pada floating widget untuk menjalankan bot otomatis!
