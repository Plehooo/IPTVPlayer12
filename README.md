# A01 Mirror — Android → Advance STP-A01 (DLNA)

A prototype Android app for local-network screen mirroring to an Advance STP-A01 via DLNA.

## What it does

- Discovers DLNA MediaRenderer devices using SSDP.
- Requests Android screen-capture permission with MediaProjection.
- Captures the phone screen as H.264.
- Captures app playback audio when Android/app policy allows it, then encodes MP3.
- Muxes H.264 + MPEG audio into a live MPEG-TS stream.
- Hosts that stream over HTTP on the phone.
- Tells the selected DLNA renderer to open the URL using UPnP AVTransport.
- Saves the same MPEG-TS stream as a recording in the Android MediaStore (`Movies/A01Mirror`).

## Important compatibility note

This is **DLNA renderer streaming**, not Miracast. The STP-A01 specification lists DLNA support plus H.264 video and MPEG-1/2 Layer I/II/III audio decoding, so H.264 + MPEG audio in MPEG-TS is used as the compatibility target.

The A01 firmware documentation does not guarantee that every firmware revision accepts a continuously generated local HTTP MPEG-TS URL through AVTransport. If the box refuses the live stream, that is a renderer/firmware limitation rather than a normal Android screen-capture limitation.

## Recommended test setup

1. Connect the A01 to Wi-Fi using its supported Wi-Fi dongle.
2. Put the Android phone and A01 on the same LAN/Wi-Fi.
3. Open the A01's DLNA/network-media feature.
4. Install the APK.
5. Tap **Scan DLNA** and select the STP-A01 renderer.
6. Select 1280×720 / 30 FPS first.
7. Tap **Mulai Mirror + Rekam** and allow screen capture (and audio capture if requested).
8. Rotate YouTube/game to landscape; the TV should keep a 16:9 presentation without stretching. Portrait content will be letterboxed rather than distorted.

## Android audio limitation

Android playback capture only works for audio usages/apps that permit capture. Some apps, DRM-protected media, or secure content may intentionally block capture. The video capture can therefore succeed while internal audio is silent.

## Build in Termux

The project is intentionally wrapper-free so it can be built with an installed Gradle.

```bash
pkg install git gradle
# Ensure Java 17 is available on your Termux installation.
java -version
gradle --version

cd ~/storage/downloads/A01Mirror
gradle assembleDebug --stacktrace
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Push to GitHub from Termux

Create an empty GitHub repository first, then:

```bash
cd ~/storage/downloads/A01Mirror
git init
git branch -M main
git add .
git commit -m "Initial A01 DLNA mirror app"
git remote add origin https://github.com/USERNAME/A01Mirror.git
git push -u origin main
```

After that, GitHub Actions builds the debug APK automatically and publishes it under the workflow run's **Artifacts**.

## Project limitations

- No Android device can capture another app's audio if the source app disallows playback capture.
- Android secure/DRM surfaces may not be capturable.
- DLNA discovery/AVTransport behavior is vendor/firmware dependent.
- A real DLNA renderer is required; a device that only exposes a DLNA player/client is not enough.
- The phone and A01 must be reachable on the same LAN; client isolation/AP isolation can break the stream.
