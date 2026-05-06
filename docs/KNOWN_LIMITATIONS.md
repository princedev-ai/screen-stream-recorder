# Known Limitations

## Android 14+ MediaProjection consent

Android 14 changed MediaProjection rules. Apps targeting Android 14+ must ask for user consent before each capture session, and each MediaProjection instance can create only one virtual display. Because of this, fully silent auto-resume after screen-off/screen-on is not possible on Android 14+ without another user consent prompt.

References:

- https://developer.android.com/about/versions/14/behavior-changes-14
- https://developer.android.com/guide/topics/large-screens/media-projection-large-screens

## Audio

The current implementation focuses on video recording and streaming. The UI keeps an audio toggle for the planned path, but system-audio capture still needs a dedicated Android 10+ AudioPlaybackCapture pipeline and AAC muxing.

## PC preview dependencies

The PC app uses PyAV for H.264 decoding and PySide6 for UI. If PyAV cannot install on a machine, the receiver can still be adapted to save raw `.h264`, but live preview will not render.

## RTSP/WebRTC

This starter uses a custom low-latency TCP H.264 packet stream. It is intentionally simpler than WebRTC/RTSP and is easier to debug locally. A production version can add WebRTC signaling on top of the same MediaCodec output.

## Build environment

This machine currently has no local JDK, Android SDK, Gradle, ADB, or Git command available in PATH. Source is present, but APK/debug builds need Android Studio or command-line Android tooling.

