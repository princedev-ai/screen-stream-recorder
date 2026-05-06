# User Manual - Screen Stream Recorder

Yeh guide Hinglish mein hai taaki setup seedha rahe.

## Android app setup

1. Android Studio install karein.
2. `android/` folder open karein.
3. App ko physical phone par run karein.
4. App open karke notification permission allow karein.
5. Agar long recording chahiye to `Battery optimization setting` open karke app ko optimize na karne dein.

## PC receiver setup

```powershell
cd "C:\Users\hp\Documents\New project\pc-receiver"
python -m pip install -r requirements.txt
python main.py
```

Windows firewall prompt aaye to private network par allow karein.

## WiFi mode

1. Phone aur PC dono same WiFi par connect karein.
2. PC receiver start karein.
3. Android app mein mode `WiFi` ya `Auto` rakhein.
4. `Find PC receiver` dabayein.
5. `Start recording` dabayein aur Android screen capture permission allow karein.

## Hotspot mode

1. Phone hotspot ON karein.
2. PC ko phone hotspot se connect karein.
3. PC receiver start karein.
4. Android app mein `Hotspot` ya `Auto` mode select karein.
5. Recording start karein.

## Recording files

Android par MP4 files app-specific Movies folder mein save hoti hain:

`Android/data/com.example.screenstreamer/files/Movies/ScreenStream`

PC par raw H.264 stream copy yahan save hoti hai:

`pc-receiver/recordings`

## Screen on/off behavior

- Screen off par current recording gracefully stop hoti hai.
- Android 13 aur older devices par app same projection session reuse karke screen on ke 3 seconds baad resume try karta hai.
- Android 14+ par Android security model fresh user permission maangta hai. Isliye app screen on par status dikhata hai: app open karke permission deni hogi.

## Troubleshooting

- PC nahi mil raha: firewall allow karein.
- Dono devices same network/subnet par rakhein.
- Router AP isolation off hona chahiye.
- Hotspot mode mein PC ko phone hotspot se connect karein, dusre WiFi se nahi.
- Preview black hai: 2 seconds wait karein; decoder ko next key frame chahiye hota hai.
- Android recording start nahi ho rahi: screen capture permission dobara allow karein.
