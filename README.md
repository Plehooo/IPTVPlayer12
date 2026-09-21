# A01 Mirror

Android screen mirroring + local recording aimed at an Advance STP-A01 DLNA renderer.

## Design

The app captures the phone display with Android MediaProjection, encodes H.264, captures eligible app playback audio with Android AudioPlaybackCapture, encodes MPEG-1 Layer III, muxes both into 188-byte MPEG-TS, serves the live stream over HTTP, and asks a DLNA renderer to play that URI with UPnP AVTransport.

The same transport stream is recorded to `Download/A01Mirror` through MediaStore.

### Important capability limits

This is **DLNA streaming, not Miracast**. A DLNA renderer must accept a continuously generated local HTTP MPEG-TS URI through its AVTransport implementation. The STP-A01 documentation lists DLNA plus H.264 and MPEG-1/2 Layer I/II/III decoding, which is why the stream is built around H.264 + MPEG-1 Layer III. Actual acceptance of a live local stream is firmware-dependent.

Android playback capture is policy-controlled. An app may allow or block its audio from being captured, and protected/secure content may also restrict capture. Video can therefore work while internal audio is silent for a particular source app.

The output canvas is kept in a TV-friendly landscape aspect ratio. Android's projection scaling preserves the captured content's aspect ratio, so portrait content is fitted rather than stretched; landscape content fills the normal 16:9 canvas when started in landscape.

## GitHub build — no Android SDK needed on the phone

The repository includes a GitHub Actions workflow. GitHub-hosted runners install Java and Gradle and build the APK automatically.

1. Create an empty GitHub repository.
2. From Termux, extract this project and push it:

```bash
cd ~/storage/downloads
unzip A01Mirror-FINAL.zip
cd A01Mirror

git init
git branch -M main
git add .
git commit -m "A01 Mirror initial release"
git remote add origin https://github.com/USERNAME/A01Mirror.git
git push -u origin main
```

3. Open GitHub → **Actions** → **Build A01 Mirror APK**.
4. Open the successful workflow run and download the `A01Mirror-debug` artifact.

The workflow deliberately pins AGP 9.4.0, Gradle 9.7.1 and JDK 17. See the official Android Gradle Plugin 9.4 release notes.

## Android build configuration

AGP 9+ has built-in Kotlin support, so this project intentionally does **not** apply `org.jetbrains.kotlin.android`. This avoids the duplicate `kotlin` extension error that occurs when the old Kotlin Android plugin is applied on top of AGP built-in Kotlin.

The app targets API 35 and compiles against API 36. The only native third-party dependency is TAndroidLame 1.1 for MPEG Layer III encoding.

## First test

Use the same Wi-Fi/LAN for the phone and STP-A01. Enable the A01's DLNA/network-media function, open the app, scan for the renderer, select it, start with **1280×720 / 30 FPS**, approve screen capture and microphone/audio permission, then start mirroring.

Once the end-to-end DLNA path is confirmed on the specific A01 firmware, try 1920×1080 and 60 FPS as a second step.

## Native dependency note

TAndroidLame is a GPL-3.0 project and uses native code. Verify its license obligations before distributing the APK outside personal testing. Its repository is linked below.

- TAndroidLame: https://github.com/naman14/TAndroidLame
- Android MediaProjection: https://developer.android.com/media/grow/media-projection
- Android playback capture: https://developer.android.com/media/platform/av-capture
- Android 16 KB page-size guidance: https://developer.android.com/guide/practices/page-sizes


## STB tidak ketemu / IP tidak terbaca

- Pencarian SSDP dikirim per-interface (Wi-Fi, hotspot HP, LAN), jadi tetap jalan walau data seluler jadi jaringan default atau Wi-Fi/hotspot tanpa internet.
- Isi kolom **IP STB** (angka yang tampil di layar STB) lalu tekan **Cari STB DLNA**. Aplikasi mencoba SSDP unicast ke IP itu, lalu menebak alamat deskripsi UPnP di port/path umum. IP terakhir disimpan otomatis.
- Bila tetap gagal, laporan pencarian (interface yang dipindai, balasan SSDP, port terbuka) tampil di kolom status.
- IP HP untuk URL stream dihitung ke arah STB (soket UDP `connect`, sama dengan `local_ip_for_renderer()` di `advance01-media-center-v6`), jadi benar untuk Wi-Fi biasa maupun hotspot HP.
- Format DLNA (DIDL tanpa `DLNA.ORG_PN`, `OP=01` + `FLAGS`, HTTP/1.1 chunked, header `contentFeatures.dlna.org` / `transferMode.dlna.org`, jeda antara `SetAVTransportURI` dan `Play`, Play baru dikirim setelah ada frame video pertama) disamakan dengan `advance01-media-center-v6`. Default video 720p / 25 fps, H.264 Main, audio MPEG-1 Layer III 48 kHz.
- Tombol **Log DLNA** menampilkan laporan pencarian dan 30 SOAP terakhir (mirip `soap_trace` di tar v6) untuk dikirim saat ada error. Saat STB dipilih, aplikasi langsung menguji `GetTransportInfo` dan `GetProtocolInfo` dan menampilkan hasilnya.
- Chunk HTTP dipotong per 188×32 byte (kelipatan paket TS), PAT/PMT dikirim tiap 0,25 detik, seperti ffmpeg di tar v6.


## MAX3 low-latency notes
- Keeps the existing project structure and DLNA flow.
- Uses a hardware AVC encoder with Surface input when available.
- Avoids per-TS-packet allocations in the MPEG-TS hot path.
- PAT/PMT control packets never flush the live queue as keyframes.
- Live client queues keep fresh data and can recover from codec resets without recreating MediaProjection.
- Recording I/O is isolated from the live path and bounded under memory pressure.
- HTTP semantics intentionally match the working Advance A01 v6 server more closely.


## Update 1.1.0 (stabil saat aplikasi berat)

Struktur file/kelas tidak berubah; yang diperbaiki:

- **Service satu proses dengan UI** (`android:process` dihapus) dan `DlnaController.setDiscoveryContext()` juga dipanggil di service. Sebelumnya SOAP `SetAVTransportURI`/`Play` di service tidak diikat ke jaringan Wi-Fi, dan tombol **Log DLNA** tidak menampilkan SOAP Play.
- **Error selalu terlihat**: Toast lewat thread utama, notifikasi error terpisah yang tetap ada setelah service berhenti, dan status/pesan error tampil di layar utama.
- **Klien STB baru** tidak lagi mendapat IDR lama + P-frame baru (artefak ±1 dtk). Klien menunggu IDR baru yang diminta ke encoder; IDR tersimpan hanya dipakai bila belum ada P-frame sesudahnya. Tidak ada lagi penulisan socket yang memblokir lock broadcaster.
- **Jaringan tersendat / CPU sibuk**: antrean kirim lebih dalam (96 paket), frame P tidak dibuang satu-satu. Bila klien terlalu tertinggal, antrean dikosongkan dan resync bersih di IDR berikutnya (encoder membuat IDR atas permintaan). Pacing 2,5x bitrate, buffer kirim 64 KB.
- **Prioritas thread**: encoder `URGENT_DISPLAY`, audio `URGENT_AUDIO`, penulis klien `DISPLAY`, penulis rekaman normal (bukan background). Wi-Fi lock low-latency + high-perf.
- **Spesifikasi TS**: PES header (`data_alignment` di byte 6), AUD sebelum tiap frame, SPS/PPS + PAT/PMT sebelum tiap IDR, PCR 180 ms sebelum PTS.
- **Sinkron A/V**: PTS video dan audio memakai jam yang sama (`TsBroadcaster.clockOriginNs`).
- **Rekaman**: antrean 32 MB, tidak mati permanen saat disk lambat (celah kecil lalu lanjut di IDR berikutnya), mulai tepat di IDR, ekor rekaman dikuras saat Stop, status rekaman nyata di UI dan notifikasi.
- **UI baru** (kartu gelap, logo, status LIVE, statistik live, tombol Izin baterai), ikon launcher adaptif + ikon notifikasi, izin notifikasi Android 13+.
- Capture audio kini juga menangkap `USAGE_UNKNOWN`.

Untuk Redmi/HyperOS: buka **Izin baterai** dan set A01 Mirror ke *Tanpa batasan*; pakai 720p 25/30 fps.

## Update 1.2.0 (semua HP, bukan hanya Redmi)

Semua bersifat tambahan; tidak ada file/fitur yang dihapus.

- **Otomatis sesuai HP** (default baru di Resolusi dan Frame rate): resolusi/fps awal dipilih dari RAM, jumlah inti CPU dan media performance class. Apa pun pilihan Anda, `H264Encoder.fitToDevice()` menurunkannya ke batas encoder hardware HP itu (fps dulu, baru resolusi) memakai `MediaCodecInfo.VideoCapabilities`.
- **Bitrate awal mengikuti kecepatan link Wi-Fi** (bila terbaca), lalu adaptasi otomatis seperti sebelumnya. Link lambat tidak langsung membanjiri STB.
- **Audio senyap sebagai cadangan**: bila audio internal tidak tersedia (izin ditolak, HP tidak mendukung, library MP3 native tidak bisa dimuat di ABI HP itu), video tetap tayang dan STB tetap menerima audio (frame MP3 senyap) sehingga tidak menunggu lalu buffering. `AudioRecord.read` yang error tidak lagi memutar CPU 100%.
- **Renderer HTTP/1.0** (lama) dilayani tanpa chunked.
- Tombol **Autostart / hemat daya** khusus merek (Xiaomi/Redmi/POCO, Oppo/Realme/OnePlus, Vivo/iQOO, Huawei/Honor, Samsung, Asus); bila layar merek tidak ditemukan, jatuh ke pengaturan baterai standar Android.


## Update 1.3.0 (mirror + playlist dalam satu APK)
- Menambahkan mode **Playlist DLNA • tanpa mirror** di aplikasi yang sama. URL M3U/M3U8 dapat dimuat, item disimpan lokal, lalu setiap item dapat ditekan untuk dikirim langsung ke renderer melalui UPnP AVTransport.
- Menambahkan tombol **Stop TV** untuk playback DLNA langsung.
- Parser playlist menerima `#EXTM3U/#EXTINF`, `group-title`, `tvg-logo`, URL absolut dan URL relatif. Maksimum 2.000 item disimpan.
- Jalur mirror diperketat ke **live edge**: antrean klien dipendekkan, audio/video lama dibuang saat resync, pacing socket dibuat halus (bukan burst 2,5×), dan thread penulis rekaman diturunkan prioritasnya agar aplikasi foreground berat tidak berebut CPU/I/O.
- PCR MPEG-TS disejajarkan dengan PTS video (tidak lagi sengaja tertinggal 180 ms).
- Fallback audio lebih luas: 48/44,1/32 kHz dan stereo/mono, serta header frame MP3 senyap diperbaiki agar indeks sample-rate tidak tertukar.

**Catatan kompatibilitas:** playlist direct-cast tidak mentranscode. A01 harus mendukung format/codec URL tersebut. Android playback capture juga tetap mengikuti kebijakan aplikasi sumber; Android mendokumentasikan bahwa hanya audio dengan usage tertentu dan capture policy yang mengizinkan yang dapat ditangkap.

## Update 1.4.0 — live-edge + dua mode dalam satu APK
- Mirror layar + audio tetap memakai pipeline MediaProjection → hardware H.264 → MPEG-TS → HTTP/DLNA.
- Jalur live sekarang dipace terus-menerus, bukan burst saat antrean sedang kosong, dan audio yang sudah terlalu jauh tertinggal dari video dibuang agar sinkronisasi renderer lebih stabil.
- Mode **Mirror saja** ditambahkan sebagai jalur paling ringan; rekaman tidak dibuka sehingga CPU/I/O storage lebih longgar saat memakai YouTube, game, browser, atau aplikasi berat.
- Mode **Playlist DLNA tanpa mirror** tetap satu APK yang sama: M3U/M3U8 dimuat, disimpan sebagai cache, item dapat ditekan satu per satu, dan URL dikirim langsung ke DMR.
- Tombol **Stop TV / Playlist** menghentikan pemutaran langsung tanpa menyentuh sesi screen-mirroring.
- Tidak ada file project lama yang dihapus; perubahan hanya menambah kemampuan dan memperketat jalur live.


## Update 1.6.0 — audio smooth + anti-burst TS + playlist besar
- Audio muxing dipisahkan dari muxing video besar supaya frame audio tidak ikut menunggu IDR.
- MPEG-TS live dipecah 7 paket TS (1316 byte) dan langsung di-flush tiap unit untuk mengurangi burst di socket.
- Saat writer sempat tertahan, scheduler wire tidak mengejar backlog dengan burst; data video lama di-resync ke IDR baru.
- Antrean audio dipertahankan dalam jendela pendek saat resync video agar mute gap tidak melebar.
- URL direct dengan suffix media tambahan (M4V, M2TS, MPEG-TS, AC3/E-AC3, WAV, OGG/Opus) dikenali tanpa mengubah pipeline DLNA.
- Playlist memakai ListView + recycling, sehingga ribuan item tetap dapat di-scroll tanpa membuat ribuan Button sekaligus.
- HLS/M3U8 dan DASH/MPD tetap dapat dikirim langsung bila renderer menerima format tersebut; renderer A01 tetap menentukan codec/fitur yang benar-benar dapat diputar.


## Update 1.7.0 — adaptif real-time (video + audio MP3 mulus, ringan RAM)

Tidak ada file/fitur yang dihapus dan struktur kelas tidak berubah; semua bersifat perbaikan/penambahan.

**Naik-turun otomatis mengikuti HP dan jaringan (tanpa angka yang dikunci):**
- Bitrate video disetel real-time dari tiga sumber: kecepatan kirim yang *terukur* ke STB (`TsBroadcaster.measuredGoodputBps()`), keterlambatan encoder saat CPU/GPU dipakai aplikasi berat (YouTube/game), dan suhu HP (`PowerManager` thermal status). Turun cepat, naik pelan (probing 15% tiap ≥4 dtk saat sehat). Batas bawah mengikuti resolusi.
- Frame rate juga naik-turun otomatis (tangga 60→30→25→20→15 fps sesuai permintaan awal): turun bila encoder terus tertinggal / jaringan tetap tertekan / HP terlalu panas ≥3 dtk, naik lagi setelah sehat ≥30 dtk. Resolusi tidak berubah di tengah stream (decoder STB murah tidak mau inisialisasi ulang ukuran gambar). Kualitas aktif tampil di layar Live.
- Mode Otomatis: profil awal 720p untuk HP menengah (perbaikan: 1.6.0 salah memilih 540p untuk sebagian besar HP 4–6 GB), 720p30 hanya HP kuat, 540p/480p untuk HP lemah.

**Video tidak macet / tidak glitch:**
- Pengiriman ke STB ditulis ulang: state pengiriman hanya disentuh satu thread (menghilangkan race yang bisa menutup koneksi), audio+tabel selalu dikirim lebih dulu dari video, IDR tidak pernah dipotong di tengah jalan, pacing 2× (4× saat IDR/backlog) sehingga IDR besar tidak memicu resync berulang. Umur frame dinilai hanya saat mulai dikirim.
- Simulasi aturan 1.6.0 vs 1.7.0 (IDR 20–45% dari bit satu GOP): 1.6.0 masuk putaran resync (fps efektif 0–2), 1.7.0 tetap 25 fps.
- Prioritas thread encoder dan pengirim dikembalikan (URGENT_DISPLAY / DISPLAY); thread rekaman prioritas normal.
- PCR 100 ms lebih awal dari PTS (bantalan kecil untuk STB murah).

**Audio MP3 tidak ngeglitch:**
- Frame MP3 senyap: indeks sample-rate diperbaiki (00=44,1 kHz, 01=48 kHz, 10=32 kHz; diverifikasi ffprobe).
- Sumber mono di-upmix ke stereo (sebelumnya LAME membaca tiap sampel kedua = audio 2× cepat).
- PTS audio dikoreksi halus dan disejajarkan langsung bila selisih >200 ms; audio tidak lagi dibuang karena "tertinggal dari video".
- LAME memakai preset cepat (7) di HP lemah, preset 5 di HP kuat.

**Ringan RAM/CPU:**
- Antrean rekaman mengikuti heap HP (3–12 MB, bukan 16 MB tetap).
- Playlist di-parse streaming baris demi baris (tidak lagi String 16 MB + salinan); koma di dalam atribut `#EXTINF` tidak memotong nama channel.
- Thread pengirim tidur/bangun (park/unpark) saat idle, bukan polling.


## Update 1.9.0 — screen blackout tanpa root
- Pipeline mirror lama tetap dipakai: `MediaProjection → VirtualDisplay → H.264 → MPEG-TS → DLNA/HTTP`.
- `ScreenPowerController.kt` sekarang **tidak menjalankan `su`, `cmd display`, atau shell privileged**. Mode layar gelap memakai kontrol brightness sistem minimum + wake lock layar agar MediaProjection tetap aktif.
- Android mewajibkan izin khusus `WRITE_SETTINGS` untuk aplikasi yang ingin mengubah `Settings.System.SCREEN_BRIGHTNESS`; aplikasi membuka halaman izin ini otomatis saat pertama kali tombol dipakai.
- Controller menyimpan brightness + mode auto/manual sebelum dimatikan dan mengembalikannya saat mode berhenti.
- `ACTION_SCREEN_OFF` dan `ACTION_SCREEN_ON` dipantau melalui receiver runtime. Jika tombol POWER fisik membuat perangkat non-interaktif, controller melakukan wake-up singkat dan mengulang brightness minimum secara best-effort.
- Ini adalah **blackout tanpa root**: panel dijaga tetap aktif tetapi backlight dipaksa ke minimum supaya tampak mati sementara frame MediaProjection tetap hidup. Android tidak memberikan aplikasi biasa API publik untuk melakukan physical-display power-off seperti shell `cmd display power-off`.

