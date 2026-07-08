<#
.SYNOPSIS
    Build the vram-tweak mod jar via Gradle.
.DESCRIPTION
    1. Runs Gradle build (clean + build) to produce the final mod jar
    2. Prints the output jar path and size
    VRAM detection uses pure OpenGL extensions — no native DLL needed.
#>

$ErrorActionPreference = "Stop"
$ScriptPath = Split-Path -Parent $PSCommandPath
Set-Location $ScriptPath

# ---- Step 1: Gradle build ----
Write-Host "[gradle] Running Gradle build ..." -ForegroundColor Cyan
$gradleExit = 0
if ($IsWindows -or $env:OS) {
    & ".\gradlew.bat" clean build 2>&1 | ForEach-Object { Write-Host "  $_" }
    $gradleExit = $LASTEXITCODE
} else {
    & ".\gradlew" clean build 2>&1 | ForEach-Object { Write-Host "  $_" }
    $gradleExit = $LASTEXITCODE
}

if ($gradleExit -ne 0) {
    Write-Host "[gradle] BUILD FAILED (exit code $gradleExit)" -ForegroundColor Red
    exit $gradleExit
}

# ---- Step 2: Show result ----
$jars = Get-ChildItem "build/libs/*.jar" -Exclude "*sources*" | Sort-Object LastWriteTime -Descending
if ($jars) {
    $jar = $jars[0]
    $size = [math]::Round($jar.Length / 1024)
    Write-Host "`n[result] $($jar.Name) ($size KB)" -ForegroundColor Green
    Write-Host "  VRAM: pure GL extensions (no native DLL)" -ForegroundColor Cyan
} else {
    Write-Host "[result] No jar found in build/libs" -ForegroundColor Red
}
