# Ego Capture — Android stereo / IMU / USB audio recorder

[简体中文](README.zh-CN.md) · [2UQ2 calibration tools](https://github.com/EgoOneLabs/Ego-Calib-Tools/tree/feat/ego-stereo-calibration/ego_stereo)

Android USB Host recorder for the **2UQ2** stereo headband (`1bcf:0b15`). This branch integrates the delivered **code16 / 0.3.5-capture-ui** source, replacing the old code5 snapshot on the base branch. Package: `com.egouvc.phone`; tested phone: Huawei STG-AL00.

## Features and evidence

- Continuous sampled stereo preview (typically 5–10 FPS), with actual rendered-frame FPS; the code15 single-visible-layer fix is retained.
- Raw 3840×1080 MJPEG capture; lossless left/right splitting into 1920×1080 MKV after stopping. Selectable **target** saved frame rate, with actual-rate reporting.
- Headband USB UVC XU IMU recording, original counts/binary packets, request diagnostics and nominal SI export (m/s², rad/s). A separate non-recording IMU probe remains under device diagnostics.
- Headband USB audio recording/export; the tested case produced 48 kHz stereo PCM16 WAV.
- Independent local saving/export state: unplugging after stopping does not cancel that existing job.
- Modern bilingual card UI, fixed recording controls and expandable save details. No bundled historical calibration report or calibration-results card.

**Historical workstation validation:** code16 built successfully; 40 JUnit tests in 14 suites passed. The user confirmed the preview fix and simplified UI. One stop-then-unplug export, real MKV/WAV decoding and IMU CSV consistency were checked. These are scoped results, not a blanket reliability certification. See [validation and handoff](docs/STATUS-20261106.md).

## Build

JDK17, Gradle8.7, Android SDK35, NDK `27.3.13750724`, AGP8.6.1, Kotlin1.9.24. Android minSdk31 / targetSdk35.

```bash
git clone --branch feat/android-capture-code16 https://github.com/EgoOneLabs/Ego-Capture.git
cd Ego-Capture
printf 'sdk.dir=%s\n' "$HOME/Android/Sdk" > local.properties
./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon --max-workers=4 --console=plain
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Use `adb devices -l` first. For a phone connected to the workstation:

```bash
adb install --no-streaming -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.egouvc.phone/.MainActivity
```

The existing app must have the same signing certificate. Keep the existing local debug keystore; none is stored here. Do not uninstall to bypass a signature mismatch: that risks deleting local recordings. A rebuild with another keystore is not necessarily compatible with the already installed APK.

## Capture and outputs

On the phone: attach the headband with USB OTG, grant camera/USB/audio permissions, open the camera, enter task/device labels, select the target FPS and record. After stopping, keep the app in the foreground until export completes. For workstation installation, connect the phone to the workstation first, then reconnect the headband.

Raw sessions live under the app-specific `files/episodes/episode_*` directory. They include `video.mjpg`, `video_index.csv`, `imu_samples.csv`, `imu_packets.bin`, quality/metadata and audio diagnostics. Exported task/device sessions under `files/data/` contain:

| Relative path | Meaning |
|---|---|
| `videos/left.mkv`, `videos/right.mkv` | Split original JPEG frames in MKV |
| `timestamps/left_timestamps.txt`, `timestamps/right_timestamps.txt` | Video timestamps |
| `imu/imu0.csv` | Nominal SI IMU export, 11-column contract |
| `imu/imu_raw_counts.csv` | Original sensor counts |
| `audio/audio.wav` | USB headband audio, when captured successfully |
| `meta.json`, export indexes/diagnostics | Provenance and quality information |

## Known limits

- IMU nominal scaling is not per-unit precision calibration. Timing is packet-level; an individual verified sample clock is not provided. Missing counter positions were observed in an existing recording. Zero quaternion fields mean **orientation unavailable**.
- A target of 30 FPS does not certify strict constant-rate capture. Existing media contained a maximum adjacent frame gap of about 67.39 ms.
- Hardware exposure/IMU synchronization, camera–IMU calibration repeatability, long recordings, reconnect-during-export and foreground/background recovery are not fully accepted.
- Camera calibration remains a workstation task. Historical DS candidate parameters are not activated by this app.

The synchronized app/native source inventory is recorded in [code16-source-manifest.json](docs/code16-source-manifest.json). No private signing keys, APKs or raw recordings are committed.
