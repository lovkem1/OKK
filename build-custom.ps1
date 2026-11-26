# OKK 定制版一键构建（6 项消息保护功能 · 微信 8.0.79）
# 与仓库自带 pack.ps1 的区别：自动探测 Android SDK / adb，不依赖作者本机硬编码路径。
#
# 用法（PowerShell）:
#   .\build-custom.ps1                 # 仅构建
#   .\build-custom.ps1 -Install        # 构建后 adb 安装并强停微信
#   .\build-custom.ps1 -Debug          # 构建 debug 包（较快，便于排查）
# 也可用环境变量提供签名（推荐，生成 release 签名包）:
#   $env:OKK_STORE_FILE="C:\keys\okk.jks"
#   $env:OKK_STORE_PASSWORD="..."
#   $env:OKK_KEY_ALIAS="okk"
#   $env:OKK_KEY_PASSWORD="..."
param(
    [switch]$Install,
    [switch]$Debug
)

$ErrorActionPreference = "Stop"
$Root = $PSScriptRoot
$OutDir = Join-Path $Root "APK-custom"
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force -Path $OutDir | Out-Null }

# ── 环境预检 ────────────────────────────────────────────────────
$javaCmd = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaCmd) { throw "未找到 java，请安装 JDK 17+ 并加入 PATH" }
$jv = (& java -version 2>&1 | Select-Object -First 1).ToString()
Write-Host "==> Java: $jv"

if (-not $env:ANDROID_HOME -and -not $env:ANDROID_SDK_ROOT) {
    Write-Warning "ANDROID_HOME / ANDROID_SDK_ROOT 未设置，尝试用 ANDROID_SDK_HOME 或常见路径"
}
$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = $env:ANDROID_SDK_HOME }
if (-not $sdk -and (Test-Path "C:\Android")) { $sdk = "C:\Android" }
if (-not $sdk) { throw "未找到 Android SDK，请设置 ANDROID_HOME 指向 SDK 目录（需 API 37）" }
Write-Host "==> Android SDK: $sdk"

# local.properties（gradlew 需要）
$lp = Join-Path $Root "local.properties"
$sdkPath = $sdk -replace '\\', '/'
"sdk.dir=$sdkPath" | Set-Content -Path $lp -Encoding ascii
Write-Host "==> wrote local.properties"

Push-Location $Root
try {
    if ($Debug) {
        $task = ":app:assembleDebug"
        $srcRel = "app\build\outputs\apk\debug\app-debug.apk"
    } else {
        $task = ":app:assembleRelease -x lintVitalRelease -x lintVitalAnalyzeRelease"
        $srcRel = "app\build\outputs\apk\release\app-release.apk"
    }

    Write-Host "==> gradlew $task"
    if ($IsWindows -or $env:OS -eq "Windows_NT") {
        & .\gradlew.bat $task.Split()
    } else {
        & .\gradlew $task.Split()
    }
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed: $LASTEXITCODE" }

    $apk = Join-Path $Root $srcRel
    if (-not (Test-Path $apk)) {
        $dir = Split-Path $srcRel -Parent
        $apk = Get-ChildItem (Join-Path $Root $dir) -Filter "*.apk" |
            Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $apk -or -not (Test-Path $apk)) { throw "apk not found at $srcRel" }

    $stamp = Get-Date -Format "yyyyMMdd-HHmm"
    $flavor = if ($Debug) { "debug" } else { "release" }
    $named = Join-Path $OutDir "OKK-Custom-6Features-$flavor-$stamp.apk"
    Copy-Item $apk $named -Force
    $len = (Get-Item $named).Length
    Write-Host "OK  $named"
    Write-Host "    size = $([math]::Round($len/1MB, 2)) MB"

    if ($Install) {
        $adb = Get-Command adb -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
        if (-not $adb -and (Test-Path "$sdk\platform-tools\adb.exe")) { $adb = "$sdk\platform-tools\adb.exe" }
        if (-not $adb) { throw "未找到 adb，请安装 platform-tools 或加入 PATH" }
        Write-Host "==> adb install -r"
        & $adb install -r $named
        if ($LASTEXITCODE -ne 0) { throw "adb install failed: $LASTEXITCODE" }
        & $adb shell am force-stop com.tencent.mm
        Write-Host "installed + 微信已强停，请重新打开微信使模块生效。"
    }
}
catch {
    Write-Error $_
    exit 1
}
finally {
    Pop-Location
}
