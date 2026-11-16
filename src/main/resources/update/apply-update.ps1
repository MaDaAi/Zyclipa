# Zyclipa update step: waits for Zyclipa to close, swaps in the new version, starts it.
# Started by the app after the download was verified (signature, size, SHA-256). The old version is kept as *.previous.
param([int]$WaitPid, [string]$Src, [string]$Dst, [string]$Staging)
$ErrorActionPreference = 'Stop'
try { Wait-Process -Id $WaitPid -Timeout 120 -ErrorAction SilentlyContinue } catch { }
Start-Sleep -Milliseconds 800
$prev = "$Dst.previous"
if (Test-Path -LiteralPath $prev) { Remove-Item -LiteralPath $prev -Recurse -Force }
Rename-Item -LiteralPath $Dst -NewName (Split-Path -Leaf $prev)
try {
    Move-Item -LiteralPath $Src -Destination $Dst
} catch {
    # Put the old version back so Zyclipa still starts.
    Rename-Item -LiteralPath $prev -NewName (Split-Path -Leaf $Dst)
    throw
}
if ($Staging -and (Test-Path -LiteralPath $Staging)) { Remove-Item -LiteralPath $Staging -Recurse -Force -ErrorAction SilentlyContinue }
Start-Process -FilePath (Join-Path $Dst 'Zyclipa.exe')
