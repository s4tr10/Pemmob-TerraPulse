# TerraPulse — Design Specification

## 1. Intent & Why
Project ini dikembangkan untuk tugas mata kuliah **Pemrograman Mobile** dengan target mendapat feedback positif dan nilai terbaik dari dosen. Aplikasi harus menawarkan **fungsionalitas GPS, routing, dan navigasi sekelas Google Maps**, namun hadir dengan **identitas UI/UX yang unik, interaktif, dan ergonomis** (bukan kloningan Google Maps), dengan performa mulus dan alur penggunaan (*flow*) yang sangat jelas.

Visible app display name: **TerraPulse** (keeping `applicationId = "com.example.googlemapsproject"` so Android Studio run configs stay intact).

## 2. Goals, Non-Goals, & Anti-Goals
- **Goals:**
  1. **GPS Real-Time & Sensor Kompas:** Tracking lokasi kontinu (`FusedLocationProviderClient` + `LocationCallback`), pengecekan/prompt menyalakan GPS (`SettingsClient`), fallback `getCurrentLocation`, kompas halus berbasis `SensorManager`, serta **GPS Telemetry Sheet** (akurasi, kecepatan, ketinggian, heading, koordinat copyable, status GPS).
  2. **UI/UX Unik (Konsep 1 — Floating Spatial Dock & Telemetry Pill):** Peta *full-bleed edge-to-edge*, tema Material 3 khusus **Deep Slate + Teal/Cyan** (Light & Dark mode otomatis), seluruh ikon bawaan Android lama (`ic_menu_*`) diganti ikon vektor tajam, kontrol pencarian utama di **dock melayang bawah**, dan **Live GPS Pill + Kompas Interaktif** di atas.
  3. **Pencarian Cerdas & Tempat Lokal:** Autocomplete live (debounce 400ms via Photon + fallback Nominatim), **Tempat Favorit** & **Riwayat Pencarian** (maks. 10) tersimpan lokal, serta chip akses cepat.
  4. **Multi-Layer Peta + Tile Cache Aktif:** 4 gaya peta (*Street*, *Satelit*, *Dark*, *Topografi*) melalui **Layer Picker 2×2**, dengan penghapusan `clearOsmDroidCache()` agar loading peta jauh lebih lancar.
  5. **Rute Jalan Raya Asli + Navigasi Aktif (Turn-by-Turn):** Rute mengikuti jalan raya via server publik OSRM (3 moda: **Mobil**, **Sepeda**, **Jalan Kaki**), jarak & ETA, daftar instruksi belokan Bahasa Indonesia, polyline dua lapis (border + fill), HUD navigasi aktif (banner belokan berikutnya, speedometer live, rotasi peta mengikuti bearing, auto-advance step, *auto-reroute* saat melenceng >45m, banner "Tiba di Tujuan"), plus **Voice Guidance** opsional (`TextToSpeech` Bahasa Indonesia, bisa mute/unmute).
  6. **Mode Simulasi Demo:** Playback GPS halus menyusuri rute (kecepatan `1x/2x` + `Pause/Play`) agar seluruh fitur navigasi, rotasi peta, dan belokan dapat didemokan secara hidup di dalam kelas atau emulator.
  7. **Arsitektur Bersih & Teruji:** MVVM + `StateFlow` modular + unit test JVM untuk parser geocoding/routing, kalkulasi jarak/off-route, penerjemah instruksi belokan, dan state navigasi.
- **Non-Goals:** API key berbayar / Google Maps SDK, backend server sendiri, ulasan/sosial, traffic live berbayar, unduh peta offline per wilayah, Room/KSP/Retrofit, dan **navigasi background saat layar mati**.
- **Anti-Goals:** Siluet kloningan Google Maps, peta lag/berkedip, panel bawah saling bertumpuk, tombol *Back* langsung keluar aplikasi saat masih di mode rute/navigasi, atau crash saat GPS mati / izin ditolak / jaringan error.

## 3. Constraints
- **Stack:** Kotlin, Android XML + ViewBinding, Material 3 (`1.14.0`), `osmdroid-android:6.1.18`, `play-services-location:21.2.0`, AGP `9.3.3` (built-in Kotlin), `compileSdk = 37`, `minSdk = 24`.
- **Dependencies:** Only already-declared or already-locally-cached libraries (`lifecycle-viewmodel-ktx:2.7.0`, `recyclerview:1.2.1`, `coordinatorlayout:1.1.0`, `gson:2.8.5`).
- **Public APIs (No API Keys):** Esri Street, Esri Satellite, CartoDB Dark Matter, OpenTopoMap; Photon + Nominatim; OSRM (`routing.openstreetmap.de/routed-{car|bike|foot}` + fallback `router.project-osrm.org`).

## 4. Approaches Considered
- **Approach 1 — Clean Modular MVVM + `StateFlow` (Single-Activity + Modular XML)** *(Chosen)*: Satu `MapView` tanpa flicker/reload, state machine tegas agar flow jelas, arsitektur MVVM bersih untuk penilaian dosen, dan zero konflik KSP/AGP 9.3.3.
- **Approach 2 — Multi-Fragment + Room DB + Retrofit/OkHttp:** Terlalu berat untuk 3 endpoint & penyimpanan favorit sederhana; menambah kompleksitas KSP dan overhead transisi Fragment.
- **Approach 3 — Helper Refactor Without ViewModel:** Rawan bug state antar mode dan kehilangan state saat rotasi layar.

## 5. UX Flow & Screen State Machine (`MapUiState`)
1. **`Explore`:** Top-left Live GPS Pill (tap → GPS Telemetry sheet); Top-right rotating Compass (tap → reset North); Mid-right controls (Layer Picker 2×2, 3-state Camera Mode `Bebas → Ikuti → Ikuti + Kompas`, Zoom `+/-`); Bottom Floating Spatial Dock (Search pill + Favorit & presets). Tap map → pin + reverse geocode → `PlaceSelected`.
2. **`Search`:** Expanded top search bar with Back/Clear + keyboard; Favorites & Recents when empty; up to 6 debounced (400ms, min 2 chars) Photon suggestions with distance when typing. Tap → save recent & enter `PlaceSelected` (or update route origin if picking origin).
3. **`PlaceSelected`:** Floating Place Sheet with name, address, coords, straight-line distance, Favorite star, Copy Coords, and primary "Lihat Rute" CTA.
4. **`RoutePreview`:** Map zooms to route bounding box; editable Origin → Destination header (`RouteOrigin.CurrentGps` or `RouteOrigin.Manual`, plus swap), TravelMode chips (`Mobil` / `Sepeda` / `Jalan Kaki`), ETA & distance, expandable Turn-by-Turn list, primary **"Mulai Navigasi"**, secondary **"Simulasi Demo"**, and manual-origin guard dialog (>100m from real GPS).
5. **`Navigating`:** Top Maneuver Banner (turn icon, countdown, Indonesian instruction, TTS toggle, `MODE SIMULASI` controls when simulating, Arrival view `<25m`), Recenter button when panned away, Bottom Telemetry Bar (speedometer, remaining ETA, remaining distance, Stop button). Auto-advance `<25m`, TTS cues at `≤150m` & `≤35m`, auto-reroute `>45m` (10s cooldown, real GPS only).

## 6. Architecture & Package Layout
- `MainActivity.kt` (thin UI binder + lifecycle owner)
- `model/`: `MapModels.kt`, `GeoMath.kt`, `NavigationEngine.kt`
- `data/`: `GeocodingService.kt`, `RoutingService.kt`, `PlaceStorage.kt`
- `location/`: `GpsTracker.kt`, `CompassSensor.kt`, `RouteSimulator.kt`
- `map/`: `MapTileSources.kt`, `UserLocationOverlay.kt`, `MapViewController.kt`
- `ui/`: `MapViewModel.kt`, `NavigationVoiceCoach.kt`, `PlaceSuggestionAdapter.kt`, `RouteStepAdapter.kt`

## 7. Key Decisions Recorded
1. Visible app name changed to `TerraPulse`; `applicationId` stays `com.example.googlemapsproject`.
2. Foreground-only navigation (`FLAG_KEEP_SCREEN_ON` during `Navigating`; GPS/sensors stop in `onStop()`).
3. Editable origin in `RoutePreview` (defaults to GPS, can switch manually for classroom/emulator demos).
4. Optional Indonesian `TextToSpeech` voice coach with quick mute/unmute toggle.
5. `Gson 2.8.5` for JSON parsing/serialization so JVM unit tests run fast without `org.json` Android stub issues.
