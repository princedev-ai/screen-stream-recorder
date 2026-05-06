# Roadmap

## Next production steps

1. Add Android 10+ system audio capture with `AudioPlaybackCaptureConfiguration`.
2. Add AAC muxing so audio and video are written into the same MP4 file.
3. Replace the custom TCP stream with WebRTC for NAT traversal and built-in jitter handling.
4. Add QR pairing and per-PC authentication tokens.
5. Add adaptive bitrate based on measured upload speed.
6. Add structured logs and crash recovery state.
7. Add signed release APK and Windows installer pipeline.

## Security hardening

- Pair PC and Android with a one-time code or QR.
- Encrypt stream transport with TLS or WebRTC DTLS-SRTP.
- Show clear recording notification at all times.
- Store files only in app-specific storage unless the user chooses another folder.

