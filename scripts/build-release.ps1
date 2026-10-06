param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo
try {
    $python = Join-Path $repo '.venv/Scripts/python.exe'
    if (-not (Test-Path -LiteralPath $python)) { throw 'Run scripts/eldencraft.ps1 setup first.' }
    if (-not $SkipBuild) {
        & (Join-Path $PSScriptRoot 'eldencraft.ps1') test
        if ($LASTEXITCODE -ne 0) { throw 'Python checks failed.' }
        & (Join-Path $PSScriptRoot 'eldencraft.ps1') test-native
        if ($LASTEXITCODE -ne 0) { throw 'Native checks failed.' }
        & (Join-Path $PSScriptRoot 'eldencraft.ps1') build-native
        if ($LASTEXITCODE -ne 0) { throw 'Native build failed.' }
        & (Join-Path $PSScriptRoot 'eldencraft.ps1') build-minecraft
        if ($LASTEXITCODE -ne 0) { throw 'Minecraft build failed.' }
        & (Join-Path $PSScriptRoot 'setup-passthrough.ps1')
        & (Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe') -NoProfile -ExecutionPolicy Bypass -File (Join-Path $repo 'tests/windows-launcher.Tests.ps1')
        if ($LASTEXITCODE -ne 0) { throw 'Windows launcher checks failed.' }
    }
    & (Join-Path $PSScriptRoot 'eldencraft.ps1') check-source
    if ($LASTEXITCODE -ne 0) { throw 'Source checks failed.' }
    . (Join-Path $PSScriptRoot 'native-env.ps1')
    Set-EldenCraftNativeEnvironment -RepoRoot $repo
    # Catch accidental dynamic CRT linking before shipping a release to a clean PC.
    foreach ($relative in @('.local/native-build/release/eldencraft_native.dll', '.local/native-build/release/eldencraft_core.dll', '.local/compositor-build/EldenCraftCompositor.addon64')) {
        $dependencies = & dumpbin /dependents (Join-Path $repo $relative)
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect release DLL: $relative" }
        if (($dependencies -join "`n") -match '(?i)(MSVCP|VCRUNTIME)\d.*\.dll') {
            throw "Release DLL still depends on an installed Visual C++ runtime: $relative"
        }
    }
    $metadata = & cargo +1.99.0 metadata --manifest-path (Join-Path $repo 'native/Cargo.toml') --locked --offline --filter-platform x86_64-pc-windows-msvc --format-version 1
    if ($LASTEXITCODE -ne 0) { throw 'Cannot collect dependency license inventory.' }
    $metadataPath = Join-Path $repo '.local/release-cargo-metadata.json'
    [IO.File]::WriteAllText($metadataPath, ($metadata -join "`n"), [Text.UTF8Encoding]::new($false))
    & $python (Join-Path $repo 'tools/package_release.py') --cargo-metadata $metadataPath
    if ($LASTEXITCODE -ne 0) { throw 'Release packaging failed.' }
} finally { Pop-Location }
