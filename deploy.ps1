<#
 .SYNOPSIS
   编译并启动智慧农业平台，首次部署时可导入 mysql.sql。

 .DESCRIPTION
   脚本使用项目 JDK 17，自动准备 Maven，检查 MySQL 网络，
   可选导入完整数据库，启动 server.PlainWebServer，并检查首页和数据库 API。

 .EXAMPLE
   .\deploy.ps1 -InitializeDatabase -Force -BoardIp 10.94.204.29 -BoardPort 8888

 .EXAMPLE
   .\deploy.ps1

 .EXAMPLE
   .\deploy.ps1 -Stop

 .EXAMPLE
   .\deploy.ps1 -Port 9090 -OpenFirewall

智慧农业平台部署脚本

默认行为：
  读取项目根目录 .env.local
  使用项目 JDK 17 编译 Java 源码
  检查数据库网络
  启动 PlainWebServer
  验证首页和数据库 API

首次部署：
  .\deploy.ps1 -InitializeDatabase -Force -BoardIp 10.94.204.29 -BoardPort 8888

注意：
  mysql.sql 是完整数据库导出文件，包含 DROP TABLE。只有明确使用
  -InitializeDatabase 时才会导入，-Force 仅用于跳过数据重置确认。
#>

param(
    [ValidateRange(1, 65535)]
    [int]$Port = 8080,

    [switch]$InitializeDatabase,
    [switch]$Force,
    [switch]$StartDetectionWorker,
    [switch]$SkipDatabaseCheck,
    [switch]$SkipMySqlInstallerDownload,
    [switch]$SkipOptionalConfigPrompt,
    [switch]$OpenFirewall,
    [switch]$Stop,

    [string]$MysqlExe = "",
    [string]$MavenHome = "",
    [string]$JavaHome = "",

    [string]$DatabaseHost = "",
    [ValidateRange(1, 65535)]
    [int]$DatabasePort = 0,
    [string]$DatabaseName = "",
    [string]$DatabaseUser = "",
    [string]$DatabasePassword = "",
    [string]$DatabaseAdminUser = "",
    [string]$DatabaseAdminPassword = "",

    [string]$BoardIp = "",
    [ValidateRange(1, 65535)]
    [int]$BoardPort = 0
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot ".")).Path
$RuntimeDir = Join-Path $Root "tmp"
$PidFile = Join-Path $RuntimeDir ".smoke-server.pid"
$LegacyPidFile = Join-Path $Root ".smoke-server.pid"
$MySqlInstallerVersion = "8.0.46.0"
$MySqlInstallerUrl = "https://cdn.mysql.com/Downloads/MySQLInstaller/mysql-installer-web-community-$MySqlInstallerVersion.msi"
$MySqlInstallerMd5 = "210420AEF5B5006AB54BB1DAB4183768"
$PythonInstallerVersion = "3.11.9"
$PythonInstallerUrl = "https://www.python.org/ftp/python/$PythonInstallerVersion/python-$PythonInstallerVersion-amd64.exe"
$PythonInstallerFileName = "python-$PythonInstallerVersion-amd64.exe"
$LocalConfigFile = Join-Path $Root "config\feishu.local.properties"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "[OK] $Message" -ForegroundColor Green
}

function Write-Warn([string]$Message) {
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Fail([string]$Message) {
    throw $Message
}

function Resolve-RequiredFile([string]$Path, [string]$Description) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Fail "未找到$Description：$Path"
    }
    return (Resolve-Path -LiteralPath $Path).Path
}

function Get-JavaVersion([string]$Java) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $Java
    $info.Arguments = "-version"
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    if (-not $process.Start()) {
        Fail "无法启动 Java：$Java"
    }

    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $stdout = $stdoutTask.Result.Trim()
    $stderr = $stderrTask.Result.Trim()

    if ($process.ExitCode -ne 0) {
        $detail = if ($stderr) { $stderr } else { $stdout }
        Fail "Java 版本检查失败：$detail"
    }

    $versionOutput = if ($stderr) { $stderr } else { $stdout }
    if (-not $versionOutput) {
        Fail "Java 版本检查未返回任何输出：$Java"
    }
    return ($versionOutput -split "[`r`n]+" | Select-Object -First 1)
}

function Read-EnvFile([string]$Path) {
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $values
    }

    Get-Content -LiteralPath $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#") -or -not $line.Contains("=")) {
            return
        }
        $parts = $line.Split("=", 2)
        $name = $parts[0].Trim()
        $value = $parts[1].Trim().Trim('"').Trim("'")
        if ($name) {
            $values[$name] = $value
        }
    }
    return $values
}

function ConvertFrom-SecureStringPlain([securestring]$SecureValue) {
    $ptr = [IntPtr]::Zero
    try {
        $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecureValue)
        return [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    } finally {
        if ($ptr -ne [IntPtr]::Zero) {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
        }
    }
}

function Read-ConfigSecret([string]$Prompt) {
    $secure = Read-Host $Prompt -AsSecureString
    if (-not $secure -or $secure.Length -eq 0) {
        return ""
    }
    return ConvertFrom-SecureStringPlain $secure
}

function Set-PlainConfigValue([string]$Path, [string]$Name, [string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }

    $lines = @()
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        $lines = @(Get-Content -LiteralPath $Path -Encoding UTF8)
    }

    $escaped = $Value.Replace("\", "\\")
    $updated = $false
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match "^\s*#?\s*$([regex]::Escape($Name))\s*=") {
            $lines[$i] = "$Name=$escaped"
            $updated = $true
            break
        }
    }
    if (-not $updated) {
        if ($lines.Count -gt 0 -and -not [string]::IsNullOrWhiteSpace($lines[-1])) {
            $lines += ""
        }
        $lines += "$Name=$escaped"
    }

    $dir = Split-Path -Parent $Path
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $encoding = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, (($lines -join "`r`n") + "`r`n"), $encoding)
    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

function Read-LocalConfigFile([string]$Path) {
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $values
    }
    Get-Content -LiteralPath $Path -Encoding UTF8 | ForEach-Object {
        $line = $_.Trim()
        if (-not $line -or $line.StartsWith("#") -or -not $line.Contains("=")) {
            return
        }
        $parts = $line.Split("=", 2)
        $name = $parts[0].Trim()
        $value = $parts[1].Trim()
        if ($name) {
            $values[$name] = $value
        }
    }
    return $values
}

function Get-OptionalConfigValue([hashtable]$FileValues, [hashtable]$LocalValues, [string[]]$Names) {
    foreach ($name in $Names) {
        $fromProcess = [Environment]::GetEnvironmentVariable($name, "Process")
        if (-not (Test-PlaceholderConfigValue $fromProcess)) {
            return $fromProcess
        }
        if ($FileValues.ContainsKey($name) -and -not (Test-PlaceholderConfigValue ([string]$FileValues[$name]))) {
            return [string]$FileValues[$name]
        }
        if ($LocalValues.ContainsKey($name) -and -not (Test-PlaceholderConfigValue ([string]$LocalValues[$name]))) {
            return [string]$LocalValues[$name]
        }
    }
    return ""
}

function Test-PlaceholderConfigValue([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $true
    }
    $text = $Value.Trim()
    return (
        $text -like "replace-with-*" -or
        $text -like "your-*" -or
        $text.Contains("你的") -or
        $text.Contains("请填写")
    )
}

function Initialize-LocalConfigFile {
    if (Test-Path -LiteralPath $LocalConfigFile -PathType Leaf) {
        return
    }

    $example = Join-Path $Root "config\feishu.local.properties.example"
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LocalConfigFile) | Out-Null
    if (Test-Path -LiteralPath $example -PathType Leaf) {
        Copy-Item -LiteralPath $example -Destination $LocalConfigFile
    } else {
        $content = @(
            "# 本机私有配置，已被 .gitignore 忽略。",
            "SMART_QA_API_URL=https://api.deepseek.com/chat/completions",
            "SMART_QA_MODEL=deepseek-chat",
            "QWEATHER_LOCATION=106.565952,29.642614",
            "QWEATHER_HOST=https://ma5rk8cjh3.re.qweatherapi.com",
            "FEISHU_ENABLED=true",
            "FEISHU_SMART_QA_ENABLED=true",
            "FEISHU_REMEMBER_LAST_CHAT=true"
        )
        $encoding = New-Object System.Text.UTF8Encoding($false)
        [System.IO.File]::WriteAllText($LocalConfigFile, (($content -join "`r`n") + "`r`n"), $encoding)
    }
}

function Ensure-OptionalApiConfig([hashtable]$FileValues) {
    if ($SkipOptionalConfigPrompt) {
        return
    }

    Initialize-LocalConfigFile
    $localValues = Read-LocalConfigFile $LocalConfigFile
    $changed = $false

    if (-not (Get-OptionalConfigValue $FileValues $localValues @("SMART_QA_API_KEY", "DEEPSEEK_API_KEY", "API"))) {
        Write-Step "配置 DeepSeek API"
        $value = Read-ConfigSecret "请输入 DeepSeek API Key（留空跳过）"
        if ($value) {
            Set-PlainConfigValue $LocalConfigFile "SMART_QA_API_KEY" $value
            Set-PlainConfigValue $LocalConfigFile "SMART_QA_API_URL" "https://api.deepseek.com/chat/completions"
            Set-PlainConfigValue $LocalConfigFile "SMART_QA_MODEL" "deepseek-chat"
            $changed = $true
        } else {
            Write-Warn "未填写 DeepSeek API Key，智能问答会不可用。"
        }
    }

    $localValues = Read-LocalConfigFile $LocalConfigFile
    if (-not (Get-OptionalConfigValue $FileValues $localValues @("QWEATHER_API_KEY", "WEATHER_API_KEY"))) {
        Write-Step "配置和风天气 API"
        $value = Read-ConfigSecret "请输入和风天气 API Key（留空跳过）"
        if ($value) {
            Set-PlainConfigValue $LocalConfigFile "QWEATHER_API_KEY" $value
            Set-PlainConfigValue $LocalConfigFile "QWEATHER_LOCATION" "106.565952,29.642614"
            Set-PlainConfigValue $LocalConfigFile "QWEATHER_HOST" "https://ma5rk8cjh3.re.qweatherapi.com"
            $changed = $true
        } else {
            Write-Warn "未填写和风天气 API Key，天气接口会回退到模拟数据。"
        }
    }

    $localValues = Read-LocalConfigFile $LocalConfigFile
    if (-not (Get-OptionalConfigValue $FileValues $localValues @("FEISHU_APP_ID"))) {
        Write-Step "配置飞书机器人 APPID"
        $value = Read-Host "请输入飞书 APPID（留空跳过）"
        if ($value) {
            Set-PlainConfigValue $LocalConfigFile "FEISHU_APP_ID" $value.Trim()
            $changed = $true
        } else {
            Write-Warn "未填写飞书 APPID，飞书机器人不会启动。"
        }
    }

    $localValues = Read-LocalConfigFile $LocalConfigFile
    if (-not (Get-OptionalConfigValue $FileValues $localValues @("FEISHU_APP_SECRET"))) {
        Write-Step "配置飞书机器人 APPSECRET"
        $value = Read-ConfigSecret "请输入飞书 APPSECRET（留空跳过）"
        if ($value) {
            Set-PlainConfigValue $LocalConfigFile "FEISHU_APP_SECRET" $value
            Set-PlainConfigValue $LocalConfigFile "FEISHU_ENABLED" "true"
            Set-PlainConfigValue $LocalConfigFile "FEISHU_SMART_QA_ENABLED" "true"
            Set-PlainConfigValue $LocalConfigFile "FEISHU_REMEMBER_LAST_CHAT" "true"
            $changed = $true
        } else {
            Write-Warn "未填写飞书 APPSECRET，飞书机器人不会启动。"
        }
    }

    if ($changed) {
        Write-Ok "本机私有配置已写入：$LocalConfigFile"
    }
}

function Test-BoardHostValue([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $false
    }
    return $Value -match "^[A-Za-z0-9][A-Za-z0-9.-]*$"
}

function Ensure-BoardConfig([hashtable]$FileValues, [string]$EnvFilePath, [string]$BoardIpOverride, [int]$BoardPortOverride) {
    $effectiveBoardIp = Get-ConfigValue $FileValues "BOARD_IP" $BoardIpOverride ""
    $boardPortOverrideText = if ($BoardPortOverride -gt 0) { [string]$BoardPortOverride } else { "" }
    $effectiveBoardPortText = Get-ConfigValue $FileValues "BOARD_PORT" $boardPortOverrideText "8888"
    $shouldPromptBoardIp = [string]::IsNullOrWhiteSpace($BoardIpOverride)
    $shouldPromptBoardPort = [string]::IsNullOrWhiteSpace($boardPortOverrideText)

    if ($shouldPromptBoardIp) {
        Write-Step "配置开发板连接"
        $prompt = if ($effectiveBoardIp) {
            "请输入板子 IP（当前：$effectiveBoardIp，直接回车保持不变）"
        } else {
            "请输入板子 IP（留空表示不配置开发板）"
        }
        $input = Read-Host $prompt
        if (-not [string]::IsNullOrWhiteSpace($input)) {
            $effectiveBoardIp = $input.Trim()
        }
    }

    if ($shouldPromptBoardPort) {
        $prompt = if ($effectiveBoardPortText) {
            "请输入板子端口（当前：$effectiveBoardPortText，直接回车保持不变）"
        } else {
            "请输入板子端口（默认 8888）"
        }
        $input = Read-Host $prompt
        if (-not [string]::IsNullOrWhiteSpace($input)) {
            $effectiveBoardPortText = $input.Trim()
        }
    }

    if ([string]::IsNullOrWhiteSpace($effectiveBoardPortText)) {
        $effectiveBoardPortText = "8888"
    }

    if ($effectiveBoardIp -and -not (Test-BoardHostValue $effectiveBoardIp)) {
        Fail "BOARD_IP 或 -BoardIp 含有不支持的字符：$effectiveBoardIp"
    }

    $effectiveBoardPort = 0
    if (-not [int]::TryParse($effectiveBoardPortText, [ref]$effectiveBoardPort) -or $effectiveBoardPort -lt 1 -or $effectiveBoardPort -gt 65535) {
        Fail "BOARD_PORT 或 -BoardPort 必须是 1 到 65535 的整数。"
    }

    if ($effectiveBoardIp) {
        Set-PlainConfigValue $EnvFilePath "BOARD_IP" $effectiveBoardIp
    }
    Set-PlainConfigValue $EnvFilePath "BOARD_PORT" $effectiveBoardPortText

    return @{
        Ip = $effectiveBoardIp
        PortText = $effectiveBoardPortText
        Port = $effectiveBoardPort
    }
}

function Get-ConfigValue([hashtable]$Values, [string]$Name, [string]$Override, [string]$Default) {
    if (-not [string]::IsNullOrWhiteSpace($Override)) {
        return $Override
    }
    if ($Values.ContainsKey($Name) -and -not [string]::IsNullOrWhiteSpace([string]$Values[$Name])) {
        return [string]$Values[$Name]
    }
    $fromProcess = [Environment]::GetEnvironmentVariable($Name, "Process")
    if (-not [string]::IsNullOrWhiteSpace($fromProcess)) {
        return $fromProcess
    }
    return $Default
}

function Set-ProcessEnvironment([hashtable]$Values) {
    foreach ($key in $Values.Keys) {
        [Environment]::SetEnvironmentVariable($key, [string]$Values[$key], "Process")
    }
}

function Update-BoardDeviceAddresses([string]$Mysql, [string]$BoardIp, [int]$BoardPort) {
    if (-not $BoardIp) {
        return
    }
    if (-not (Test-BoardHostValue $BoardIp)) {
        Fail "BOARD_IP 或 -BoardIp 含有不支持的字符：$BoardIp"
    }
    $safeBoardIp = $BoardIp.Replace("'", "''")
    $sql = "USE farm; UPDATE device SET ip='$safeBoardIp', port=$BoardPort, online=0 WHERE type <> '摄像头';"
    Invoke-MySqlText $Mysql $sql
}

function Find-Mysql([string]$Requested) {
    if ($Requested) {
        return Resolve-RequiredFile $Requested "mysql.exe"
    }

    $command = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $candidates = @(
        (Join-Path ${env:ProgramFiles} "MySQL\MySQL Server 8.0\bin\mysql.exe"),
        (Join-Path ${env:ProgramFiles(x86)} "MySQL\MySQL Server 8.0\bin\mysql.exe"),
        (Join-Path ${env:ProgramFiles} "MariaDB 11.0\bin\mysql.exe"),
        (Join-Path ${env:ProgramFiles} "MariaDB 10.11\bin\mysql.exe")
    )
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    Fail "未找到 mysql.exe。请安装 MySQL 客户端并加入 PATH，或使用 -MysqlExe 指定完整路径。"
}

function Find-Python {
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

function Download-PythonInstaller {
    $toolsDir = Join-Path $Root "tools"
    $installer = Join-Path $toolsDir $PythonInstallerFileName
    New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null

    if (Test-Path -LiteralPath $installer -PathType Leaf) {
        Write-Ok "已找到 Python 安装包：$installer"
        return $installer
    }

    Write-Host "下载地址：$PythonInstallerUrl"
    try {
        Invoke-WebRequest -Uri $PythonInstallerUrl -OutFile $installer -UseBasicParsing
    } catch {
        Fail "下载 Python 安装包失败：$($_.Exception.Message)"
    }
    Write-Ok "Python 安装包下载完成：$installer"
    return $installer
}

function Install-PythonIfMissing {
    $python = Find-Python
    if ($python) {
        return $python
    }

    Write-Step "下载并安装 Python $PythonInstallerVersion"
    $installer = Download-PythonInstaller
    $installArgs = @(
        "/quiet",
        "InstallAllUsers=0",
        "PrependPath=1",
        "Include_pip=1",
        "Include_launcher=1",
        "Include_test=0",
        "SimpleInstall=1"
    )
    $process = Start-Process -FilePath $installer -ArgumentList $installArgs -Wait -PassThru
    if ($process.ExitCode -ne 0) {
        Fail "Python 安装失败，返回码：$($process.ExitCode)。也可以手动安装：https://www.python.org/downloads/windows/"
    }

    $python = Find-Python
    if (-not $python) {
        Fail "Python 安装完成但未找到 python.exe。请重新打开 PowerShell 后再运行脚本。"
    }
    Write-Ok "Python 已安装：$python"
    return $python
}

function Invoke-PythonCommand([string]$Python, [string[]]$Arguments, [string]$Description) {
    $previousPythonIoEncoding = $env:PYTHONIOENCODING
    $previousPipVersionCheck = $env:PIP_DISABLE_PIP_VERSION_CHECK
    try {
        $env:PYTHONIOENCODING = "utf-8"
        $env:PIP_DISABLE_PIP_VERSION_CHECK = "1"
        & $Python @Arguments
        if ($LASTEXITCODE -ne 0) {
            Fail "$Description 失败，返回码：$LASTEXITCODE"
        }
    } finally {
        $env:PYTHONIOENCODING = $previousPythonIoEncoding
        $env:PIP_DISABLE_PIP_VERSION_CHECK = $previousPipVersionCheck
    }
}

function Ensure-DetectionPythonEnvironment {
    $venvPython = Join-Path $Root ".venv-detection\Scripts\python.exe"
    $requirements = Resolve-RequiredFile (Join-Path $Root "workers\requirements-detection.txt") "人体识别 Python 依赖清单"
    if (-not (Test-Path -LiteralPath $venvPython -PathType Leaf)) {
        $systemPython = Install-PythonIfMissing
        Write-Step "创建人体识别 Python 虚拟环境"
        Invoke-PythonCommand $systemPython @("-m", "venv", (Join-Path $Root ".venv-detection")) "创建人体识别 Python 虚拟环境"
    }

    $venvPython = Resolve-RequiredFile $venvPython "人体识别 Python 环境"
    Write-Step "安装人体识别 Python 依赖"
    Invoke-PythonCommand $venvPython @("-m", "pip", "install", "--upgrade", "pip") "升级 pip"
    Invoke-PythonCommand $venvPython @("-m", "pip", "install", "-r", $requirements) "安装人体识别 Python 依赖"
    Write-Ok "人体识别 Python 环境已就绪：$venvPython"
    return $venvPython
}

function Test-LocalDatabaseHost([string]$DatabaseHost) {
    $normalized = $DatabaseHost.Trim().TrimEnd(".").ToLowerInvariant()
    if ($normalized -in @("localhost", "127.0.0.1", "::1")) {
        return $true
    }

    if ($env:COMPUTERNAME -and $normalized -eq $env:COMPUTERNAME.ToLowerInvariant()) {
        return $true
    }
    return $false
}

function Get-LocalMySqlServices {
    return @(Get-Service -ErrorAction SilentlyContinue | Where-Object {
        $_.Name -match "(?i)mysql|mariadb" -or $_.DisplayName -match "(?i)mysql|mariadb"
    })
}

function Download-MySqlInstaller {
    $toolsDir = Join-Path $Root "tools"
    $installer = Join-Path $toolsDir "mysql-installer-web-community-$MySqlInstallerVersion.msi"
    New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null

    if (Test-Path -LiteralPath $installer -PathType Leaf) {
        $existingHash = (Get-FileHash -LiteralPath $installer -Algorithm MD5).Hash
        if ($existingHash -eq $MySqlInstallerMd5) {
            Write-Ok "已找到已校验的 MySQL Installer：$installer"
            return $installer
        }
        Fail "已有 MySQL Installer 文件校验失败：$installer。请删除该文件后重新运行脚本。"
    }

    Write-Host "下载地址：$MySqlInstallerUrl"
    try {
        Invoke-WebRequest -Uri $MySqlInstallerUrl -OutFile $installer -UseBasicParsing
    } catch {
        Fail "下载 MySQL Installer 失败：$($_.Exception.Message)"
    }

    $downloadedHash = (Get-FileHash -LiteralPath $installer -Algorithm MD5).Hash
    if ($downloadedHash -ne $MySqlInstallerMd5) {
        Fail "MySQL Installer 下载文件校验失败：$installer。请删除该文件后重新运行脚本。"
    }
    Write-Ok "MySQL Installer 下载并校验完成：$installer"
    return $installer
}

function Wait-DatabasePort([string]$DatabaseHost, [int]$DatabasePort, [int]$TimeoutSeconds = 30) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $reachable = Test-NetConnection -ComputerName $DatabaseHost -Port $DatabasePort -InformationLevel Quiet -WarningAction SilentlyContinue
        if ($reachable) {
            return $true
        }
        Start-Sleep -Seconds 1
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Start-LocalMySqlInstallation([int]$DatabasePort) {
    $installedServices = @(Get-LocalMySqlServices)
    if ($installedServices.Count -gt 0) {
        foreach ($service in $installedServices) {
            if ($service.Status -in @("Stopped", "Paused")) {
                Write-Step "启动本机数据库服务：$($service.Name)"
                try {
                    Start-Service -Name $service.Name -ErrorAction Stop
                } catch {
                    Fail "无法启动本机数据库服务 $($service.Name)：$($_.Exception.Message)。请以管理员身份运行 PowerShell。"
                }
            }
        }

        if (Wait-DatabasePort $env:DB_HOST $DatabasePort 30) {
            Write-Ok "本机数据库服务已启动并监听：$($env:DB_HOST):$DatabasePort"
            return
        }

        $serviceNames = ($installedServices | ForEach-Object { "$($_.Name)($($_.Status))" }) -join "、"
        Fail "检测到本机已有 MySQL/MariaDB 服务：$serviceNames，但 $($env:DB_HOST):$DatabasePort 仍不可连接。请检查 MySQL 配置文件中的 port 是否为 $DatabasePort，或查看错误日志。脚本不会重复下载安装。"
    }

    if ($SkipMySqlInstallerDownload) {
        Fail "未检测到本机 MySQL 服务，且已使用 -SkipMySqlInstallerDownload 禁用自动下载。请按 部署文档.md 的 5.2 节安装 MySQL。"
    }

    Write-Step "下载 MySQL Installer"
    $installer = Download-MySqlInstaller
    Write-Step "启动 MySQL 安装向导"
    try {
        Start-Process -FilePath "msiexec.exe" -ArgumentList ('/i "{0}"' -f $installer) -Verb RunAs
    } catch {
        Fail "无法启动 MySQL 安装向导：$($_.Exception.Message)"
    }

    Fail ("未检测到本机 MySQL 服务，已下载并启动 MySQL Installer。" +
        "`n请在向导中选择 Server only，设置 root 密码并完成配置后，重新运行 .\deploy.ps1。" +
        "`n安装包位置：$installer")
}

function Find-Maven([string]$Requested) {
    if ($Requested) {
        $requestedMvn = Join-Path $Requested "bin\mvn.cmd"
        return Resolve-RequiredFile $requestedMvn "Maven"
    }

    $command = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    $localMvn = Join-Path $Root "tools\apache-maven-3.9.9\bin\mvn.cmd"
    if (Test-Path -LiteralPath $localMvn -PathType Leaf) {
        return (Resolve-Path -LiteralPath $localMvn).Path
    }

    Write-Step "下载项目私有 Maven 3.9.9"
    $toolsDir = Join-Path $Root "tools"
    $mavenRoot = Join-Path $toolsDir "apache-maven-3.9.9"
    $mavenZip = Join-Path $toolsDir "apache-maven-3.9.9-bin.zip"
    New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null

    if ((Test-Path -LiteralPath $mavenZip -PathType Leaf) -and
        ((Get-Item -LiteralPath $mavenZip).Length -eq 0)) {
        Remove-Item -LiteralPath $mavenZip -Force
    }
    if (-not (Test-Path -LiteralPath $mavenZip -PathType Leaf)) {
        Invoke-WebRequest `
            -Uri "https://archive.apache.org/dist/maven/maven-3/3.9.9/binaries/apache-maven-3.9.9-bin.zip" `
            -OutFile $mavenZip `
            -UseBasicParsing
    }
    if (-not (Test-Path -LiteralPath $mavenRoot -PathType Container)) {
        Expand-Archive -LiteralPath $mavenZip -DestinationPath $toolsDir -Force
    }
    return Resolve-RequiredFile (Join-Path $mavenRoot "bin\mvn.cmd") "Maven"
}

function New-MySqlDefaultsFile {
    $path = Join-Path $env:TEMP ("smoke-mysql-" + [guid]::NewGuid().ToString("N") + ".cnf")
    $content = @(
        "[client]"
        "protocol=tcp"
        "host=$env:DB_HOST"
        "port=$env:DB_PORT"
        "user=$env:DB_ADMIN_USER"
        "password=$env:DB_ADMIN_PASS"
        "default-character-set=utf8mb4"
    ) -join "`r`n"
    [System.IO.File]::WriteAllText(
        $path,
        $content + "`r`n",
        (New-Object System.Text.UTF8Encoding($false))
    )
    return $path
}

function Invoke-MySqlBytes([string]$Client, [byte[]]$Bytes) {
    $defaultsFile = New-MySqlDefaultsFile
    try {
        $info = New-Object System.Diagnostics.ProcessStartInfo
        $info.FileName = $Client
        $info.Arguments = '--defaults-extra-file="' + $defaultsFile + '" --batch'
        $info.WorkingDirectory = $Root
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardInput = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true

        $process = New-Object System.Diagnostics.Process
        $process.StartInfo = $info
        if (-not $process.Start()) {
            Fail "无法启动 mysql.exe。"
        }

        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.BaseStream.Write($Bytes, 0, $Bytes.Length)
        $process.StandardInput.Close()
        $process.WaitForExit()

        $stdout = $stdoutTask.Result
        $stderr = $stderrTask.Result
        if ($process.ExitCode -ne 0) {
            $detail = $stderr.Trim()
            if (-not $detail) { $detail = $stdout.Trim() }
            Fail "mysql 执行失败：$detail"
        }
    } finally {
        if (Test-Path -LiteralPath $defaultsFile) {
            Remove-Item -LiteralPath $defaultsFile -Force -ErrorAction SilentlyContinue
        }
    }
}

function Invoke-MySqlFile([string]$Client, [string]$SqlFile) {
    $resolved = Resolve-RequiredFile $SqlFile "数据库脚本"
    Write-Host "导入数据库脚本：$(Split-Path -Leaf $resolved)"
    Invoke-MySqlBytes $Client ([System.IO.File]::ReadAllBytes($resolved))
}

function Invoke-MySqlText([string]$Client, [string]$Sql) {
    $encoding = New-Object System.Text.UTF8Encoding($false)
    Invoke-MySqlBytes $Client $encoding.GetBytes($Sql)
}

function Get-PidFilePaths {
    @($PidFile, $LegacyPidFile) | Select-Object -Unique
}

function Read-KnownPid([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return 0
    }

    $knownPid = 0
    if ([int]::TryParse((Get-Content -LiteralPath $Path -Raw).Trim(), [ref]$knownPid) -and $knownPid -gt 0) {
        return $knownPid
    }
    return 0
}

function Test-ManagedServerProcess([int]$ProcessId) {
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if (-not $processInfo) {
        return $false
    }

    $commandLine = [string]$processInfo.CommandLine
    return ($commandLine -like "*server.PlainWebServer*" -or $commandLine -like "*server.WebServer*")
}

function Get-KnownServerPids {
    foreach ($path in (Get-PidFilePaths)) {
        $knownPid = Read-KnownPid $path
        if ($knownPid -gt 0 -and (Test-ManagedServerProcess $knownPid)) {
            $knownPid
        }
    }
}

function Stop-KnownServer {
    $stopped = @{}
    foreach ($path in (Get-PidFilePaths)) {
        $knownPid = Read-KnownPid $path
        if ($knownPid -le 0 -or $stopped.ContainsKey($knownPid)) {
            continue
        }

        $process = Get-Process -Id $knownPid -ErrorAction SilentlyContinue
        if ($process) {
            if (Test-ManagedServerProcess $knownPid) {
                Write-Host "停止上次由 deploy.ps1 启动的服务：PID=$knownPid"
                Stop-Process -Id $knownPid -Force -ErrorAction SilentlyContinue
                $stopped[$knownPid] = $true
                Start-Sleep -Milliseconds 800
            } elseif ($path -eq $PidFile) {
                Write-Warn "PID 文件指向的进程不是本项目服务，已跳过：PID=$knownPid"
            }
        }
    }
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
}

function Test-PortAvailable([int]$CheckPort) {
    $listeners = @(Get-NetTCPConnection -LocalPort $CheckPort -State Listen -ErrorAction SilentlyContinue)
    if ($listeners.Count -eq 0) {
        return
    }

    $knownPids = @(Get-KnownServerPids)
    $other = @($listeners | Where-Object { $knownPids -notcontains $_.OwningProcess })
    if ($other.Count -gt 0) {
        $owner = $other | Select-Object -First 1 -ExpandProperty OwningProcess
        $ownerName = (Get-Process -Id $owner -ErrorAction SilentlyContinue).ProcessName
        Fail "端口 $CheckPort 已被占用，PID=$owner（$ownerName）。请停止该程序或使用 -Port 指定其他端口。"
    }
}

function Wait-Http([string]$Url, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -eq 200) {
                return $response
            }
        } catch {
            Start-Sleep -Seconds 1
        }
    }
    return $null
}

function Wait-BoardRefresh([string]$BaseUrl, [int]$TimeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri "$BaseUrl/api/board/refresh" -Method Post -UseBasicParsing -TimeoutSec 8
            if ($response.StatusCode -eq 200) {
                $json = $response.Content | ConvertFrom-Json
                if ($json.code -eq 0) {
                    return $true
                }
            }
        } catch {
            Start-Sleep -Seconds 2
        }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Start-PlatformServer([string]$Java, [string]$Classpath, [int]$ListenPort) {
    $outLog = Join-Path $Root "server.run.out.log"
    $errLog = Join-Path $Root "server.run.err.log"
    $quotedClasspath = '"' + $Classpath.Replace('"', '\"') + '"'
    $arguments = @(
        "-Djava.net.preferIPv4Stack=true",
        "-cp", $quotedClasspath,
        "server.PlainWebServer",
        [string]$ListenPort
    )
    $process = Start-Process `
        -FilePath $Java `
        -ArgumentList $arguments `
        -WorkingDirectory $Root `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -WindowStyle Hidden `
        -PassThru

    New-Item -ItemType Directory -Force -Path $RuntimeDir | Out-Null
    Set-Content -LiteralPath $PidFile -Value $process.Id -Encoding ASCII
    $baseUrl = "http://localhost:$ListenPort"
    $homepageResponse = Wait-Http "$baseUrl/" 45
    if (-not $homepageResponse) {
        if (Get-Process -Id $process.Id -ErrorAction SilentlyContinue) {
            Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        }
        Fail "服务启动失败。请查看 $outLog 和 $errLog。"
    }
    return $process
}

function Add-FirewallRule([int]$ListenPort) {
    $displayName = "智慧农业平台 TCP $ListenPort"
    $existing = Get-NetFirewallRule -DisplayName $displayName -ErrorAction SilentlyContinue
    if (-not $existing) {
        New-NetFirewallRule `
            -DisplayName $displayName `
            -Direction Inbound `
            -Action Allow `
            -Protocol TCP `
            -LocalPort $ListenPort | Out-Null
    }
    Write-Ok "已允许 Windows 防火墙入站端口：$ListenPort"
}

Push-Location $Root
try {
    if ($Stop) {
        Write-Step "停止服务"
        Stop-KnownServer
        Write-Ok "服务已停止。"
        return
    }

    Write-Step "读取部署配置"
    $envFile = Join-Path $Root ".env.local"
    $fileValues = Read-EnvFile $envFile
    Set-ProcessEnvironment $fileValues
    Ensure-OptionalApiConfig $fileValues

    $dbPortOverride = if ($DatabasePort -gt 0) { [string]$DatabasePort } else { "" }
    $env:DB_HOST = Get-ConfigValue $fileValues "DB_HOST" $DatabaseHost "127.0.0.1"
    $env:DB_PORT = Get-ConfigValue $fileValues "DB_PORT" $dbPortOverride "3306"
    $env:DB_NAME = Get-ConfigValue $fileValues "DB_NAME" $DatabaseName "farm"
    $env:DB_USER = Get-ConfigValue $fileValues "DB_USER" $DatabaseUser "newuser"
    $env:DB_PASS = Get-ConfigValue $fileValues "DB_PASS" $DatabasePassword "123456"
    $env:DB_ADMIN_USER = Get-ConfigValue $fileValues "DB_ADMIN_USER" $DatabaseAdminUser $env:DB_USER
    $env:DB_ADMIN_PASS = Get-ConfigValue $fileValues "DB_ADMIN_PASS" $DatabaseAdminPassword $env:DB_PASS
    $boardConfig = Ensure-BoardConfig $fileValues $envFile $BoardIp $BoardPort
    $effectiveBoardIp = [string]$boardConfig.Ip
    $effectiveBoardPortText = [string]$boardConfig.PortText
    $effectiveBoardPort = [int]$boardConfig.Port

    $effectiveDbPort = 0
    if (-not [int]::TryParse($env:DB_PORT, [ref]$effectiveDbPort) -or $effectiveDbPort -lt 1 -or $effectiveDbPort -gt 65535) {
        Fail "DB_PORT 必须是 1 到 65535 的整数。"
    }
    $env:BOARD_PORT = [string]$effectiveBoardPort
    if ($effectiveBoardIp) { $env:BOARD_IP = $effectiveBoardIp }

    Write-Host "数据库：$($env:DB_HOST):$($env:DB_PORT)/$($env:DB_NAME)"
    if ($effectiveBoardIp) {
        Write-Host "开发板：$effectiveBoardIp`:$effectiveBoardPort"
    } else {
        Write-Warn "未配置板子 IP；开发板设备不会自动连通。"
    }

    Write-Step "检查项目文件"
    Resolve-RequiredFile (Join-Path $Root "pom.xml") "pom.xml" | Out-Null
    Resolve-RequiredFile (Join-Path $Root "mysql.sql") "mysql.sql" | Out-Null
    Resolve-RequiredFile (Join-Path $Root "frontend\index.html") "前端首页" | Out-Null
    Resolve-RequiredFile (Join-Path $Root "src\server\PlainWebServer.java") "Java 服务入口" | Out-Null
    Resolve-RequiredFile (Join-Path $Root "lib\mysql-connector-j-8.0.33.jar") "MySQL JDBC 驱动" | Out-Null

    $bundledJavaHome = Join-Path $Root "jdk-17.0.2"
    $selectedJavaHome = if ($JavaHome) { $JavaHome } elseif (Test-Path -LiteralPath (Join-Path $bundledJavaHome "bin\java.exe")) { $bundledJavaHome } elseif ($env:JAVA_HOME) { $env:JAVA_HOME } else { "" }
    if (-not $selectedJavaHome) {
        Fail "未找到项目 JDK 17。请保留项目中的 jdk-17.0.2，或使用 -JavaHome 指定 JDK 17 目录。"
    }
    $java = Resolve-RequiredFile (Join-Path $selectedJavaHome "bin\java.exe") "Java 可执行文件"
    $javac = Resolve-RequiredFile (Join-Path $selectedJavaHome "bin\javac.exe") "Java 编译器"
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $selectedJavaHome).Path
    $env:Path = "$(Split-Path $java -Parent);$env:Path"
    Write-Host "Java：$java"
    Write-Host (Get-JavaVersion $java)

    $maven = Find-Maven $MavenHome
    Write-Host "Maven：$maven"

    if (-not $SkipDatabaseCheck) {
        Write-Step "检查数据库 TCP 连通性"
        $dbTest = Test-NetConnection -ComputerName $env:DB_HOST -Port $effectiveDbPort -InformationLevel Quiet
        if (-not $dbTest) {
            if (Test-LocalDatabaseHost $env:DB_HOST) {
                Start-LocalMySqlInstallation $effectiveDbPort
                $dbTest = Test-NetConnection -ComputerName $env:DB_HOST -Port $effectiveDbPort -InformationLevel Quiet
            }
            if (-not $dbTest) {
                Fail ("无法连接 MySQL：$($env:DB_HOST):$effectiveDbPort。请检查 .env.local 中的 DB_HOST/DB_PORT、MySQL 服务状态、授权、网络和防火墙。" +
                    "`n如果只是验证前端和 Java 服务启动链路，可临时使用：.\deploy.ps1 -SkipDatabaseCheck")
            }
        }
        Write-Ok "数据库 TCP 端口可达。"
    }

    if ($InitializeDatabase) {
        if ($env:DB_NAME -ne "farm") {
            Fail "-InitializeDatabase 只能导入到 farm 数据库；请将 DB_NAME 设置为 farm。"
        }
        if (-not $Force) {
            Write-Warn "mysql.sql 是完整导出文件，会 DROP TABLE 后重建 14 张表并覆盖现有数据。"
            $answer = Read-Host "确认继续请输入 YES"
            if ($answer -cne "YES") {
                Fail "已取消数据库初始化。"
            }
        }

        Write-Step "导入 mysql.sql"
        $mysql = Find-Mysql $MysqlExe
        Invoke-MySqlFile $mysql (Join-Path $Root "mysql.sql")
        Write-Ok "mysql.sql 导入完成。"

        if ($effectiveBoardIp) {
            Write-Step "更新数据库中的开发板地址"
            Update-BoardDeviceAddresses $mysql $effectiveBoardIp $effectiveBoardPort
            Write-Ok "已将非摄像头设备指向 $effectiveBoardIp`:$effectiveBoardPort。"
        }
    } elseif ($effectiveBoardIp) {
        Write-Step "更新数据库中的开发板地址"
        $mysql = Find-Mysql $MysqlExe
        Update-BoardDeviceAddresses $mysql $effectiveBoardIp $effectiveBoardPort
        Write-Ok "已将非摄像头设备指向 $effectiveBoardIp`:$effectiveBoardPort。"
    }

    Write-Step "构建 Java 后端"
    & $maven "-DskipTests" "package" "-Dstyle.color=never"
    if ($LASTEXITCODE -ne 0) {
        Fail "Maven 构建失败，返回码：$LASTEXITCODE"
    }

    & $maven "dependency:build-classpath" "-Dmdep.outputFile=target\classpath.txt" "-Dmdep.outputAbsoluteArtifactFilename=true" "-DincludeScope=runtime" "-Dstyle.color=never"
    if ($LASTEXITCODE -ne 0) {
        Fail "生成运行时 classpath 失败，返回码：$LASTEXITCODE"
    }

    Resolve-RequiredFile (Join-Path $Root "target\classes\server\PlainWebServer.class") "编译后的服务类" | Out-Null
    $classpathFile = Resolve-RequiredFile (Join-Path $Root "target\classpath.txt") "运行时 classpath 文件"
    $classpath = (Resolve-Path (Join-Path $Root "target\classes")).Path + ";" + (Get-Content -LiteralPath $classpathFile -Raw).Trim()
    Write-Ok "Java 后端构建完成。"

    Stop-KnownServer
    Test-PortAvailable $Port

    Write-Step "启动 Java 服务"
    $serverProcess = Start-PlatformServer $java $classpath $Port
    $baseUrl = "http://localhost:$Port"
    Write-Ok "服务已监听：$baseUrl/"

    if (-not $SkipDatabaseCheck) {
        Write-Step "检查数据库 API"
        $plots = Wait-Http "$baseUrl/api/plots" 45
        if (-not $plots) {
            Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
            Fail "首页可访问，但 /api/plots 未通过。请检查数据库表结构、账号权限及服务日志。"
        }
        try {
            $json = $plots.Content | ConvertFrom-Json
            if ($json.code -ne 0) {
                throw "API 返回 code=$($json.code)"
            }
        } catch {
            Stop-Process -Id $serverProcess.Id -Force -ErrorAction SilentlyContinue
            Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
            Fail "数据库 API 返回异常：$($_.Exception.Message)"
        }
        Write-Ok "/api/plots 返回 code=0。"
    }

    if ($effectiveBoardIp) {
        Write-Step "连接开发板"
        $boardTcpOk = Test-NetConnection -ComputerName $effectiveBoardIp -Port $effectiveBoardPort -InformationLevel Quiet -WarningAction SilentlyContinue
        if ($boardTcpOk) {
            Write-Ok "开发板 TCP 端口可达：$effectiveBoardIp`:$effectiveBoardPort"
            if (Wait-BoardRefresh $baseUrl 20) {
                Write-Ok "服务端已触发开发板刷新，开始通过长连接采集数据。"
            } else {
                Write-Warn "开发板端口可达，但暂时没有拿到有效读数。请确认开发板固件已运行，且当前没有其他 TCP 客户端占用连接。"
            }
        } else {
            Write-Warn "无法连接开发板：$effectiveBoardIp`:$effectiveBoardPort。请确认板子已上电、已连接 Wi-Fi、端口监听正常，并且电脑和板子在同一网络。"
        }
    }

    if ($OpenFirewall) {
        Write-Step "配置 Windows 防火墙"
        Add-FirewallRule $Port
    }

    if ($StartDetectionWorker) {
        Write-Step "启动 YOLO 人体识别 Worker"
        $workerScript = Resolve-RequiredFile (Join-Path $Root "workers\start_human_detection.ps1") "人体识别启动脚本"
        Ensure-DetectionPythonEnvironment | Out-Null
        Resolve-RequiredFile (Join-Path $Root "models\yolov8n.pt") "YOLO 模型" | Out-Null
        $workerOut = Join-Path $Root "worker.run.out.log"
        $workerErr = Join-Path $Root "worker.run.err.log"
        $worker = Start-Process `
            -FilePath "powershell.exe" `
            -ArgumentList @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $workerScript, "-ServerUrl", $baseUrl) `
            -WorkingDirectory $Root `
            -RedirectStandardOutput $workerOut `
            -RedirectStandardError $workerErr `
            -WindowStyle Hidden `
            -PassThru
        Write-Ok "YOLO Worker 已启动，PID=$($worker.Id)。"
    }

    Write-Host ""
    Write-Host "部署成功。" -ForegroundColor Green
    Write-Host "访问地址：http://localhost:$Port/?simulator=0"
    Write-Host "服务 PID：$($serverProcess.Id)"
    Write-Host "标准输出：$(Join-Path $Root "server.run.out.log")"
    Write-Host "错误输出：$(Join-Path $Root "server.run.err.log")"
} catch {
    Write-Host ""
    Write-Host "[ERROR] 部署失败：$($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    Pop-Location
}
