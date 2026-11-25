# TerraPulse

Aplikasi peta & navigasi Android berbasis OSM (`osmdroid`) + GPS real-time. Dibuat buat tugas Pemrograman Mobile.

## Fitur
- **GPS & Kompas live** — tracking posisi, radius akurasi, kecepatan, ketinggian, panel telemetri.
- **Pencarian** — autocomplete (Photon + fallback Nominatim), riwayat, favorit lokal.
- **4 Layer peta** — Street, Satelit, Dark, Topo.
- **Rute jalan asli** — OSRM (mobil, sepeda, jalan kaki) + estimasi waktu & daftar belokan.
- **Navigasi aktif** — HUD turn-by-turn, speedometer, rotasi peta, auto-reroute, suara TTS Indonesia.
- **Mode Simulasi** — buat demo gerak di emulator/kelas tanpa harus jalan keluar.

## Stack
Kotlin · XML (ViewBinding + Material 3) · MVVM (`StateFlow`) · osmdroid · Play Services Location · Gson.
Gratis semua, gak pakai API key.

## Cara jalanin
1. Buka di Android Studio.
2. Tunggu Gradle sync.
3. Klik Run. Beres.
