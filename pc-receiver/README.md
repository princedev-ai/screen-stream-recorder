# PC Receiver

Yeh Windows desktop companion Android app se H.264 stream receive karta hai.

## Install

```powershell
cd "C:\Users\hp\Documents\New project\pc-receiver"
python -m pip install -r requirements.txt
python main.py
```

## Use

1. PC aur Android phone ko same WiFi par rakhein, ya PC ko phone hotspot se connect karein.
2. `python main.py` run karein.
3. Android app mein `Find PC receiver` ya `Start recording` dabayein.
4. Stream window mein preview dikhega. Raw `.h264` copy `pc-receiver/recordings` mein save hoti rahegi.

## Firewall

Windows firewall prompt aaye to private network access allow karein.

Ports:

- UDP `45891` discovery
- TCP `45892` video stream

## Notes

- Live preview ke liye PyAV FFmpeg bindings use hoti hain.
- Saving pause karne se sirf PC-side copy rukti hai. Android par MP4 recording continue rahegi.
- Multiple Android devices connect kar sakte hain; device list mein sab dikhte hain.
