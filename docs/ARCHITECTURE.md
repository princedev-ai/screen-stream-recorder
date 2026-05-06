# Architecture

## Android components

```mermaid
flowchart LR
    UI["MainActivity"] --> Permission["MediaProjection consent"]
    Permission --> Service["ScreenRecordService"]
    Service --> Encoder["MediaCodecScreenRecorder"]
    Encoder --> MP4["MediaMuxer MP4 file"]
    Encoder --> Streamer["VideoPacketStreamer"]
    Service --> Discovery["PcDiscoveryManager UDP discovery"]
    Discovery --> Streamer
    Streamer --> PC["PC Receiver TCP 45892"]
    Screen["Screen ON/OFF receiver"] --> Service
```

## PC components

```mermaid
flowchart LR
    UDP["UDP discovery responder 45891"] --> Android["Android app"]
    TCP["TCP H.264 receiver 45892"] --> Save["Raw .h264 save"]
    TCP --> Decoder["PyAV H.264 decoder"]
    Decoder --> UI["PySide live viewer"]
```

## Stream protocol

The Android app sends encoded H.264 packets to the PC receiver over TCP.

Header format, big endian:

| Field | Size |
| --- | --- |
| Magic `SSP1` | 4 bytes |
| Packet type | 1 byte |
| Flags | 1 byte |
| Reserved | 2 bytes |
| PTS microseconds | 8 bytes |
| Payload size | 4 bytes |

Packet types:

- `1`: codec config, SPS/PPS in Annex B format
- `2`: H.264 frame, Annex B format

Flags:

- `1`: key frame

## Design decisions

- MediaCodec is used instead of MediaRecorder so recording and streaming can share one encoded video pipeline.
- MP4 recording uses MediaMuxer on Android.
- Stream fallback is automatic: if PC is unavailable, the streamer drops packets and local MP4 recording continues.
- Discovery uses UDP broadcast because it works in normal WiFi and most phone hotspot networks.

