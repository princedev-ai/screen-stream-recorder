param(
    [string]$Name = "ScreenStreamReceiver"
)

python -m pip install -r requirements.txt
python -m PyInstaller `
    --noconfirm `
    --clean `
    --onefile `
    --windowed `
    --name $Name `
    --icon receiver.ico `
    --add-data "receiver.ico;." `
    --add-data "receiver_logo.png;." `
    --hidden-import av `
    --hidden-import numpy `
    --hidden-import sounddevice `
    --hidden-import websocket `
    main.py
