# Installs music-player.apk (in this folder) on a Wear OS watch over Wi-Fi.
# Right-click > Run with PowerShell, or:  powershell -ExecutionPolicy Bypass -File install.ps1
# Needs: the watch and this computer on the same Wi-Fi. adb is downloaded from Google if it isn't installed.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue' # Invoke-WebRequest is far faster without the progress bar
Set-Location $PSScriptRoot

function Ask([string] $question) {
    (Read-Host $question).Trim()
}

function Fail([string] $message) {
    Write-Host "`n$message" -ForegroundColor Red
    Read-Host 'Press Enter to close'
    exit 1
}

if (-not (Test-Path 'music-player.apk')) { Fail "music-player.apk isn't in this folder." }

# --- adb: one on PATH, or Google's platform-tools fetched next to this script ---
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
    $adb = "$PSScriptRoot\platform-tools\adb.exe"
    if (-not (Test-Path $adb)) {
        Write-Host "Downloading Google's platform-tools (adb)..."
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $zip = "$env:TEMP\platform-tools.zip"
        Invoke-WebRequest 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
        Expand-Archive $zip $PSScriptRoot -Force
        Remove-Item $zip
    }
}
# From here on adb does the work; Windows PowerShell would treat its stderr output as a fatal error under 'Stop'.
$ErrorActionPreference = 'Continue'

# --- connect to the watch ---
Write-Host @'

On the watch:
  1. Settings > System > About > Versions: tap "Build number" 7 times to turn on Developer options.
  2. Settings > Developer options: turn on "ADB debugging", then "Wireless debugging".
  3. In Wireless debugging, tap "Pair new device". Keep that screen open.
'@
$pairAddress = Ask 'IP address & port shown under "Pair new device" (e.g. 192.168.1.20:37123)'
$pairCode = Ask 'Wi-Fi pairing code shown on the watch'
& $adb pair $pairAddress $pairCode
if ($LASTEXITCODE) { Fail 'Pairing failed. Check the address and code, and that both are on the same Wi-Fi.' }

$ip = $pairAddress.Split(':')[0]
Write-Host "`nNow go back one screen. Wireless debugging shows ""IP address & port"" (a different port)."
$port = Ask "That port (the number after $ip`:)"
$device = "${ip}:$port"
& $adb connect $device | Out-Host
if ((& $adb -s $device get-state 2>$null) -ne 'device') { Fail "Couldn't connect to $device." }

# --- install ---
Write-Host "`nInstalling Music..."
$result = & $adb -s $device install -r 'music-player.apk' 2>&1 | Out-String
Write-Host $result.Trim()
if ($result -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
    Fail 'A copy signed with a different key is installed. Uninstall it on the watch first, then run this again.'
}
if ($result -notmatch 'Success') { Fail 'Installing failed.' }
# The app asks for these itself on first launch; granting them here just saves the taps.
foreach ($p in 'READ_MEDIA_AUDIO', 'POST_NOTIFICATIONS') {
    & $adb -s $device shell pm grant dev.watchmusic "android.permission.$p" | Out-Host
}

Write-Host "`nDone: open Music on the watch. You can turn Wireless debugging off again; it uses battery." -ForegroundColor Green
Read-Host 'Press Enter to close'
