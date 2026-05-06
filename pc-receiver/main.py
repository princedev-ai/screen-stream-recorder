import datetime as dt
import json
import os
import queue
import socket
import struct
import sys
import threading
import time
from fractions import Fraction
from pathlib import Path

try:
    import av
except Exception:
    av = None

try:
    import numpy as np
except Exception:
    np = None

try:
    import sounddevice as sd
except Exception:
    sd = None

try:
    import websocket
except Exception:
    websocket = None

try:
    from PySide6.QtCore import Qt, QThread, Signal
    from PySide6.QtGui import QIcon, QImage, QPixmap
    from PySide6.QtWidgets import (
        QApplication,
        QFileDialog,
        QFrame,
        QHBoxLayout,
        QLabel,
        QListWidget,
        QMainWindow,
        QPushButton,
        QSlider,
        QVBoxLayout,
        QWidget,
    )
except Exception as exc:
    print("PySide6 is required. Run: python -m pip install -r requirements.txt")
    print(exc)
    raise

try:
    from zeroconf import ServiceInfo, Zeroconf
except Exception:
    ServiceInfo = None
    Zeroconf = None


DISCOVERY_PORT = 45891
STREAM_PORT = 45892
AUDIO_PORT = 8081
CAMERA_PORT = 8082
CAMERA_CONTROL_PORT = 8083
CAMERA_STALL_TIMEOUT = 5.0
CAMERA_UI_EMIT_INTERVAL = 1.0 / 12.0
MAGIC = b"SSP1"
HEADER_SIZE = 20
TYPE_CONFIG = 1
TYPE_FRAME = 2
FLAG_KEY_FRAME = 1
SERVICE_TYPE = "_screenstream._tcp.local."
CONFIG_PATH = Path.home() / ".screen_stream_receiver.json"


def resource_path(name: str) -> Path:
    base_path = Path(getattr(sys, "_MEIPASS", Path(__file__).parent))
    return base_path / name


class DiscoveryResponder(threading.Thread):
    def __init__(self, port: int, stop_event: threading.Event):
        super().__init__(daemon=True)
        self.port = port
        self.stop_event = stop_event
        self.hostname = socket.gethostname()
        self.zeroconf = None
        self.service_info = None

    def run(self) -> None:
        self._register_zeroconf()
        try:
            self._serve_udp()
        finally:
            self._unregister_zeroconf()

    def _serve_udp(self) -> None:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        sock.settimeout(0.5)
        sock.bind(("", DISCOVERY_PORT))
        response = f"SSP_PC|{self.hostname}|{self.port}|{self._local_ip()}".encode("utf-8")

        while not self.stop_event.is_set():
            try:
                data, address = sock.recvfrom(1024)
            except socket.timeout:
                continue
            except OSError:
                break

            if data.startswith(b"SSP_DISCOVER"):
                sock.sendto(response, address)

        sock.close()

    def _register_zeroconf(self) -> None:
        if Zeroconf is None or ServiceInfo is None:
            return
        try:
            ip_bytes = socket.inet_aton(self._local_ip())
            self.zeroconf = Zeroconf()
            self.service_info = ServiceInfo(
                SERVICE_TYPE,
                f"{self.hostname}.{SERVICE_TYPE}",
                addresses=[ip_bytes],
                port=self.port,
                properties={b"version": b"1"},
                server=f"{self.hostname}.local.",
            )
            self.zeroconf.register_service(self.service_info)
        except Exception:
            self.zeroconf = None
            self.service_info = None

    def _unregister_zeroconf(self) -> None:
        if self.zeroconf and self.service_info:
            try:
                self.zeroconf.unregister_service(self.service_info)
                self.zeroconf.close()
            except Exception:
                pass

    def _local_ip(self) -> str:
        try:
            probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            probe.connect(("8.8.8.8", 80))
            ip = probe.getsockname()[0]
            probe.close()
            return ip
        except Exception:
            return "127.0.0.1"


class StreamServer(QThread):
    status_changed = Signal(str)
    audio_status_changed = Signal(str)
    camera_status_changed = Signal(str)
    device_changed = Signal(str)
    frame_received = Signal(str, QImage)
    camera_frame_received = Signal(QImage)

    def __init__(self, save_dir: Path):
        super().__init__()
        self.save_dir = save_dir
        self.stop_event = threading.Event()
        self.saving_enabled = True
        self.discovery = DiscoveryResponder(STREAM_PORT, self.stop_event)
        self.audio_volume = 0.8
        self.android_host = None
        self.audio_client = None
        self.camera_client = None

    def run(self) -> None:
        self.discovery.start()
        server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("", STREAM_PORT))
        server.listen(8)
        server.settimeout(0.5)
        self.status_changed.emit(f"Listening on port {STREAM_PORT}")

        while not self.stop_event.is_set():
            try:
                client, address = server.accept()
            except socket.timeout:
                continue
            except OSError:
                break
            worker = threading.Thread(
                target=self._handle_client,
                args=(client, address),
                daemon=True,
            )
            worker.start()

        try:
            server.close()
        except Exception:
            pass

    def stop(self) -> None:
        self.stop_event.set()
        if self.audio_client:
            self.audio_client.stop()
        if self.camera_client:
            self.camera_client.stop()

    def start_side_streams(self, host: str) -> None:
        if (
            self.android_host == host
            and self.audio_client
            and self.camera_client
            and self.audio_client.is_alive()
            and self.camera_client.is_alive()
        ):
            return
        self.android_host = host
        if self.audio_client:
            self.audio_client.stop()
        if self.camera_client:
            self.camera_client.stop()
        self.audio_client = AudioClientThread(host, self.stop_event, self.audio_status_changed, lambda: self.audio_volume)
        self.camera_client = CameraClientThread(
            host,
            self.stop_event,
            self.camera_status_changed,
            self.camera_frame_received,
            lambda: self.save_dir,
            lambda: self.saving_enabled,
            self._save_mp4_copy,
        )
        self.audio_client.start()
        self.camera_client.start()

    def send_camera_command(self, command: str) -> None:
        if not self.android_host:
            self.camera_status_changed.emit("Camera control: no Android device connected")
            return
        if websocket is None:
            self.camera_status_changed.emit("Camera control: websocket-client not installed")
            return
        try:
            ws = websocket.create_connection(f"ws://{self.android_host}:{CAMERA_CONTROL_PORT}", timeout=2)
            ws.send(command)
            response = ws.recv()
            ws.close()
            self.camera_status_changed.emit(f"Camera control: {response}")
        except Exception as exc:
            self.camera_status_changed.emit(f"Camera control failed: {exc}")

    def _handle_client(self, client: socket.socket, address) -> None:
        device_name = f"{address[0]}:{address[1]}"
        self.device_changed.emit(device_name)
        self.status_changed.emit(f"Connected: {device_name}")
        self.start_side_streams(address[0])
        timestamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
        self.save_dir.mkdir(parents=True, exist_ok=True)
        h264_path = self.save_dir / f"android_stream_{timestamp}.h264"
        decoder = H264Decoder()
        bytes_received = 0
        frames_received = 0
        last_status_at = time.monotonic()
        first_packet = True

        with client:
            client.settimeout(2.0)
            with h264_path.open("ab") as output:
                while not self.stop_event.is_set():
                    header = self._read_exact(client, HEADER_SIZE)
                    if not header:
                        break
                    if first_packet:
                        first_packet = False
                        client.settimeout(None)
                    magic, packet_type, flags, _reserved, pts_us, payload_size = struct.unpack(
                        ">4sBBHQI",
                        header,
                    )
                    if magic != MAGIC or payload_size < 0 or payload_size > 8_000_000:
                        break
                    payload = self._read_exact(client, payload_size)
                    if payload is None:
                        break

                    if self.saving_enabled and packet_type in (TYPE_CONFIG, TYPE_FRAME):
                        output.write(payload)
                        output.flush()
                    bytes_received += len(payload)

                    if packet_type in (TYPE_CONFIG, TYPE_FRAME):
                        image = decoder.decode(payload)
                        if image is not None:
                            frames_received += 1
                            self.frame_received.emit(device_name, image)

                    now = time.monotonic()
                    if now - last_status_at >= 1.0:
                        mb = bytes_received / (1024 * 1024)
                        self.status_changed.emit(
                            f"Receiving: {device_name} | {mb:.1f} MB | frames {frames_received}"
                        )
                        last_status_at = now

        self.status_changed.emit(f"Disconnected: {device_name}")
        if bytes_received == 0:
            h264_path.unlink(missing_ok=True)
            return
        if self.saving_enabled:
            self._save_mp4_copy(h264_path)

    def _read_exact(self, client: socket.socket, size: int):
        chunks = []
        remaining = size
        while remaining:
            try:
                chunk = client.recv(remaining)
            except socket.timeout:
                return None
            if not chunk:
                return None
            chunks.append(chunk)
            remaining -= len(chunk)
        return b"".join(chunks)

    def _save_mp4_copy(self, h264_path: Path) -> None:
        if av is None or not h264_path.exists() or h264_path.stat().st_size == 0:
            return

        mp4_path = h264_path.with_suffix(".mp4")
        temp_path = h264_path.with_suffix(".tmp.mp4")
        frame_count = 0
        input_container = None
        output_container = None
        output_stream = None

        try:
            self.status_changed.emit(f"Saving MP4: {mp4_path.name}")
            input_container = av.open(
                str(h264_path),
                format="h264",
                options={"fflags": "discardcorrupt", "err_detect": "ignore_err"},
            )
            output_container = av.open(str(temp_path), mode="w", format="mp4")

            for frame in input_container.decode(video=0):
                if output_stream is None:
                    output_stream = output_container.add_stream("libx264", rate=30)
                    output_stream.width = frame.width
                    output_stream.height = frame.height
                    output_stream.pix_fmt = "yuv420p"
                    output_stream.bit_rate = 3_000_000
                    output_stream.options = {"preset": "veryfast", "crf": "23"}
                    output_stream.time_base = Fraction(1, 30)

                frame.pts = frame_count
                frame.time_base = Fraction(1, 30)
                for packet in output_stream.encode(frame):
                    output_container.mux(packet)
                frame_count += 1

                if frame_count % 120 == 0:
                    self.status_changed.emit(f"Saving MP4: {frame_count} frames")

            if output_stream is not None:
                for packet in output_stream.encode(None):
                    output_container.mux(packet)

            output_container.close()
            input_container.close()
            output_container = None
            input_container = None

            if frame_count == 0:
                temp_path.unlink(missing_ok=True)
                self.status_changed.emit("MP4 not saved: no decodable frames")
                return

            temp_path.replace(mp4_path)
            size_mb = mp4_path.stat().st_size / (1024 * 1024)
            self.status_changed.emit(f"Saved MP4: {mp4_path.name} ({size_mb:.1f} MB)")
        except Exception as exc:
            saved_partial = self._finalize_partial_mp4(
                temp_path=temp_path,
                mp4_path=mp4_path,
                output_container=output_container,
                input_container=input_container,
                output_stream=output_stream,
                frame_count=frame_count,
            )
            output_container = None
            input_container = None
            if saved_partial:
                self.status_changed.emit(
                    f"Saved partial MP4: {mp4_path.name} ({frame_count} frames; damaged data skipped)"
                )
            else:
                self._safe_unlink(temp_path)
                self.status_changed.emit(f"MP4 save failed: {exc}")
        finally:
            try:
                if output_container is not None:
                    output_container.close()
            except Exception:
                pass
            try:
                if input_container is not None:
                    input_container.close()
            except Exception:
                pass

    def _finalize_partial_mp4(
        self,
        temp_path: Path,
        mp4_path: Path,
        output_container,
        input_container,
        output_stream,
        frame_count: int,
    ) -> bool:
        try:
            if output_stream is not None and output_container is not None:
                for packet in output_stream.encode(None):
                    output_container.mux(packet)
        except Exception:
            pass
        try:
            if output_container is not None:
                output_container.close()
        except Exception:
            pass
        try:
            if input_container is not None:
                input_container.close()
        except Exception:
            pass
        if frame_count <= 0 or not temp_path.exists():
            return False
        try:
            temp_path.replace(mp4_path)
            return mp4_path.exists() and mp4_path.stat().st_size > 0
        except Exception:
            return False

    def _safe_unlink(self, path: Path) -> None:
        try:
            path.unlink(missing_ok=True)
        except PermissionError:
            time.sleep(0.2)
            try:
                path.unlink(missing_ok=True)
            except Exception:
                pass
        except Exception:
            pass


class H264Decoder:
    def __init__(self):
        self.codec = av.CodecContext.create("h264", "r") if av else None

    def decode(self, payload: bytes):
        if self.codec is None:
            return None
        try:
            frames = []
            for packet in self.codec.parse(payload):
                frames.extend(self.codec.decode(packet))
            if not frames:
                return None
            frame = frames[-1]
            rgb = frame.to_ndarray(format="rgb24")
            height, width, channels = rgb.shape
            bytes_per_line = channels * width
            return QImage(rgb.data, width, height, bytes_per_line, QImage.Format_RGB888).copy()
        except Exception:
            return None


class AudioClientThread(threading.Thread):
    def __init__(self, host: str, stop_event: threading.Event, status_signal, volume_getter):
        super().__init__(daemon=True)
        self.host = host
        self.stop_event = stop_event
        self.local_stop = threading.Event()
        self.status_signal = status_signal
        self.volume_getter = volume_getter
        self.codec = av.CodecContext.create("aac", "r") if av else None
        self.output_stream = None
        self.frames = 0

    def stop(self):
        self.local_stop.set()
        self._close_output()

    def run(self):
        if websocket is None:
            self.status_signal.emit("Audio: websocket-client missing")
            return
        if av is None or np is None:
            self.status_signal.emit("Audio: PyAV/numpy missing")
            return
        if sd is None:
            self.status_signal.emit("Audio: sounddevice missing")
            return

        url = f"ws://{self.host}:{AUDIO_PORT}"
        while not self.stop_event.is_set() and not self.local_stop.is_set():
            try:
                self.status_signal.emit(f"Audio: connecting {url}")
                ws = websocket.WebSocketApp(
                    url,
                    on_data=self._on_data,
                    on_open=lambda _ws: self.status_signal.emit("Audio: connected"),
                    on_close=self._on_close,
                    on_error=lambda _ws, err: self.status_signal.emit(f"Audio error: {err}"),
                )
                ws.run_forever(ping_interval=10, ping_timeout=5)
            except Exception as exc:
                self.status_signal.emit(f"Audio reconnect: {exc}")
            if not self.stop_event.is_set() and not self.local_stop.is_set():
                time.sleep(2)
        self._close_output()

    def _on_close(self, _ws, _code, _msg):
        self._close_output()
        self.status_signal.emit("Audio: disconnected")

    def _on_data(self, _ws, data, opcode, _fin):
        if opcode != websocket.ABNF.OPCODE_BINARY or self.codec is None:
            return
        try:
            for packet in self.codec.parse(data):
                for frame in self.codec.decode(packet):
                    self._play_frame(frame)
        except Exception as exc:
            self.status_signal.emit(f"Audio decode error: {exc}")

    def _play_frame(self, frame):
        samples = frame.to_ndarray()
        samples = np.asarray(samples)
        if samples.ndim == 2:
            if samples.shape[0] <= 8:
                samples = samples.T
        if samples.ndim == 1:
            samples = np.column_stack([samples, samples])
        if samples.dtype.kind in ("i", "u"):
            samples = samples.astype(np.float32) / max(1, np.iinfo(samples.dtype).max)
        else:
            samples = samples.astype(np.float32)
        samples = np.clip(samples * float(self.volume_getter()), -1.0, 1.0).astype(np.float32)
        samples = np.ascontiguousarray(samples)
        if self.output_stream is None:
            self.output_stream = sd.OutputStream(
                samplerate=frame.sample_rate,
                channels=samples.shape[1],
                dtype="float32",
                blocksize=0,
            )
            self.output_stream.start()
        self.output_stream.write(samples)
        self.frames += 1
        if self.frames % 100 == 0:
            self.status_signal.emit(f"Audio: playing ({self.frames} frames)")

    def _close_output(self):
        try:
            if self.output_stream:
                self.output_stream.stop()
                self.output_stream.close()
        except Exception:
            pass
        self.output_stream = None


class CameraClientThread(threading.Thread):
    def __init__(
        self,
        host: str,
        stop_event: threading.Event,
        status_signal,
        frame_signal,
        save_dir_getter,
        saving_getter,
        save_callback,
    ):
        super().__init__(daemon=True)
        self.host = host
        self.stop_event = stop_event
        self.local_stop = threading.Event()
        self.status_signal = status_signal
        self.frame_signal = frame_signal
        self.save_dir_getter = save_dir_getter
        self.saving_getter = saving_getter
        self.save_callback = save_callback
        self.decoder = H264Decoder()
        self.frames = 0
        self.last_frame_at = time.monotonic()
        self.last_emit_at = 0.0
        self.h264_file = None
        self.h264_path = None
        self.bytes_written = 0

    def stop(self):
        self.local_stop.set()
        self._finish_recording_file()

    def run(self):
        if websocket is None:
            self.status_signal.emit("Camera: websocket-client missing")
            return
        url = f"ws://{self.host}:{CAMERA_PORT}"
        while not self.stop_event.is_set() and not self.local_stop.is_set():
            try:
                self.decoder = H264Decoder()
                self.last_frame_at = time.monotonic()
                self.status_signal.emit(f"Camera: connecting {url}")
                watchdog_stop = threading.Event()
                ws = websocket.WebSocketApp(
                    url,
                    on_data=self._on_data,
                    on_open=self._on_open,
                    on_close=self._on_close,
                    on_error=lambda _ws, err: self.status_signal.emit(f"Camera error: {err}"),
                )
                watchdog = threading.Thread(
                    target=self._watch_camera,
                    args=(ws, watchdog_stop),
                    daemon=True,
                )
                watchdog.start()
                ws.run_forever(ping_interval=10, ping_timeout=5)
                watchdog_stop.set()
                self._finish_recording_file()
            except Exception as exc:
                self._finish_recording_file()
                self.status_signal.emit(f"Camera reconnect: {exc}")
            if not self.stop_event.is_set() and not self.local_stop.is_set():
                time.sleep(2)

    def _on_open(self, _ws):
        self.status_signal.emit("Camera: connected")
        self._start_recording_file()

    def _on_close(self, _ws, _code, _msg):
        self._finish_recording_file()
        self.status_signal.emit("Camera: disconnected")

    def _watch_camera(self, ws, watchdog_stop: threading.Event):
        while not self.stop_event.is_set() and not self.local_stop.is_set() and not watchdog_stop.is_set():
            if time.monotonic() - self.last_frame_at > CAMERA_STALL_TIMEOUT:
                self.status_signal.emit("Camera: stalled, reconnecting")
                try:
                    ws.close()
                except Exception:
                    pass
                return
            time.sleep(1)

    def _on_data(self, _ws, data, opcode, _fin):
        if opcode != websocket.ABNF.OPCODE_BINARY:
            return
        self._write_camera_packet(data)
        image = self.decoder.decode(data)
        if image is not None:
            self.frames += 1
            self.last_frame_at = time.monotonic()
            now = time.monotonic()
            if now - self.last_emit_at >= CAMERA_UI_EMIT_INTERVAL:
                self.last_emit_at = now
                self.frame_signal.emit(image)
            if self.frames % 30 == 0:
                self.status_signal.emit(f"Camera: {self.frames} frames")

    def _start_recording_file(self):
        if self.h264_file is not None or not self.saving_getter():
            return
        try:
            save_dir = Path(self.save_dir_getter())
            save_dir.mkdir(parents=True, exist_ok=True)
            timestamp = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
            self.h264_path = save_dir / f"camera_stream_{timestamp}.h264"
            self.h264_file = self.h264_path.open("ab")
            self.bytes_written = 0
        except Exception as exc:
            self.h264_file = None
            self.h264_path = None
            self.status_signal.emit(f"Camera save disabled: {exc}")

    def _write_camera_packet(self, data: bytes):
        if not self.saving_getter():
            return
        if self.h264_file is None:
            self._start_recording_file()
        if self.h264_file is None:
            return
        try:
            self.h264_file.write(data)
            self.h264_file.flush()
            self.bytes_written += len(data)
        except Exception as exc:
            self.status_signal.emit(f"Camera save error: {exc}")
            self._finish_recording_file()

    def _finish_recording_file(self):
        path = self.h264_path
        bytes_written = self.bytes_written
        try:
            if self.h264_file is not None:
                self.h264_file.close()
        except Exception:
            pass
        self.h264_file = None
        self.h264_path = None
        self.bytes_written = 0
        if path is None:
            return
        if bytes_written <= 0:
            try:
                path.unlink(missing_ok=True)
            except Exception:
                pass
            return
        threading.Thread(target=self.save_callback, args=(path,), daemon=True).start()


class ClickableLabel(QLabel):
    clicked = Signal()

    def mousePressEvent(self, event):
        self.clicked.emit()
        super().mousePressEvent(event)


class CameraFullscreenWindow(QMainWindow):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Camera Fullscreen")
        self.image = None
        self.label = ClickableLabel("Waiting for camera")
        self.label.setAlignment(Qt.AlignCenter)
        self.label.setStyleSheet("background:#020617;color:#e5e7eb;font-size:18px;")
        self.label.clicked.connect(self.close)
        self.setCentralWidget(self.label)

    def update_frame(self, image: QImage):
        self.image = image
        self._render()

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self._render()

    def mousePressEvent(self, event):
        self.close()
        super().mousePressEvent(event)

    def keyPressEvent(self, event):
        if event.key() == Qt.Key_Escape:
            self.close()
            return
        super().keyPressEvent(event)

    def _render(self):
        if self.image is None or self.image.isNull():
            return
        pixmap = QPixmap.fromImage(self.image)
        self.label.setPixmap(
            pixmap.scaled(
                self.label.size(),
                Qt.KeepAspectRatio,
                Qt.SmoothTransformation,
            )
        )


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Screen Stream Receiver")
        self.resize(1240, 760)
        icon_path = resource_path("receiver.ico")
        if icon_path.exists():
            self.setWindowIcon(QIcon(str(icon_path)))
        self.save_dir = self.load_save_dir()
        self.server = StreamServer(self.save_dir)
        self.server.status_changed.connect(self.set_status)
        self.server.audio_status_changed.connect(self.set_audio_status)
        self.server.camera_status_changed.connect(self.set_camera_status)
        self.server.device_changed.connect(self.add_device)
        self.server.frame_received.connect(self.show_frame)
        self.server.camera_frame_received.connect(self.show_camera_frame)
        self.server.start()
        self.camera_front = False
        self.last_camera_image = None
        self.camera_fullscreen = None
        self._build_ui()

    def closeEvent(self, event):
        self.server.stop()
        self.server.wait(1500)
        super().closeEvent(event)

    def _build_ui(self):
        root = QWidget()
        layout = QHBoxLayout(root)
        layout.setContentsMargins(14, 14, 14, 14)
        layout.setSpacing(14)

        side_panel = QFrame()
        side_panel.setObjectName("Sidebar")
        side_panel.setMinimumWidth(320)
        side_panel.setMaximumWidth(380)
        side = QVBoxLayout(side_panel)
        side.setContentsMargins(18, 18, 18, 18)
        side.setSpacing(10)

        header_row = QHBoxLayout()
        header_row.setContentsMargins(0, 0, 0, 0)
        header_row.setSpacing(10)

        logo = QLabel()
        logo.setObjectName("Logo")
        logo_pixmap = QPixmap(str(resource_path("receiver_logo.png")))
        if not logo_pixmap.isNull():
            logo.setPixmap(logo_pixmap.scaled(52, 52, Qt.KeepAspectRatio, Qt.SmoothTransformation))
        logo.setFixedSize(56, 56)

        title_box = QVBoxLayout()
        title_box.setContentsMargins(0, 0, 0, 0)
        title_box.setSpacing(0)
        title = QLabel("Screen Stream")
        title.setObjectName("AppTitle")
        subtitle = QLabel("Receiver for Android screen, audio, and camera")
        subtitle.setObjectName("SubTitle")
        subtitle.setWordWrap(True)
        title_box.addWidget(title)
        title_box.addWidget(subtitle)
        header_row.addWidget(logo)
        header_row.addLayout(title_box, 1)

        self.status = self._chip("Starting...", "BlueChip")
        self.audio_status = self._chip("Audio: waiting", "GreenChip")
        self.camera_status = self._chip("Camera: waiting", "PurpleChip")
        self.devices = QListWidget()
        self.devices.setObjectName("DeviceList")
        self.save_label = QLabel(f"Saving to: {self.save_dir}")
        self.save_label.setObjectName("PathLabel")
        self.save_label.setWordWrap(True)

        choose_dir = QPushButton("Save folder")
        choose_dir.setObjectName("SecondaryButton")
        choose_dir.clicked.connect(self.choose_save_dir)
        open_dir = QPushButton("Open recordings")
        open_dir.setObjectName("SecondaryButton")
        open_dir.clicked.connect(self.open_save_dir)
        self.save_button = QPushButton("Pause saving")
        self.save_button.setObjectName("PrimaryButton")
        self.save_button.clicked.connect(self.toggle_saving)
        self.volume_slider = QSlider(Qt.Horizontal)
        self.volume_slider.setRange(0, 100)
        self.volume_slider.setValue(int(self.server.audio_volume * 100))
        self.volume_slider.valueChanged.connect(self.change_volume)
        switch_camera = QPushButton("Switch camera")
        switch_camera.setObjectName("SecondaryButton")
        switch_camera.clicked.connect(self.switch_camera)
        self.zoom_slider = QSlider(Qt.Horizontal)
        self.zoom_slider.setRange(10, 40)
        self.zoom_slider.setValue(10)
        self.zoom_slider.valueChanged.connect(self.change_zoom)
        flash_button = QPushButton("Flash")
        flash_button.setObjectName("WarningButton")
        flash_button.clicked.connect(lambda: self.server.send_camera_command("FLASH_TOGGLE"))

        side.addLayout(header_row)
        side.addSpacing(4)
        side.addWidget(self._section_title("Devices"))
        side.addWidget(self.devices)
        side.addWidget(self.audio_status)
        side.addWidget(self.camera_status)
        side.addWidget(self._section_title("Volume"))
        side.addWidget(self.volume_slider)
        side.addWidget(self._section_title("Camera controls"))
        side.addWidget(switch_camera)
        side.addWidget(self._caption("Camera zoom"))
        side.addWidget(self.zoom_slider)
        side.addWidget(flash_button)
        side.addWidget(self._section_title("Recordings"))
        side.addWidget(self.save_label)
        side.addWidget(choose_dir)
        side.addWidget(open_dir)
        side.addWidget(self.save_button)
        side.addStretch(1)

        self.video = QLabel("Waiting for Android stream")
        self.video.setAlignment(Qt.AlignCenter)
        self.video.setObjectName("VideoSurface")
        self.video.setMinimumSize(720, 480)
        self.camera_pip = ClickableLabel("Camera PiP")
        self.camera_pip.setAlignment(Qt.AlignCenter)
        self.camera_pip.setObjectName("CameraPip")
        self.camera_pip.setMinimumSize(260, 160)
        self.camera_pip.setMaximumSize(420, 260)
        self.camera_pip.setToolTip("Click to view camera fullscreen")
        self.camera_pip.clicked.connect(self.open_camera_fullscreen)

        layout.addWidget(side_panel)
        video_column = QVBoxLayout()
        video_column.setContentsMargins(0, 0, 0, 0)
        video_column.setSpacing(8)
        video_column.addWidget(self.video, 1)
        pip_row = QHBoxLayout()
        pip_row.addStretch(1)
        pip_row.addWidget(self.camera_pip)
        video_column.addLayout(pip_row)
        layout.addLayout(video_column, 4)
        self.setCentralWidget(root)
        self._apply_styles()

    def _chip(self, text: str, style_name: str) -> QLabel:
        label = QLabel(text)
        label.setObjectName(style_name)
        label.setWordWrap(True)
        return label

    def _section_title(self, text: str) -> QLabel:
        label = QLabel(text)
        label.setObjectName("SectionTitle")
        return label

    def _caption(self, text: str) -> QLabel:
        label = QLabel(text)
        label.setObjectName("Caption")
        return label

    def _apply_styles(self):
        self.setStyleSheet(
            """
            QWidget {
                font-family: Segoe UI, Arial, sans-serif;
                font-size: 10.5pt;
                color: #0f172a;
                background: #eef4ff;
            }
            QFrame#Sidebar {
                background: #ffffff;
                border: 1px solid #dbeafe;
                border-radius: 18px;
            }
            QLabel#AppTitle {
                color: #1d4ed8;
                font-size: 22pt;
                font-weight: 800;
                background: transparent;
            }
            QLabel#SubTitle {
                color: #64748b;
                background: transparent;
            }
            QLabel#Logo {
                background: transparent;
            }
            QLabel#SectionTitle {
                color: #0f172a;
                font-weight: 700;
                padding-top: 8px;
                background: transparent;
            }
            QLabel#Caption, QLabel#PathLabel {
                color: #475569;
                background: transparent;
            }
            QLabel#BlueChip, QLabel#GreenChip, QLabel#PurpleChip {
                border-radius: 10px;
                padding: 8px 10px;
                font-weight: 600;
            }
            QLabel#BlueChip {
                background: #dbeafe;
                color: #1e40af;
            }
            QLabel#GreenChip {
                background: #dcfce7;
                color: #166534;
            }
            QLabel#PurpleChip {
                background: #ede9fe;
                color: #5b21b6;
            }
            QListWidget#DeviceList {
                background: #f8fafc;
                border: 1px solid #dbeafe;
                border-radius: 10px;
                padding: 6px;
                min-height: 150px;
            }
            QPushButton {
                border: 0;
                border-radius: 10px;
                padding: 9px 12px;
                font-weight: 650;
            }
            QPushButton#PrimaryButton {
                background: #2563eb;
                color: white;
            }
            QPushButton#SecondaryButton {
                background: #e0f2fe;
                color: #075985;
            }
            QPushButton#WarningButton {
                background: #fef3c7;
                color: #92400e;
            }
            QPushButton:hover {
                background: #38bdf8;
                color: #082f49;
            }
            QSlider::groove:horizontal {
                height: 7px;
                background: #cbd5e1;
                border-radius: 4px;
            }
            QSlider::handle:horizontal {
                width: 18px;
                margin: -6px 0;
                border-radius: 9px;
                background: #2563eb;
            }
            QLabel#VideoSurface {
                background: #0f172a;
                color: #e2e8f0;
                border-radius: 18px;
                font-size: 18px;
                font-weight: 600;
            }
            QLabel#CameraPip {
                background: #111827;
                color: #e5e7eb;
                border: 3px solid #60a5fa;
                border-radius: 14px;
                font-size: 13px;
                font-weight: 700;
            }
            """
        )

    def choose_save_dir(self):
        chosen = QFileDialog.getExistingDirectory(self, "Choose recording folder", str(self.save_dir))
        if not chosen:
            return
        self.save_dir = Path(chosen)
        self.server.save_dir = self.save_dir
        self.save_config()
        self.save_label.setText(f"Saving to: {self.save_dir}")

    def toggle_saving(self):
        self.server.saving_enabled = not self.server.saving_enabled
        self.save_button.setText("Pause saving" if self.server.saving_enabled else "Resume saving")

    def open_save_dir(self):
        self.save_dir.mkdir(parents=True, exist_ok=True)
        os.startfile(self.save_dir)

    def load_save_dir(self) -> Path:
        try:
            data = json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
            configured = Path(data.get("save_dir", ""))
            if str(configured):
                return configured
        except Exception:
            pass
        return Path.cwd() / "recordings"

    def save_config(self):
        try:
            CONFIG_PATH.write_text(
                json.dumps({"save_dir": str(self.save_dir)}, indent=2),
                encoding="utf-8",
            )
        except Exception:
            pass

    def set_status(self, message: str):
        self.status.setText(message)

    def set_audio_status(self, message: str):
        self.audio_status.setText(message)

    def set_camera_status(self, message: str):
        self.camera_status.setText(message)

    def add_device(self, device: str):
        matches = self.devices.findItems(device, Qt.MatchExactly)
        if not matches:
            self.devices.addItem(device)

    def show_frame(self, device: str, image: QImage):
        pixmap = QPixmap.fromImage(image)
        self.video.setPixmap(
            pixmap.scaled(
                self.video.size(),
                Qt.KeepAspectRatio,
                Qt.SmoothTransformation,
            )
        )

    def show_camera_frame(self, image: QImage):
        self.last_camera_image = image
        pixmap = QPixmap.fromImage(image)
        self.camera_pip.setPixmap(
            pixmap.scaled(
                self.camera_pip.size(),
                Qt.KeepAspectRatio,
                Qt.SmoothTransformation,
            )
        )
        if self.camera_fullscreen and self.camera_fullscreen.isVisible():
            self.camera_fullscreen.update_frame(image)

    def open_camera_fullscreen(self):
        if self.camera_fullscreen is None:
            self.camera_fullscreen = CameraFullscreenWindow(self)
        if self.last_camera_image is not None:
            self.camera_fullscreen.update_frame(self.last_camera_image)
        else:
            self.camera_status.setText("Camera fullscreen: waiting for camera frame")
        self.camera_fullscreen.showFullScreen()

    def change_volume(self, value: int):
        self.server.audio_volume = value / 100.0

    def switch_camera(self):
        self.camera_front = not self.camera_front
        self.server.send_camera_command("SWITCH_FRONT" if self.camera_front else "SWITCH_BACK")

    def change_zoom(self, value: int):
        self.server.send_camera_command(f"ZOOM:{value / 10.0:.1f}")


def main() -> int:
    app = QApplication(sys.argv)
    window = MainWindow()
    window.show()
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
