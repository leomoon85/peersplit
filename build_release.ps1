# PeerSplit - Automated Release Build Script for Google Play Store
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " PeerSplit - Google Play Release App Bundle (.aab) Builder" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$keystoreFile = "peersplit-release.jks"
$keystoreAlias = "peersplit"

if (-not (Test-Path $keystoreFile)) {
    Write-Host "`n[!] Release keystore '$keystoreFile' not found." -ForegroundColor Yellow
    Write-Host "Generating a new release keystore for Google Play Store..." -ForegroundColor Green
    
    $keytool = (Get-Command keytool -ErrorAction SilentlyContinue).Source
    if ($keytool) {
        & keytool -genkeypair -v `
            -keystore $keystoreFile `
            -alias $keystoreAlias `
            -keyalg RSA `
            -keysize 2048 `
            -validity 10000 `
            -storepass "peersplit123" `
            -keypass "peersplit123" `
            -dname "CN=PeerSplit Developer, OU=Mobile, O=PeerSplit, L=City, ST=State, C=US"
        Write-Host "[✓] Created release keystore: $keystoreFile" -ForegroundColor Green
    } else {
        Write-Host "[!] Note: keytool is available with JDK installation. You can also generate your keystore inside Android Studio -> Build -> Generate Signed Bundle / APK." -ForegroundColor Yellow
    }
}

Write-Host "`nExecuting Gradle Release Build..." -ForegroundColor Cyan
if (Test-Path ".\gradlew.bat") {
    .\gradlew.bat bundleRelease
} else {
    Write-Host "Open this project in Android Studio and select Build -> Generate Signed Bundle." -ForegroundColor Yellow
}

$aabPath = "app\build\outputs\bundle\release\app-release.aab"
if (Test-Path $aabPath) {
    Write-Host "`n[SUCCESS] Release Bundle ready for Google Play Store!" -ForegroundColor Green
    Write-Host "Path: $aabPath" -ForegroundColor White
}
