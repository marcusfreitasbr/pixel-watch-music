# adb fallback for the Add Music page: copies a PC folder to /sdcard/Music on the watch, then indexes it.
# Pushes new or changed audio files. With -Mirror it also removes watch files that aren't in the folder.
#   & .\music-player\sync-music.ps1 -Device <watch-ip>:<port>
param(
    [string] $Source = "$env:USERPROFILE\Music\Watch",
    [string] $Device, # adb serial; needed when adb lists the watch twice (IP and mDNS name)
    [switch] $DryRun, # show what would change without changing anything
    [switch] $Mirror  # also delete watch songs that aren't in $Source, including ones added via the Add Music page
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8 # adb prints UTF-8; folder names may hold emoji
$adb = @(if ($Device) { '-s', $Device })
$remoteRoot = '/sdcard/Music'
$audio = '.mp3', '.m4a', '.aac', '.flac', '.ogg', '.opus', '.wav'

$Source = (Resolve-Path $Source).Path.TrimEnd('\')
$local = @{}
Get-ChildItem -Recurse -File $Source | Where-Object { $audio -contains $_.Extension.ToLower() } | ForEach-Object {
    $local[$_.FullName.Substring($Source.Length + 1).Replace('\', '/')] = $_
}

# Remote listing as "size|path" lines.
$remote = @{}
& adb @adb shell "find $remoteRoot -type f -not -path '*/.*' -exec stat -c '%s|%n' {} +" | ForEach-Object {
    $size, $path = $_ -split '\|', 2
    if ($path) { $remote[$path.Substring($remoteRoot.Length + 1)] = [long]$size }
}

$toPush = @($local.Keys | Where-Object { $remote[$_] -ne $local[$_].Length } | Sort-Object)
$toDelete = @(if ($Mirror) { $remote.Keys | Where-Object { -not $local.ContainsKey($_) } | Sort-Object })
Write-Host "$($local.Count) songs in $Source. To push: $($toPush.Count). To remove from watch: $($toDelete.Count)."
$toDelete | ForEach-Object { Write-Host "  - $_" }
if ($DryRun) { return }

$i = 0
foreach ($rel in $toPush) {
    $i++
    Write-Host "[$i/$($toPush.Count)] $rel"
    & adb @adb push $local[$rel].FullName "$remoteRoot/$rel" | Out-Null
    if ($LASTEXITCODE) { throw "adb push failed: $rel" }
}

if ($toDelete) {
    # Paths can hold quotes and spaces, and PowerShell strips quotes inside `adb shell "..."`,
    # so push the list and a script that reads it.
    $tmp = New-Item -ItemType Directory (Join-Path ([IO.Path]::GetTempPath()) "music-sync-$PID")
    [IO.File]::WriteAllText("$tmp\delete.txt", (($toDelete | ForEach-Object { "$remoteRoot/$_" }) -join "`n") + "`n")
    [IO.File]::WriteAllText("$tmp\delete.sh", @"
while IFS= read -r f; do rm -f "`$f"; done < /data/local/tmp/music-delete.txt
rm /data/local/tmp/music-delete.txt /data/local/tmp/music-delete.sh
find $remoteRoot -mindepth 1 -type d -empty -not -path '*/.*' -delete
"@)
    & adb @adb push "$tmp\delete.txt" /data/local/tmp/music-delete.txt | Out-Null
    & adb @adb push "$tmp\delete.sh" /data/local/tmp/music-delete.sh | Out-Null
    & adb @adb shell sh /data/local/tmp/music-delete.sh
    Remove-Item -Recurse $tmp
}

if ($toPush -or $toDelete) {
    # adb push doesn't fill in tags (artist, album, duration); a volume scan does.
    & adb @adb shell content call --uri content://media --method scan_volume --arg external_primary | Out-Null
    Write-Host "Synced and indexed. Reopen Music on the watch to see the changes."
} else {
    Write-Host "Already in sync."
}
