# Builds the Music app with the release key and gathers what a GitHub release needs in dist\:
# music-player.apk and the install scripts.
#   & .\release.ps1
# The key comes from RELEASE_KEYSTORE / RELEASE_KEYSTORE_PASS when set (as in CI), otherwise from the files below.
param(
    [string] $Keystore = "$env:USERPROFILE\.android\pixel-watch-release.jks",
    [string] $PasswordFile = "$env:USERPROFILE\.android\pixel-watch-release-password.txt"
)
$ErrorActionPreference = 'Stop'
if (-not $env:RELEASE_KEYSTORE) { $env:RELEASE_KEYSTORE = $Keystore }
if (-not $env:RELEASE_KEYSTORE_PASS) { $env:RELEASE_KEYSTORE_PASS = (Get-Content -Raw $PasswordFile).Trim() }

$dist = "$PSScriptRoot\dist"
Remove-Item -Recurse -Force $dist -ErrorAction SilentlyContinue
New-Item -ItemType Directory $dist | Out-Null
& "$PSScriptRoot\build-apk.ps1" -Project "$PSScriptRoot\music-player" -Release
Copy-Item "$PSScriptRoot\music-player\music-player-release.apk" "$dist\music-player.apk"
Copy-Item "$PSScriptRoot\install.ps1", "$PSScriptRoot\install.sh" $dist
Write-Host "Release files in $dist"
