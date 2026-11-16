# Builds a Windows package of Zyclipa with jpackage (part of the JDK).
#   -Type app-image : a portable folder with its own Java runtime (no extra tools needed)
#   -Type msi / exe : an installer; needs the free WiX Toolset 3.x on PATH
# Run from the project folder after `mvn package`.
param(
    [ValidateSet('app-image', 'msi', 'exe')] [string] $Type = 'app-image',
    [string] $Version = '0.1.0',
    [string] $Dest = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$jar = Join-Path $root 'target/zyclipa.jar'
if (-not (Test-Path $jar)) { throw "Build first: mvn package (missing $jar)" }

if (-not $Dest) { $Dest = Join-Path $root 'dist' }
$inDir = Join-Path $root 'target/jpackage-input'
if (Test-Path $inDir) { Remove-Item -Recurse -Force $inDir }   # only the current jar goes into the package
New-Item -ItemType Directory -Force $inDir | Out-Null
Copy-Item $jar $inDir -Force

$jpArgs = @(
    '--type', $Type,
    '--name', 'Zyclipa',
    '--app-version', $Version,
    '--vendor', 'Zyclipa contributors',
    '--description', 'Free gaming utility: recording, instant replay, mixer and soundboard',
    '--input', $inDir,
    '--main-jar', 'zyclipa.jar',
    '--main-class', 'io.zyclipa.Launcher',
    '--java-options', '--enable-native-access=ALL-UNNAMED',
    '--java-options', '-Xmx384m',
    '--icon', (Join-Path $PSScriptRoot 'zyclipa.ico'),
    '--dest', $Dest
)
if ($Type -ne 'app-image') {
    # Per-user install: no administrator rights needed.
    $jpArgs += @('--license-file', (Join-Path $root 'LICENSE'), '--win-per-user-install', '--win-menu', '--win-shortcut', '--win-dir-chooser',
               '--win-upgrade-uuid', '6c1f5f8e-2b8a-4b8e-9d1e-5a7c0c2f9e31')
}
# Optional: bundle FFmpeg next to the app (put ffmpeg.exe and ffprobe.exe in packaging/ffmpeg first).
$ff = Join-Path $PSScriptRoot 'ffmpeg'
if (Test-Path (Join-Path $ff 'ffmpeg.exe')) { $jpArgs += @('--app-content', $ff) }

$jp = 'jpackage'
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/jpackage.exe'))) { $jp = Join-Path $env:JAVA_HOME 'bin/jpackage.exe' }
& $jp @jpArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage failed ($LASTEXITCODE)" }
# Licence and third-party notices travel with the app (required by the bundled components' licences).
$img = Join-Path $Dest 'Zyclipa'
if (Test-Path $img) {
    Copy-Item (Join-Path $root 'LICENSE') (Join-Path $img 'LICENSE.txt') -Force
    Copy-Item (Join-Path $root 'packaging/THIRD-PARTY-NOTICES.txt') (Join-Path $img 'THIRD-PARTY-NOTICES.txt') -Force
}
Write-Host "Done: $Dest"
