param(
  [string]$ServerUrl = "http://localhost:8888",
  [string]$StorageDir = "D:\smoke\data\detections",
  [string]$ModelPath = "D:\smoke\models\yolov8n.pt",
  [string]$FfmpegPath = "D:\smoke\tools\ffmpeg-9.0.1-essentials_build\bin\ffmpeg.exe"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$python = Join-Path $root ".venv-detection\Scripts\python.exe"

if (!(Test-Path $python)) {
  throw "未找到 D 盘识别环境：$python。请先运行：python -m venv .venv-detection; .\.venv-detection\Scripts\python.exe -m pip install -r workers\requirements-detection.txt"
}

New-Item -ItemType Directory -Force -Path $StorageDir | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ModelPath) | Out-Null

$env:SERVER_URL = $ServerUrl
$env:DETECTION_STORAGE_DIR = $StorageDir
$env:YOLO_MODEL = $ModelPath
if (Test-Path $FfmpegPath) {
  $env:FFMPEG_PATH = $FfmpegPath
}

Write-Host "YOLO 人体识别 worker 启动"
Write-Host "SERVER_URL=$env:SERVER_URL"
Write-Host "DETECTION_STORAGE_DIR=$env:DETECTION_STORAGE_DIR"
Write-Host "YOLO_MODEL=$env:YOLO_MODEL"
Write-Host "FFMPEG_PATH=$env:FFMPEG_PATH"

& $python (Join-Path $root "workers\human_detection_worker.py")
