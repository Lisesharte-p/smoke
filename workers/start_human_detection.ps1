param(
  [string]$ServerUrl = "http://localhost:8080",
  [string]$StorageDir = "",
  [string]$ModelPath = "",
  [string]$FfmpegPath = ""
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$python = Join-Path $root ".venv-detection\Scripts\python.exe"
$requirements = Join-Path $root "workers\requirements-detection.txt"
$pythonInstallerVersion = "3.11.9"
$pythonInstallerUrl = "https://www.python.org/ftp/python/$pythonInstallerVersion/python-$pythonInstallerVersion-amd64.exe"
if ([string]::IsNullOrWhiteSpace($StorageDir)) { $StorageDir = Join-Path $root "data\detections" }
if ([string]::IsNullOrWhiteSpace($ModelPath)) { $ModelPath = Join-Path $root "models\yolov8n.pt" }
if ([string]::IsNullOrWhiteSpace($FfmpegPath)) { $FfmpegPath = Join-Path $root "tools\ffmpeg-9.0.1-essentials_build\bin\ffmpeg.exe" }

function Find-SystemPython {
  $candidatePaths = @()
  $commands = @(Get-Command python.exe -ErrorAction SilentlyContinue)
  foreach ($command in $commands) {
    if ($command.Source -and $command.Source -notlike "*\WindowsApps\*") {
      $candidatePaths += $command.Source
    }
  }
  $candidatePaths += @(
    (Join-Path $env:LocalAppData "Programs\Python\Python311\python.exe"),
    (Join-Path $env:ProgramFiles "Python311\python.exe")
  )
  foreach ($candidate in ($candidatePaths | Select-Object -Unique)) {
    if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
      return (Resolve-Path -LiteralPath $candidate).Path
    }
  }
  return ""
}

function Invoke-PythonCommand([string]$Python, [string[]]$Arguments, [string]$Description) {
  $oldEncoding = $env:PYTHONIOENCODING
  $oldPipCheck = $env:PIP_DISABLE_PIP_VERSION_CHECK
  try {
    $env:PYTHONIOENCODING = "utf-8"
    $env:PIP_DISABLE_PIP_VERSION_CHECK = "1"
    & $Python @Arguments
    if ($LASTEXITCODE -ne 0) {
      throw "$Description 失败，返回码：$LASTEXITCODE"
    }
  } finally {
    $env:PYTHONIOENCODING = $oldEncoding
    $env:PIP_DISABLE_PIP_VERSION_CHECK = $oldPipCheck
  }
}

function Install-PythonIfMissing {
  $systemPython = Find-SystemPython
  if ($systemPython) { return $systemPython }

  $toolsDir = Join-Path $root "tools"
  $installer = Join-Path $toolsDir "python-$pythonInstallerVersion-amd64.exe"
  New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
  if (!(Test-Path -LiteralPath $installer -PathType Leaf)) {
    Write-Host "下载 Python 安装包：$pythonInstallerUrl"
    Invoke-WebRequest -Uri $pythonInstallerUrl -OutFile $installer -UseBasicParsing
  }

  Write-Host "静默安装 Python $pythonInstallerVersion"
  $process = Start-Process -FilePath $installer -Wait -PassThru -ArgumentList @(
    "/quiet",
    "InstallAllUsers=0",
    "PrependPath=1",
    "Include_pip=1",
    "Include_launcher=1",
    "Include_test=0",
    "SimpleInstall=1"
  )
  if ($process.ExitCode -ne 0) {
    throw "Python 安装失败，返回码：$($process.ExitCode)。也可以手动安装：https://www.python.org/downloads/windows/"
  }

  $systemPython = Find-SystemPython
  if (!$systemPython) {
    throw "Python 安装完成但未找到 python.exe。请重新打开 PowerShell 后再运行脚本。"
  }
  return $systemPython
}

if (!(Test-Path $python)) {
  $systemPython = Install-PythonIfMissing
  Write-Host "创建人体识别 Python 虚拟环境：$python"
  Invoke-PythonCommand $systemPython @("-m", "venv", (Join-Path $root ".venv-detection")) "创建人体识别 Python 虚拟环境"
}

Invoke-PythonCommand $python @("-m", "pip", "install", "--upgrade", "pip") "升级 pip"
Invoke-PythonCommand $python @("-m", "pip", "install", "-r", $requirements) "安装人体识别 Python 依赖"

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
