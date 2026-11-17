#requires -Version 5.1
<#
.SYNOPSIS
    使用 Android SDK build-tools 直接构建 ClipBalFix（无需 Gradle）。

.PARAMETER AndroidSdk
    Android SDK 根目录，默认取 $env:ANDROID_HOME。

.PARAMETER Keystore
    JKS 签名文件路径。不存在时会自动生成一个测试签名。

.EXAMPLE
    .\tools\build.ps1
#>
param(
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$Keystore = "",
    [string]$StorePass = "android",
    [string]$KeyAlias = "clipbal",
    [string]$KeyPass = "android",
    [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
if (-not $OutDir) { $OutDir = Join-Path $root "dist" }
if (-not $Keystore) { $Keystore = Join-Path $root "keystore\release.jks" }

if (-not $AndroidSdk) {
    $candidate = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    if (Test-Path $candidate) { $AndroidSdk = $candidate }
}
if (-not $AndroidSdk -or -not (Test-Path $AndroidSdk)) {
    throw "未找到 Android SDK，请设置 ANDROID_HOME 或传入 -AndroidSdk"
}

$buildTools = Get-ChildItem (Join-Path $AndroidSdk "build-tools") -Directory |
    Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
if (-not $buildTools) { throw "未找到 build-tools" }
$bt = $buildTools.FullName

$platforms = Get-ChildItem (Join-Path $AndroidSdk "platforms") -Directory |
    Sort-Object {
        $v = $_.Name -replace '^android-', ''
        $p = $v.Split('.')
        [int]$p[0] * 1000 + $(if ($p.Count -gt 1) { [int]$p[1] } else { 0 })
    } -Descending
$androidJar = Join-Path $platforms[0].FullName "android.jar"
if (-not (Test-Path $androidJar)) { throw "未找到 android.jar" }

$javaHome = $env:JAVA_HOME
if (-not $javaHome) { throw "请设置 JAVA_HOME（JDK 17+）" }
$javac = Join-Path $javaHome "bin\javac.exe"
$keytool = Join-Path $javaHome "bin\keytool.exe"

# libxposed API（编译期依赖，不打进 APK）
$apiJar = Join-Path $root "libs\libxposed-api-102.jar"
if (-not (Test-Path $apiJar)) {
    Write-Host "未找到 $apiJar，尝试从 Maven Central 下载..." -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path (Split-Path $apiJar) | Out-Null
    $aar = Join-Path $env:TEMP "libxposed-api-102.aar"
    Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar" -OutFile $aar -UseBasicParsing
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($aar)
    $entry = $zip.Entries | Where-Object { $_.FullName -eq "classes.jar" }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $apiJar, $true)
    $zip.Dispose()
}

$src = Join-Path $root "app\src\main\java"
$res = Join-Path $root "app\src\main\res"
$manifest = Join-Path $root "app\src\main\AndroidManifest.xml"
$metaDir = Join-Path $root "app\src\main\resources\META-INF\xposed"

$work = Join-Path $root "build"
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$work\classes","$work\dex" | Out-Null

Write-Host "==> javac" -ForegroundColor Cyan
$sources = Get-ChildItem $src -Recurse -Filter *.java | ForEach-Object FullName
& $javac -source 8 -target 8 -nowarn -encoding UTF-8 -classpath "$androidJar;$apiJar" -d "$work\classes" $sources
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Host "==> d8" -ForegroundColor Cyan
$classes = Get-ChildItem "$work\classes" -Recurse -Filter *.class | ForEach-Object FullName
& (Join-Path $bt "d8.bat") --release --min-api 34 --lib $androidJar --lib $apiJar --output "$work\dex" $classes
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

Write-Host "==> aapt package" -ForegroundColor Cyan
# Gradle 工程里 package 由 namespace 提供；aapt1 需要显式 package 属性，构建时生成临时清单
$tmpManifest = Join-Path $work "AndroidManifest.xml"
(Get-Content $manifest -Raw) -replace '<manifest ', '<manifest package="com.clipbal.fix" android:versionCode="4" android:versionName="2.2" ' |
    Set-Content -Encoding UTF8 $tmpManifest
$baseApk = Join-Path $work "base.apk"
Push-Location (Split-Path $manifest)
& (Join-Path $bt "aapt.exe") package -f -M $tmpManifest -S $res -I $androidJar -F $baseApk
Pop-Location
if ($LASTEXITCODE -ne 0) { throw "aapt 失败" }

Write-Host "==> 打包 META-INF/xposed" -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($baseApk, 'Update')
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$work\dex\classes.dex", "classes.dex") | Out-Null
foreach ($f in @("module.prop", "java_init.list", "scope.list")) {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $metaDir $f), "META-INF/xposed/$f") | Out-Null
}
$zip.Dispose()

Write-Host "==> zipalign" -ForegroundColor Cyan
$aligned = Join-Path $work "aligned.apk"
& (Join-Path $bt "zipalign.exe") -f -p 4 $baseApk $aligned

Write-Host "==> 签名" -ForegroundColor Cyan
if (-not (Test-Path $Keystore)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $Keystore) | Out-Null
    Write-Host "生成测试签名: $Keystore" -ForegroundColor Yellow
    & $keytool -genkeypair -keystore $Keystore -alias $KeyAlias -keyalg RSA -keysize 2048 `
        -validity 3650 -storepass $StorePass -keypass $KeyPass -dname "CN=ClipBalFix"
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$outApk = Join-Path $OutDir "ClipBalFix-2.2.apk"
& (Join-Path $bt "apksigner.bat") sign --ks $Keystore --ks-pass "pass:$StorePass" --key-pass "pass:$KeyPass" --out $outApk $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败" }

Write-Host "完成: $outApk" -ForegroundColor Green
