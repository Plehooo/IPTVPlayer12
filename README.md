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
