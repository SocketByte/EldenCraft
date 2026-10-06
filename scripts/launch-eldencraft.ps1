param([switch]$PrepareOnly)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$config = Get-Content -LiteralPath (Join-Path $repo 'config\local.json') -Raw | ConvertFrom-Json
$gameExe = Join-Path $config.elden_ring_game_dir 'eldenring.exe'
$supportedHash = '1a3547101327f65d0c76da2f9190ac0aa66871ea42bae2aecc61e11a8b597891'
if (-not (Test-Path -LiteralPath $gameExe)) { throw 'Configured Elden Ring executable does not exist.' }
if ((Get-FileHash -Algorithm SHA256 -LiteralPath $gameExe).Hash -ine $supportedHash) {
    throw 'The game executable changed. Revalidate SDK compatibility before launching the native host.'
}
$running = Get-Process -ErrorAction SilentlyContinue | Where-Object {
    $_.ProcessName -eq 'eldenring' -or $_.ProcessName -like 'EasyAntiCheat*' -or $_.ProcessName -eq 'start_protected_game'
}
if ($running) { throw 'Close Elden Ring and any active protected game before launching the isolated mod profile.' }
$me3 = Join-Path $repo '.tools\me3-v0.13.0\bin\me3.exe'
$builtDll = Join-Path $repo '.local\native-build\release\eldencraft_native.dll'
$builtCore = Join-Path $repo '.local\native-build\release\eldencraft_core.dll'
if (-not (Test-Path -LiteralPath $me3)) { throw 'Run scripts/eldencraft.ps1 setup-native first.' }
if (-not (Test-Path -LiteralPath $builtDll) -or -not (Test-Path -LiteralPath $builtCore)) { throw 'Run scripts/eldencraft.ps1 build-native first.' }
$runtime = Join-Path $repo '.local\eldencraft-runtime'
New-Item -ItemType Directory -Force -Path $runtime,(Join-Path $runtime 'data'),(Join-Path $runtime 'logs') | Out-Null
if (-not $env:ELDENCRAFT_CAMPAIGN_CONFIG) { $env:ELDENCRAFT_CAMPAIGN_CONFIG = Join-Path $repo 'config/campaign.json' }
if (-not $env:ELDENCRAFT_CAMPAIGN_DIR) { $env:ELDENCRAFT_CAMPAIGN_DIR = Join-Path $runtime 'data/campaign' }
# eldencraft_native.dll is the persistent loader; it runs a private copy of eldencraft_core.dll.
Copy-Item -LiteralPath $builtDll -Destination (Join-Path $runtime 'eldencraft_native.dll') -Force
Copy-Item -LiteralPath $builtCore -Destination (Join-Path $runtime 'eldencraft_core.dll') -Force
$reshade = Join-Path $repo '.tools\reshade-v6.8.0\ReShade64.dll'
$addon = Join-Path $repo '.local\compositor-build\EldenCraftCompositor.addon64'
$shader = Join-Path $repo 'compositor\shaders\EldenCraftPassthrough.fx'
foreach ($required in @($reshade,$addon,$shader)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Passthrough dependency is missing: $required" }
}
if ((Get-FileHash -LiteralPath $reshade -Algorithm SHA256).Hash -ine '0cee63f9c9f13f3ac909c5b4903f4dbb4b719a7ab3b4f13b0deaf83c814b94f7') {
    throw 'ReShade runtime does not match the official pinned 6.8.0 add-on build.'
}
Copy-Item -LiteralPath $reshade -Destination (Join-Path $runtime 'ReShade64.asi') -Force
New-Item -ItemType Directory -Force (Join-Path $runtime 'addons'),(Join-Path $runtime 'shaders') | Out-Null
Copy-Item -LiteralPath $addon -Destination (Join-Path $runtime 'addons\EldenCraftCompositor.addon64') -Force
Copy-Item -LiteralPath $shader -Destination (Join-Path $runtime 'shaders\EldenCraftPassthrough.fx') -Force
$ini = "[ADDON]`nAddonPath=$runtime\addons`n[GENERAL]`nEffectSearchPaths=$runtime\shaders`nPresetPath=$runtime\EldenCraftPreset.ini`n[INPUT]`nInputProcessing=2`n[OVERLAY]`nTutorialProgress=4`n"
[System.IO.File]::WriteAllText((Join-Path $runtime 'ReShade.ini'), $ini, [System.Text.UTF8Encoding]::new($false))
$presetPath = Join-Path $runtime 'EldenCraftPreset.ini'
# Preserve the verified host-depth convention and the user's shader settings
# between launches; an uncalibrated first launch still starts scene-hidden.
if (-not (Test-Path -LiteralPath $presetPath)) {
    [System.IO.File]::WriteAllText($presetPath, "Techniques=EldenCraftOverlay@EldenCraftPassthrough.fx`nTechniqueSorting=EldenCraftOverlay@EldenCraftPassthrough.fx`n", [System.Text.UTF8Encoding]::new($false))
}
$profile = Join-Path $runtime 'eldencraft.me3'
$profileText = @'
profileVersion = "v1"
savefile = "EldenCraft.sl2"
start_online = false
disable_arxan = false
mem_patch = false

[[supports]]
game = "eldenring"

[[natives]]
path = "ReShade64.asi"

[[natives]]
path = "eldencraft_native.dll"
'@
# ReShade is loaded from the workspace before the native host.
[System.IO.File]::WriteAllText($profile, $profileText, [System.Text.UTF8Encoding]::new($false))
if ($PrepareOnly) {
    Write-Output "Prepared offline profile: $profile"
    Write-Output 'No game was launched. The first launch uses a separate EldenCraft.sl2 save.'
    return
}
$saveFiles = @(Get-ChildItem -LiteralPath $config.elden_ring_save_dir -Recurse -File -ErrorAction Stop)
if ($saveFiles.Count -eq 0) { throw 'No readable save/config files were found; refusing an empty backup and game launch.' }
& (Join-Path $repo '.venv\Scripts\python.exe') (Join-Path $repo 'tools\backup.py') create $config.elden_ring_save_dir --name eldencraft-eldenring --note 'Before isolated offline native launch'
if ($LASTEXITCODE -ne 0) { throw 'Save backup failed; game launch canceled.' }
$env:ELDENCRAFT_DATA_DIR = Join-Path $runtime 'data'
$env:ELDENCRAFT_EXPECTED_SHA256 = $supportedHash
$env:RESHADE_BASE_PATH_OVERRIDE = $runtime
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$stdout = Join-Path $runtime "logs\me3-$stamp.stdout.log"
$stderr = Join-Path $runtime "logs\me3-$stamp.stderr.log"
$arguments = @('--crash-reporting=false', 'launch', '--profile', ('"' + $profile + '"'), '--exe', ('"' + $gameExe + '"'))
$process = Start-Process -FilePath $me3 -ArgumentList $arguments -WorkingDirectory $runtime -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
Write-Output "Offline loader started (PID $($process.Id)); save: EldenCraft.sl2."
Write-Output "Loader output: $stdout"
Write-Output "Loader errors: $stderr"
