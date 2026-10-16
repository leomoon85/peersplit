# PeerSplit - One-Click Device Runner
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$apk = "$env:USERPROFILE\Desktop\PeerSplit.apk"

Write-Host "=============================================" -ForegroundColor Cyan
Write-Host "   PeerSplit - Android Device Runner         " -ForegroundColor Cyan
Write-Host "=============================================" -ForegroundColor Cyan

if (-not (Test-Path $adb)) {
    Write-Host "[ERROR] ADB not found at $adb" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path $apk)) {
    Write-Host "[ERROR] APK not found at $apk. Please build first." -ForegroundColor Red
    exit 1
}

Write-Host "`nChecking for connected Android devices via USB..." -ForegroundColor Yellow
$devices = & $adb devices | Select-String -Pattern "device$"

if (-not $devices) {
    Write-Host "`n[!] No Android device detected yet." -ForegroundColor Yellow
    Write-Host "Please do the following:" -ForegroundColor White
    Write-Host "  1. Connect your Android phone to this PC via USB cable." -ForegroundColor White
    Write-Host "  2. On your phone: Settings -> About Phone -> tap 'Build Number' 7 times." -ForegroundColor White
    Write-Host "  3. Go to System / Developer Options -> enable 'USB Debugging'." -ForegroundColor White
    Write-Host "  4. On the phone screen pop-up, tap 'Allow USB debugging'." -ForegroundColor White
    Write-Host "`nWaiting for device connection (Press Ctrl+C to cancel)..." -ForegroundColor Cyan

    while (-not $devices) {
        Start-Sleep -Seconds 2
        $devices = & $adb devices | Select-String -Pattern "device$"
    }
}

Write-Host "`n[✓] Connected device detected!" -ForegroundColor Green
Write-Host "Installing PeerSplit.apk to your phone..." -ForegroundColor Cyan

& $adb install -r -d $apk

if ($LASTEXITCODE -eq 0) {
    Write-Host "`n[✓] Installation Successful!" -ForegroundColor Green
    Write-Host "Launching PeerSplit on your device..." -ForegroundColor Cyan
    & $adb shell am start -n "com.peersplit.app/com.peersplit.app.MainActivity"
    Write-Host "`nPeerSplit is now running on your phone! Enjoy!" -ForegroundColor Green
} else {
    Write-Host "`n[ERROR] Installation failed. Check device screen for permission prompts." -ForegroundColor Red
}
