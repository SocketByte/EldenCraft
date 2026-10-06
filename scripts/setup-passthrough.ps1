# Reproducible workspace-only setup. Does not execute ReShade's installer,
# launch either game, or write into a game installation/profile.
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$runtimeDir = Join-Path $repo '.tools\reshade-v6.8.0'
$researchDir = Join-Path $repo '.local\research'
$sdkRepo = Join-Path $researchDir 'reshade'
$sdkExport = Join-Path $researchDir 'reshade-6.8.0'
$buildDir = Join-Path $repo '.local\compositor-build'
$setupUrl = 'https://reshade.me/downloads/ReShade_Setup_6.8.0_Addon.exe'
$setupHash = 'AFE4C8F13048306307983B8B3D41D5BF00A86820440B0E57DEA10950E1176445'
$dllHash = '0CEE63F9C9F13F3AC909C5B4903F4DBB4B719A7AB3B4F13B0DEAF83C814B94F7'
$sdkCommit = '18deaa52de0c425a78b329e9cb3c497281cd00ec'
$setupPath = Join-Path $runtimeDir 'ReShade_Setup_6.8.0_Addon.exe'
$dllPath = Join-Path $runtimeDir 'ReShade64.dll'

function Assert-Checksum([string]$Path, [string]$Expected) {
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ine $Expected) {
        throw "Checksum mismatch; refusing to use $Path. Existing files were not replaced."
    }
}
function Assert-Exit([string]$Operation) {
    if ($LASTEXITCODE -ne 0) { throw "$Operation failed (exit $LASTEXITCODE)." }
}

Get-Command git -ErrorAction Stop | Out-Null
Get-Command tar -ErrorAction Stop | Out-Null
New-Item -ItemType Directory -Force -Path $runtimeDir, $researchDir, $sdkExport | Out-Null

if (-not (Test-Path -LiteralPath $setupPath)) {
    $downloadPath = Join-Path $runtimeDir 'ReShade_Setup_6.8.0_Addon.exe.part'
    Write-Output 'Downloading official ReShade 6.8.0 with full add-on support...'
    Invoke-WebRequest -Uri $setupUrl -OutFile $downloadPath
    Assert-Checksum $downloadPath $setupHash
    Move-Item -LiteralPath $downloadPath -Destination $setupPath
}
Assert-Checksum $setupPath $setupHash
if (-not (Test-Path -LiteralPath $dllPath)) {
    . (Join-Path $PSScriptRoot 'windows.ps1')
    Expand-ReShadeRuntime $setupPath $runtimeDir $setupHash $dllHash | Out-Null
}
Assert-Checksum $dllPath $dllHash

if (-not (Test-Path -LiteralPath (Join-Path $sdkRepo '.git'))) {
    if (Test-Path -LiteralPath $sdkRepo) { throw "SDK destination exists without a Git checkout: $sdkRepo" }
    & git clone --depth 1 --branch v6.8.0 --single-branch 'https://github.com/crosire/reshade.git' $sdkRepo
    Assert-Exit 'Pinned ReShade SDK clone'
}
$resolvedCommit = & git -C $sdkRepo rev-parse --verify 'refs/tags/v6.8.0^{commit}' 2>$null
if ($LASTEXITCODE -ne 0) {
    & git -C $sdkRepo fetch --depth 1 'https://github.com/crosire/reshade.git' 'refs/tags/v6.8.0:refs/tags/v6.8.0'
    Assert-Exit 'Pinned ReShade SDK fetch'
    $resolvedCommit = & git -C $sdkRepo rev-parse --verify 'refs/tags/v6.8.0^{commit}'
    Assert-Exit 'Pinned SDK revision lookup'
}
if (($resolvedCommit -join '').Trim() -ine $sdkCommit) { throw 'ReShade v6.8.0 tag did not resolve to the pinned commit.' }

# Reuse existing headers only if each file matches its committed Git blob.
$headers = @(& git -C $sdkRepo ls-tree -r --name-only $sdkCommit -- include)
Assert-Exit 'Pinned SDK header inventory'
if ($headers.Count -eq 0) { throw 'Pinned SDK contains no headers.' }
$reuseHeaders = $true
foreach ($relativePath in $headers) {
    $localHeader = Join-Path $sdkExport $relativePath
    if (-not (Test-Path -LiteralPath $localHeader)) { $reuseHeaders = $false; break }
    $expectedBlob = & git -C $sdkRepo rev-parse "${sdkCommit}:$relativePath"
    Assert-Exit 'Pinned SDK blob lookup'
    $actualBlob = & git -C $sdkRepo hash-object --no-filters -- $localHeader
    Assert-Exit 'Local SDK header validation'
    if ($actualBlob -ne $expectedBlob) { $reuseHeaders = $false; break }
}
if (-not $reuseHeaders) {
    $headerArchive = Join-Path $sdkExport 'headers.tar'
    & git -C $sdkRepo archive --format=tar "--output=$headerArchive" $sdkCommit -- include
    Assert-Exit 'Pinned SDK header export'
    & tar -xf $headerArchive -C $sdkExport
    Assert-Exit 'Pinned SDK header extraction'
}

# ReShade's public ImGui function table requires its exact submodule version.
# Only headers are used; no second ImGui implementation is linked into the add-on.
$imguiCommit = '3912b3d9a9c1b3f17431aebafd86d2f40ee6e59c'
$imguiHeaders = @{
    'imgui.h' = '07562842049EACA4E47C3FCDCC9D8EAFD442695B9856D6F6639E92E1BB6C737E'
    'imconfig.h' = '6E5687893594EBFAF8569280CFEA83D025904A8A4E23BEC073086F6C5BC8F7FB'
}
foreach ($name in $imguiHeaders.Keys) {
    $destination = Join-Path (Join-Path $sdkExport 'include') $name
    if (-not (Test-Path -LiteralPath $destination)) {
        $temporary = "$destination.part"
        Invoke-WebRequest -Uri "https://raw.githubusercontent.com/ocornut/imgui/$imguiCommit/$name" -OutFile $temporary
        Assert-Checksum $temporary $imguiHeaders[$name]
        Move-Item -LiteralPath $temporary -Destination $destination
    }
    Assert-Checksum $destination $imguiHeaders[$name]
}

. (Join-Path $PSScriptRoot 'native-env.ps1')
Set-EldenCraftNativeEnvironment -RepoRoot $repo
& cmake -S (Join-Path $repo 'compositor') -B $buildDir -G Ninja `
    "-DRESHADE_INCLUDE_DIR=$(Join-Path $sdkExport 'include')" -DCMAKE_BUILD_TYPE=Release
Assert-Exit 'Compositor configuration'
& cmake --build $buildDir
Assert-Exit 'Compositor build'
& ctest --test-dir $buildDir --output-on-failure
Assert-Exit 'Compositor tests'

$artifact = Join-Path $buildDir 'EldenCraftCompositor.addon64'
Write-Output "ReShade runtime verified: $dllPath"
Write-Output "Compositor ready: $artifact"
Write-Output "SHA256: $((Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash)"
Write-Output 'Setup complete. Game installations, profiles and saves were not changed.'
