# Builds music-player.apk (debug key; -Release for the release key). See ..\build-apk.ps1.
param([switch] $Release)
& "$PSScriptRoot\..\build-apk.ps1" -Project $PSScriptRoot -Release:$Release
