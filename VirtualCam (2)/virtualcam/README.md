# VirtualCam — PC Screen → Android Virtual Camera

Streams your PC screen (or a specific OBS window) over LAN to an Android device and
exposes it as a virtual camera source other apps can read from.

```
virtualcam/
├── server/             PC streaming server (Python)
│   ├── server.py
│   └── requirements.txt
├── android/            Android app (Kotlin)
│   └── app/src/main/java/com/virtualcam/
│       ├── StreamState.kt
│       ├── network/    WebSocket client + frame decoder
│       ├── service/    Foreground service (keeps streaming when backgrounded)
│       ├── camera/     Virtual camera provider
│       └── ui/         MainActivity + ViewModel
└── README.md
```

---

## 1. Run the PC Server

**Requirements:** Python 3.9+

```bash
cd server
pip install -r requirements.txt
```

**Start the server (full screen, default settings):**

```bash
python server.py
```

**Target a specific OBS window (Windows/macOS):**

```bash
python server.py --window "OBS"
```

**All options:**

| Flag        | Default   | Description                                   |
|-------------|-----------|------------------------------------------------|
| `--host`    | `0.0.0.0` | Bind address                                   |
| `--port`    | `8765`    | WebSocket port                                 |
| `--fps`     | `30`      | Target frame rate                              |
| `--quality` | `75`      | JPEG quality (0–100)                           |
| `--width`   | `1280`    | Output frame width                             |
| `--height`  | `720`     | Output frame height                            |
| `--window`  | none      | Partial window title to capture instead of full screen |

The console prints your LAN IP isn't shown automatically — find it with:
- **Windows:** `ipconfig` → IPv4 Address
- **macOS/Linux:** `ifconfig` or `ip addr`

Make sure your firewall allows inbound connections on the chosen port (default `8765`).
The PC and Android device must be on the **same LAN/Wi-Fi network**.

---

## 2. Build the Android App

**Requirements:** Android Studio (latest stable) or Gradle CLI, JDK 17.

**Option A — Android Studio**
1. Open the `android/` folder as a project.
2. Let Gradle sync.
3. Run on a physical device (API 26+) via USB or Wi-Fi debugging.

**Option B — Command line**
```bash
cd android
./gradlew assembleDebug
# APK output: app/build/outputs/apk/debug/app-debug.apk
adb install app/build/outputs/apk/debug/app-debug.apk
```

> If `gradlew` isn't executable: `chmod +x gradlew`

---

## 3. Connect

1. Start `server.py` on your PC.
2. Open the VirtualCam app on Android.
3. Enter the PC's LAN IP address and port (`8765` by default).
4. Tap **Connect**. The live preview appears, and the status dot turns green with a latency reading once frames arrive.
5. The stream keeps running in the background (persistent notification) once connected.

---

## 4. Virtual Camera Compatibility

| Android version | Behavior |
|---|---|
| **13+ (API 33+)** | Uses the platform's virtual camera / ImageWriter pipeline. Frames are pushed into a `Surface` that can be wired into `CameraManager`-discoverable camera devices on supported OEM builds. |
| **8.0–12 (API 26–32)** | Falls back to a shared `SurfaceTexture`/`Surface` exposed via `VirtualCameraService.getSharedSurface()`. Only apps that explicitly support a custom video source (rather than querying the system camera list) can consume it. |

**Important limitation:** Android does not allow a third-party, non-root app to fully
replace the system camera list system-wide on stock firmware. Full injection into
*any* app's camera picker (WhatsApp, Instagram, Zoom, etc.) without root or an OEM-signed
privileged app is not technically possible. This implementation provides:
- A real-time, low-latency LAN preview inside the app itself.
- A `Surface`/`ImageWriter` pipeline ready to wire into camera APIs on devices/ROMs that
  expose `VirtualDeviceManager` virtual camera registration (API 33+, OEM-dependent).
- A documented extension point (`VirtualCameraService`) where a root/Magisk module or
  OEM-signed system app could complete system-wide registration if your target devices
  support it.

If your use case requires guaranteed system-wide camera replacement on stock,
non-rooted devices across all apps, that requires either root (Magisk virtual camera
module) or an OEM partnership — there is no public, non-root Android API that
guarantees this on all devices today.

---

## 5. Troubleshooting

| Problem | Fix |
|---|---|
| App stuck on "Connecting…" | Confirm PC and phone are on the same network; check firewall on the PC for the chosen port. |
| Stream connects then drops every few seconds | Lower `--fps` or `--quality` on the server — your Wi-Fi may not have enough bandwidth. |
| High latency (>300ms) | Move closer to the router, switch to 5GHz Wi-Fi, or reduce `--width`/`--height`. |
| Black bars look wrong | This is intentional letterboxing — it preserves the PC's aspect ratio instead of stretching. |
| Virtual camera not detected by an app | Confirm the target app supports custom video sources, or check `adb logcat -s VirtualCameraService` for initialization errors. |

**Verify the app's virtual camera service registered correctly:**
```bash
adb logcat -s VirtualCameraService StreamService
```

---

## 6. Permissions Used

| Permission | Why |
|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | LAN WebSocket connection |
| `CAMERA` | Required to register as a camera provider |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Keep streaming while app is backgrounded |
| `WAKE_LOCK` | Prevent CPU sleep during long sessions |
| `POST_NOTIFICATIONS` (API 33+) | Required to show the persistent streaming notification |
