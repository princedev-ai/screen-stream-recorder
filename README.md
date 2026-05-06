# Android Screen Recorder and PC Live Viewer

Yeh repository ek native Android screen recorder + Windows PC companion receiver ka starter project hai.

## What is included

- Android Kotlin app in `android/`
- Foreground service based screen capture
- H.264 MediaCodec video pipeline
- MP4 recording output in app-specific Movies folder
- UDP LAN discovery for the PC receiver
- Low-latency H.264 packet stream to the PC app
- Python/PySide PC viewer in `pc-receiver/`
- Hindi/Hinglish setup and troubleshooting docs in `docs/`

## Important Android privacy limitation

Modern Android versions intentionally do not allow silent screen capture forever. Android 14+ requires a fresh user consent for every new MediaProjection session. That means automatic recording after screen-off/screen-on can only resume without a prompt on older Android versions where the same projection can still be reused. On Android 14+, the service keeps monitoring and asks the user to re-authorize.

See:
- https://developer.android.com/guide/topics/large-screens/media-projection-large-screens
- https://developer.android.com/develop/background-work/services/fgs/service-types

## Build quick start

### Android

1. Install Android Studio with Android SDK.
2. Open the `android/` folder.
3. Let Gradle sync.
4. Run the `app` configuration on a physical Android device.

This workspace currently does not include a local JDK, Android SDK, or Gradle command, so APK build cannot be completed from this machine until those tools are installed.

### PC receiver

```powershell
cd "C:\Users\hp\Documents\New project\pc-receiver"
python -m pip install -r requirements.txt
python main.py
```

Start the PC receiver first, then start recording from the Android app.
