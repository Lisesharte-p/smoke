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
$PidFile = Join-Path $Root ".smoke-server.pid"

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

function Stop-KnownServer {
    if (-not (Test-Path -LiteralPath $PidFile -PathType Leaf)) {
        return
    }

    $knownPid = 0
    $pidText = (Get-Content -LiteralPath $PidFile -Raw).Trim()
    if ([int]::TryParse($pidText, [ref]$knownPid) -and $knownPid -gt 0) {
        $process = Get-Process -Id $knownPid -ErrorAction SilentlyContinue
        if ($process) {
            Write-Host "停止上次由 deploy.ps1 启动的服务：PID=$knownPid"
            Stop-Process -Id $knownPid -Force -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 800
        }
    }
    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
}

function Test-PortAvailable([int]$CheckPort) {
    $listeners = @(Get-NetTCPConnection -LocalPort $CheckPort -State Listen -ErrorAction SilentlyContinue)
    if ($listeners.Count -eq 0) {
        return
    }

    $knownPid = 0
    if (Test-Path -LiteralPath $PidFile -PathType Leaf) {
        [int]::TryParse((Get-Content -LiteralPath $PidFile -Raw).Trim(), [ref]$knownPid) | Out-Null
    }
    $other = @($listeners | Where-Object { $_.OwningProcess -ne $knownPid })
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

    $dbPortOverride = if ($DatabasePort -gt 0) { [string]$DatabasePort } else { "" }
    $boardPortOverride = if ($BoardPort -gt 0) { [string]$BoardPort } else { "" }
    $env:DB_HOST = Get-ConfigValue $fileValues "DB_HOST" $DatabaseHost "127.0.0.1"
    $env:DB_PORT = Get-ConfigValue $fileValues "DB_PORT" $dbPortOverride "3306"
    $env:DB_NAME = Get-ConfigValue $fileValues "DB_NAME" $DatabaseName "farm"
    $env:DB_USER = Get-ConfigValue $fileValues "DB_USER" $DatabaseUser "root"
    $env:DB_PASS = Get-ConfigValue $fileValues "DB_PASS" $DatabasePassword "123456"
    $env:DB_ADMIN_USER = Get-ConfigValue $fileValues "DB_ADMIN_USER" $DatabaseAdminUser $env:DB_USER
    $env:DB_ADMIN_PASS = Get-ConfigValue $fileValues "DB_ADMIN_PASS" $DatabaseAdminPassword $env:DB_PASS
    $effectiveBoardIp = Get-ConfigValue $fileValues "BOARD_IP" $BoardIp ""
    $effectiveBoardPortText = Get-ConfigValue $fileValues "BOARD_PORT" $boardPortOverride "8888"

    $effectiveDbPort = 0
    $effectiveBoardPort = 0
    if (-not [int]::TryParse($env:DB_PORT, [ref]$effectiveDbPort) -or $effectiveDbPort -lt 1 -or $effectiveDbPort -gt 65535) {
        Fail "DB_PORT 必须是 1 到 65535 的整数。"
    }
    if (-not [int]::TryParse($effectiveBoardPortText, [ref]$effectiveBoardPort) -or $effectiveBoardPort -lt 1 -or $effectiveBoardPort -gt 65535) {
        Fail "BOARD_PORT 或 -BoardPort 必须是 1 到 65535 的整数。"
    }
    $env:BOARD_PORT = [string]$effectiveBoardPort
    if ($effectiveBoardIp) { $env:BOARD_IP = $effectiveBoardIp }

    Write-Host "数据库：$($env:DB_HOST):$($env:DB_PORT)/$($env:DB_NAME)"
    if ($effectiveBoardIp) {
        Write-Host "开发板：$effectiveBoardIp`:$effectiveBoardPort"
    } else {
        Write-Warn "未配置 BOARD_IP；数据库初始化后不会自动修改 mysql.sql 中的设备地址。"
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
    & $java -version 2>&1 | Select-Object -First 1

    $maven = Find-Maven $MavenHome
    Write-Host "Maven：$maven"

    if (-not $SkipDatabaseCheck) {
        Write-Step "检查数据库 TCP 连通性"
        $dbTest = Test-NetConnection -ComputerName $env:DB_HOST -Port $effectiveDbPort -InformationLevel Quiet
        if (-not $dbTest) {
            Fail "无法连接 MySQL：$($env:DB_HOST):$effectiveDbPort。请检查数据库授权、网络和防火墙。"
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
            if ($effectiveBoardIp -notmatch "^[A-Za-z0-9][A-Za-z0-9.-]*$") {
                Fail "BOARD_IP 或 -BoardIp 含有不支持的字符：$effectiveBoardIp"
            }
            Write-Step "更新数据库中的开发板地址"
            $safeBoardIp = $effectiveBoardIp.Replace("'", "''")
            $sql = "USE farm; UPDATE device SET ip='$safeBoardIp', port=$effectiveBoardPort, online=0 WHERE type <> '摄像头';"
            Invoke-MySqlText $mysql $sql
            Write-Ok "已将非摄像头设备指向 $effectiveBoardIp`:$effectiveBoardPort。"
        }
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

    if ($OpenFirewall) {
        Write-Step "配置 Windows 防火墙"
        Add-FirewallRule $Port
    }

    if ($StartDetectionWorker) {
        Write-Step "启动 YOLO 人体识别 Worker"
        $workerScript = Resolve-RequiredFile (Join-Path $Root "workers\start_human_detection.ps1") "人体识别启动脚本"
        Resolve-RequiredFile (Join-Path $Root ".venv-detection\Scripts\python.exe") "人体识别 Python 环境" | Out-Null
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
} finally {
    Pop-Location
}
