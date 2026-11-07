# Local files and repository completeness

The repository includes the code16 application, USB audio/XU/SI export source,
JNI lossless JPEG splitter, vendored UVC/libusb/libuvc/JPEG/YUV sources and their
existing notices, Android resources, Gradle wrapper, and inspection scripts.
No separate historical patch ZIP or private workstation source folder is needed.

From a fresh checkout run:

```bash
python3 scripts/verify_checkout.py
```

This checks the 1,210-file code16 source manifest, wrapper, local module and JNI
makefile inputs. It does not build an APK or replace hardware acceptance.
The unchanged code16 baseline has a previously recorded workstation build and
40-test result; this repository-completeness change does not claim a new build.

Install the documented JDK/Android SDK/NDK and allow Gradle/Maven dependencies
to download as described in the root README. `local.properties` is generated
for the current machine and is intentionally not copied from the author's SDK
path. Do not commit signing keys or a machine-specific Android SDK tree.

Recordings, device permissions and USB hardware are runtime inputs. Calibration
YAML/PDF reports are managed in the companion Ego-Calib-Tools repository; the
capture application does not require historical calibration files to record.
There are no code16 assets to recover from the old calibration-display build.

If a new feature introduces a local AAR, model or configuration, commit the
redistributable file and its provenance or provide a versioned download with a
checksum. Do not document an undistributed `/home/.../Downloads/*.zip` as an
installation dependency.
