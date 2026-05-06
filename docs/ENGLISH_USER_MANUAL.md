# User Manual - Screen Stream Recorder

This guide explains how to set up and use the Android screen recorder and the Windows PC receiver.

## Android App Setup

1. Install Android Studio with the Android SDK.
2. Open the `android/` folder in Android Studio.
3. Let Gradle sync finish.
4. Run the app on a physical Android phone.
5. Open the app and allow notification permission when requested.
6. For longer recordings, open `Battery optimization setting` from the app and allow the app to run without battery optimization.

## PC Receiver Setup

```powershell
cd "C:\Users\hp\Documents\New project\pc-receiver"
python -m pip install -r requirements.txt
python main.py
```

If Windows Firewall shows a prompt, allow the app on private networks.

## WiFi Mode

1. Connect the phone and PC to the same WiFi network.
2. Start the PC receiver.
3. In the Android app, select `WiFi` or `Auto` mode.
4. Tap `Find PC receiver`.
5. Tap `Start recording`.
6. Allow the Android screen capture permission prompt.

The PC receiver should start showing the live stream after the next video key frame arrives.

## Hotspot Mode

1. Turn on the phone hotspot.
2. Connect the PC to the phone hotspot.
3. Start the PC receiver.
4. In the Android app, select `Hotspot` or `Auto` mode.
5. Tap `Start recording`.
6. Allow the Android screen capture permission prompt.

Use hotspot mode when both devices are not on the same WiFi network.

## Recording Files

Android MP4 recordings are saved in the app-specific Movies folder:

`Android/data/com.example.screenstreamer/files/Movies/ScreenStream`

The PC receiver saves raw H.264 stream copies here:

`pc-receiver/recordings`

## Screen On/Off Behavior

- When the screen turns off, the current recording stops gracefully.
- On Android 13 and older, the app tries to reuse the same projection session and resume recording about 3 seconds after the screen turns on.
- On Android 14 and newer, Android requires fresh user approval for each new screen capture session. When this happens, open the app and approve screen capture again.

## Troubleshooting

- PC receiver is not found: allow the app through Windows Firewall.
- Streaming does not start: keep both devices on the same network or connect the PC to the phone hotspot.
- WiFi discovery fails: make sure router AP isolation is disabled.
- Hotspot mode fails: make sure the PC is connected to the phone hotspot, not another WiFi network.
- Preview is black: wait a few seconds; the decoder needs the next key frame.
- Android recording does not start: approve the screen capture permission again.
- Long recordings stop unexpectedly: disable battery optimization for the app.
