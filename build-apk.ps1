# Builds one app's APK without Gradle: aapt2 -> javac -> d8 -> zipalign -> apksigner.
# Signs with the Android debug key into <app>.apk, or with -Release into <app>-release.apk (kept apart, so a
# release build is never installed over a debug one by mistake) using the key in these environment variables:
#   RELEASE_KEYSTORE       path to the .jks
#   RELEASE_KEYSTORE_PASS  its password (also the key's)
#   RELEASE_KEY_ALIAS      optional, default "release"
#   & .\build-apk.ps1 -Project .\music-player [-Release]
param(
    [Parameter(Mandatory)] [string] $Project, # folder holding AndroidManifest.xml, res and src
    [switch] $Release
)
$ErrorActionPreference = 'Stop'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$bt = "$sdk\build-tools\36.0.0"
$jar = "$sdk\platforms\android-36.1\android.jar"
$root = (Resolve-Path $Project).Path
$name = Split-Path $root -Leaf
$out = "$root\build"

if ($Release) {
    if (-not $env:RELEASE_KEYSTORE -or -not $env:RELEASE_KEYSTORE_PASS) {
        throw '-Release needs RELEASE_KEYSTORE and RELEASE_KEYSTORE_PASS'
    }
    $alias = if ($env:RELEASE_KEY_ALIAS) { $env:RELEASE_KEY_ALIAS } else { 'release' }
    # env: makes apksigner read the password itself, so it never appears on a command line
    $sign = '--ks', $env:RELEASE_KEYSTORE, '--ks-pass', 'env:RELEASE_KEYSTORE_PASS',
        '--ks-key-alias', $alias, '--key-pass', 'env:RELEASE_KEYSTORE_PASS'
} else {
    $sign = '--ks', "$env:USERPROFILE\.android\debug.keystore", '--ks-pass', 'pass:android',
        '--ks-key-alias', 'androiddebugkey', '--key-pass', 'pass:android'
}

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force "$out\classes", "$out\dex", "$out\gen" | Out-Null

& "$bt\aapt2.exe" compile --dir "$root\res" -o "$out\res.zip"
if ($LASTEXITCODE) { throw "aapt2 compile failed" }
& "$bt\aapt2.exe" link -I $jar --manifest "$root\AndroidManifest.xml" --java "$out\gen" -o "$out\base.apk" "$out\res.zip"
if ($LASTEXITCODE) { throw "aapt2 link failed" }

$sources = Get-ChildItem -Recurse "$root\src", "$out\gen" -Filter *.java | ForEach-Object FullName
& javac -encoding UTF-8 -source 11 -target 11 -Xlint:-options -classpath $jar -d "$out\classes" @sources
if ($LASTEXITCODE) { throw "javac failed" }

$classes = Get-ChildItem -Recurse "$out\classes" -Filter *.class | ForEach-Object FullName
& "$bt\d8.bat" --release --min-api 33 --lib $jar --output "$out\dex" @classes
if ($LASTEXITCODE) { throw "d8 failed" }

& jar -uMf "$out\base.apk" -C "$out\dex" classes.dex
& "$bt\zipalign.exe" -f -p 4 "$out\base.apk" "$out\aligned.apk"
$apk = "$root\$name$(if ($Release) { '-release' }).apk"
& "$bt\apksigner.bat" sign @sign --out $apk "$out\aligned.apk"
if ($LASTEXITCODE) { throw "apksigner failed" }
Write-Host "Built $apk"
