import os
import json
import sys
import subprocess
import time
import threading
from collections import deque
from datetime import datetime
from pathlib import Path
from urllib.parse import quote

try:
    import cv2
    import requests
    from ultralytics import YOLO
except ImportError as exc:
    print("[worker] 缺少依赖:", exc)
    print("[worker] 请先安装: pip install -r workers/requirements-detection.txt")
    sys.exit(1)


SERVER_URL = os.getenv("SERVER_URL", "http://localhost:8888").rstrip("/")
TOKEN = os.getenv("DETECTION_WORKER_TOKEN", "")
STORAGE_DIR = Path(os.getenv("DETECTION_STORAGE_DIR", "data/detections")).resolve()
MODEL_PATH = os.getenv("YOLO_MODEL", "yolov8n.pt")
FFMPEG_PATH = os.getenv("FFMPEG_PATH", "")
CONFIG_REFRESH_SECONDS = int(os.getenv("DETECTION_CONFIG_REFRESH_SECONDS", "30"))
DETECT_INTERVAL_SECONDS = float(os.getenv("DETECTION_INTERVAL_SECONDS", "0.6"))
DEFAULT_FPS = float(os.getenv("DETECTION_DEFAULT_FPS", "12"))


def headers():
    h = {}
    if TOKEN:
        h["X-Detection-Token"] = TOKEN
    return h


def api_get(path):
    res = requests.get(SERVER_URL + path, headers=headers(), timeout=10)
    res.raise_for_status()
    data = res.json()
    if data.get("code") != 0:
        raise RuntimeError(data.get("msg") or "server returned code != 0")
    return data.get("data")


def api_post(path, payload):
    h = headers()
    h["Content-Type"] = "application/json; charset=utf-8"
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    res = requests.post(SERVER_URL + path, data=body, headers=h, timeout=15)
    res.raise_for_status()
    data = res.json()
    if data.get("code") != 0:
        raise RuntimeError(data.get("msg") or "server returned code != 0")
    return data.get("data")


def dt_text(ts=None):
    return datetime.fromtimestamp(ts or time.time()).strftime("%Y-%m-%d %H:%M:%S")


def safe_part(value):
    return "".join(c if c.isalnum() or c in ("-", "_") else "_" for c in str(value or "unknown"))


def find_ffmpeg():
    candidates = []
    if FFMPEG_PATH:
        candidates.append(Path(FFMPEG_PATH))
    candidates.extend([
        Path("D:/smoke/tools/ffmpeg-9.0.1-essentials_build/bin/ffmpeg.exe"),
        Path("tools/ffmpeg-9.0.1-essentials_build/bin/ffmpeg.exe").resolve(),
    ])
    for path in candidates:
        if path.exists():
            return str(path)
    return ""


def camera_stream_url(cam):
    protocol = str(cam.get("protocol") or "mjpeg").lower()
    ip = cam.get("ip")
    port = cam.get("port")
    username = cam.get("username") or ""
    password = cam.get("password") or ""
    auth = ""
    if username:
        auth = quote(username, safe="") + ":" + quote(password, safe="") + "@"
    if protocol == "rtsp":
        return f"rtsp://{auth}{ip}:{port}/live"
    return f"http://{auth}{ip}:{port}/video"


class CameraWorker(threading.Thread):
    def __init__(self, cam, model):
        super().__init__(daemon=True)
        self.cam = cam
        self.model = model
        self.stop_event = threading.Event()
        self.lock = threading.Lock()
        self.last_event_at = 0.0

    def update(self, cam):
        with self.lock:
            self.cam = cam

    def stop(self):
        self.stop_event.set()

    def current_cam(self):
        with self.lock:
            return dict(self.cam)

    def report_status(self, status, message=""):
        try:
            api_post("/api/internal/detection-status", {
                "deviceId": self.cam.get("id"),
                "status": status,
                "message": message,
            })
        except Exception as exc:
            print(f"[worker] {self.cam.get('id')} 状态上报失败: {exc}")

    def run(self):
        device_id = self.cam.get("id")
        while not self.stop_event.is_set():
            cam = self.current_cam()
            if not cam.get("enabled", True):
                print(f"[worker] {device_id} 人体识别已关闭")
                self.report_status("disabled", "人体识别已关闭")
                time.sleep(3)
                continue
            try:
                self.capture_loop(cam)
            except Exception as exc:
                print(f"[worker] {device_id} 识别循环异常: {exc}")
                self.report_status("error", str(exc))
                time.sleep(5)

    def capture_loop(self, cam):
        device_id = cam.get("id")
        url = camera_stream_url(cam)
        print(f"[worker] 连接摄像头 {device_id}: {url}")
        cap = cv2.VideoCapture(url)
        if not cap.isOpened():
            raise RuntimeError("无法打开摄像头流")
        self.report_status("running", "摄像头流已连接，正在识别")

        fps = cap.get(cv2.CAP_PROP_FPS)
        if not fps or fps <= 1 or fps > 60:
            fps = DEFAULT_FPS
        pre_seconds = int(cam.get("preSeconds") or 5)
        post_seconds = int(cam.get("postSeconds") or 10)
        max_buffer = max(int((pre_seconds + 2) * fps), 1)
        frames = deque(maxlen=max_buffer)
        last_detect_check = 0.0
        last_status_report = time.time()

        try:
            while not self.stop_event.is_set():
                latest = self.current_cam()
                if latest.get("id") != device_id or not latest.get("enabled", True):
                    break
                ok, frame = cap.read()
                if not ok or frame is None:
                    raise RuntimeError("读取摄像头帧失败")

                now = time.time()
                if now - last_status_report >= 15:
                    self.report_status("running", "摄像头流已连接，正在识别")
                    last_status_report = now
                frames.append((now, frame.copy()))
                if now - last_detect_check < DETECT_INTERVAL_SECONDS:
                    continue
                last_detect_check = now

                confidence = self.detect_person(frame, float(latest.get("confidenceThreshold") or 0.5))
                if confidence is None:
                    continue
                cooldown = int(latest.get("cooldownSeconds") or 30)
                if now - self.last_event_at < cooldown:
                    continue
                self.last_event_at = now
                print(f"[worker] {device_id} 检测到人体，置信度 {confidence:.3f}")
                self.save_event(latest, cap, fps, frames, frame.copy(), confidence, now, post_seconds)
        finally:
            cap.release()

    def detect_person(self, frame, threshold):
        result = self.model.predict(frame, classes=[0], conf=threshold, verbose=False)[0]
        if result.boxes is None or len(result.boxes) == 0:
            return None
        confidences = result.boxes.conf.cpu().numpy().tolist()
        if not confidences:
            return None
        best = max(float(v) for v in confidences)
        return best if best >= threshold else None

    def save_event(self, cam, cap, fps, pre_buffer, snapshot, confidence, detected_at, post_seconds):
        device_id = safe_part(cam.get("id"))
        day = datetime.fromtimestamp(detected_at).strftime("%Y%m%d")
        stamp = datetime.fromtimestamp(detected_at).strftime("%H%M%S")
        rel_dir = Path(device_id) / day
        out_dir = STORAGE_DIR / rel_dir
        out_dir.mkdir(parents=True, exist_ok=True)

        video_name = f"{stamp}_person.mp4"
        snapshot_name = f"{stamp}_person.jpg"
        video_path = out_dir / video_name
        snapshot_path = out_dir / snapshot_name

        start_ts = detected_at - int(cam.get("preSeconds") or 5)
        clip_frames = [(ts, frame) for ts, frame in list(pre_buffer) if ts >= start_ts]
        deadline = time.time() + post_seconds

        while time.time() < deadline and not self.stop_event.is_set():
            ok, frame = cap.read()
            if not ok or frame is None:
                break
            clip_frames.append((time.time(), frame.copy()))

        if not clip_frames:
            clip_frames.append((detected_at, snapshot))
        cv2.imwrite(str(snapshot_path), snapshot)
        self.write_video(video_path, [f for _, f in clip_frames], fps)

        payload = {
            "deviceId": cam.get("id"),
            "startedAt": dt_text(clip_frames[0][0]),
            "endedAt": dt_text(clip_frames[-1][0]),
            "confidence": round(confidence, 4),
            "snapshotPath": str(rel_dir / snapshot_name).replace("\\", "/"),
            "videoPath": str(rel_dir / video_name).replace("\\", "/"),
        }
        data = api_post("/api/internal/detection-records", payload)
        print(f"[worker] {cam.get('id')} 识别记录已上报: {data}")

    def write_video(self, path, frames, fps):
        h, w = frames[0].shape[:2]
        ffmpeg = find_ffmpeg()
        if ffmpeg:
            self.write_video_ffmpeg(ffmpeg, path, frames, fps, w, h)
            return
        writer = cv2.VideoWriter(str(path), cv2.VideoWriter_fourcc(*"mp4v"), fps, (w, h))
        if not writer.isOpened():
            raise RuntimeError("无法创建 MP4 回放文件")
        try:
            for frame in frames:
                if frame.shape[1] != w or frame.shape[0] != h:
                    frame = cv2.resize(frame, (w, h))
                writer.write(frame)
        finally:
            writer.release()

    def write_video_ffmpeg(self, ffmpeg, path, frames, fps, w, h):
        cmd = [
            ffmpeg,
            "-y",
            "-f", "rawvideo",
            "-pix_fmt", "bgr24",
            "-s", f"{w}x{h}",
            "-r", str(max(fps, 1)),
            "-i", "-",
            "-an",
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-pix_fmt", "yuv420p",
            "-movflags", "+faststart",
            str(path),
        ]
        proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        assert proc.stdin is not None
        try:
            for frame in frames:
                if frame.shape[1] != w or frame.shape[0] != h:
                    frame = cv2.resize(frame, (w, h))
                proc.stdin.write(frame.tobytes())
            proc.stdin.close()
            stderr = proc.stderr.read().decode("utf-8", errors="ignore") if proc.stderr else ""
            code = proc.wait()
        finally:
            if proc.poll() is None:
                proc.kill()
        if code != 0:
            raise RuntimeError("ffmpeg 写入 H.264 MP4 失败: " + stderr[-300:])


def load_cameras():
    cameras = api_get("/api/internal/camera-configs")
    return {cam["id"]: cam for cam in cameras if cam.get("ip") and cam.get("port")}


def main():
    STORAGE_DIR.mkdir(parents=True, exist_ok=True)
    print(f"[worker] YOLO 模型: {MODEL_PATH}")
    print(f"[worker] 服务器: {SERVER_URL}")
    print(f"[worker] 存储目录: {STORAGE_DIR}")
    print(f"[worker] ffmpeg: {find_ffmpeg() or '未找到，回退 OpenCV mp4v'}")
    model = YOLO(MODEL_PATH)
    workers = {}
    try:
        while True:
            cameras = load_cameras()
            for device_id, cam in cameras.items():
                if device_id in workers:
                    workers[device_id].update(cam)
                else:
                    worker = CameraWorker(cam, model)
                    workers[device_id] = worker
                    worker.start()
            for device_id in list(workers.keys()):
                if device_id not in cameras:
                    workers[device_id].stop()
                    del workers[device_id]
            time.sleep(CONFIG_REFRESH_SECONDS)
    except KeyboardInterrupt:
        print("[worker] 正在停止")
    finally:
        for worker in workers.values():
            worker.stop()


if __name__ == "__main__":
    main()
