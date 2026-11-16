# Builds a signed Zyclipra release for the in-app updater.
#   1. ./mvnw.cmd package (with the JDK the exe should ship, e.g. .tools\jdk-21.0.13+9)
#   2. powershell -File packaging\make-release.ps1 -Version 0.2.0 -Notes "What's new"
#   3. On GitHub: MaDaAi/zyclipra-dashboard › Releases › new release, tag v<Version>, upload BOTH files from target\release:
#        Zyclipra-<Version>-windows-x64.zip   and   zyclipra-update.json
# The signing key is private\update-signing-key.txt (never commit or share it; back it up – without it you can't ship updates).
param(
    [Parameter(Mandatory = $true)] [string] $Version,
    [string] $Notes = '',
    [string] $Jdk = ''
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Jdk) { $Jdk = (Get-ChildItem (Join-Path $root '.tools') -Directory -Filter 'jdk-21*' | Sort-Object Name | Select-Object -Last 1).FullName }
if (-not $Jdk) { throw 'No JDK found in .tools (expected jdk-21.x)' }
$env:JAVA_HOME = $Jdk
$out = Join-Path $root 'target\release'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-installer.ps1') -Version $Version -Dest $out
if ($LASTEXITCODE -ne 0) { throw 'packaging failed' }
$zip = Join-Path $out "Zyclipra-$Version-windows-x64.zip"
Push-Location $root
try {
    & (Join-Path $Jdk 'bin\java.exe') (Join-Path $PSScriptRoot 'SignRelease.java') pack (Join-Path $out 'Zyclipra') $zip
    if ($LASTEXITCODE -ne 0) { throw 'zip failed' }
    & (Join-Path $Jdk 'bin\java.exe') (Join-Path $PSScriptRoot 'SignRelease.java') sign $zip $Version $Notes
}
finally { Pop-Location }
if ($LASTEXITCODE -ne 0) { throw 'signing failed' }
Get-ChildItem $out -File | Select-Object Name, Length
