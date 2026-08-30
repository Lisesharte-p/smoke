@echo off
setlocal
cd /d %~dp0..
set SERVER_URL=http://localhost:8080
set DETECTION_STORAGE_DIR=%CD%\data\detections
set YOLO_MODEL=%CD%\models\yolov8n.pt
set FFMPEG_PATH=%CD%\tools\ffmpeg-9.0.1-essentials_build\bin\ffmpeg.exe
.venv-detection\Scripts\python.exe -u workers\human_detection_worker.py >> worker.run.out.log 2>> worker.run.err.log
