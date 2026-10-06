<#
.SYNOPSIS
Builds every EldenCraft component, installs it into the dedicated Minecraft
profile and the Elden Ring workspace runtime, then launches Elden Ring offline.

.DESCRIPTION
1. Refuses to run while Elden Ring or the dedicated Minecraft profile is running.
2. Builds the native loader + core, the ReShade compositor add-on and the Fabric
   guest jar (the Gradle build runs all guest conformance suites). Native and
   compositor tests run unless -SkipTests.
3. Backs up the Minecraft profile, retires the previous bridge jar to
   .local\profile-mod-backups and installs the new one (hash-verified).
4. Runs scripts\launch-eldencraft.ps1, which copies the DLLs, add-on and effect
   into .local\eldencraft-runtime, backs up the Elden Ring saves and starts the
   offline me3 profile. Nothing is written into the game folder.

Start the Modrinth EldenCraft profile afterwards; its world opens automatically.
Logs of every step: .local\deploy-logs\<timestamp>\.

.EXAMPLE
.\scripts\deploy-eldencraft.ps1
.\scripts\deploy-eldencraft.ps1 -HotReload -PoseLock
.\scripts\deploy-eldencraft.ps1 -SkipTests -NoLaunch
#>
param(
    # Skip native and compositor tests (the Gradle build still runs guest suites).
    [switch]$SkipTests,
    # Build and install only.
    [switch]$NoLaunch,
    # ELDENCRAFT_HOT_RELOAD=1: reload the core later with eldencraft.ps1 reload-native.
    [switch]$HotReload,
    # ELDENCRAFT_POSE_LOCK=1: experimental camera-locked composition.
    [switch]$PoseLock,
    # ELDENCRAFT_GPU_TRANSPORT=0: shared-memory frames instead of shared GPU textures.
    [switch]$NoGpuTransport,
    # Do not enable the full offline lab (native combat, block collision, mob proxies).
    [switch]$NoLab
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$project = $repo
$python = Join-Path $repo '.venv\Scripts\python.exe'
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logDir = Join-Path $repo ".local\deploy-logs\$stamp"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
$script:step = 0

function Write-Step([string]$text) { Write-Host ''; Write-Host "==> $text" -ForegroundColor Cyan }

# Runs a native command line through cmd so stderr chatter (Gradle, cargo) never
# becomes a PowerShell error; the exit code decides. Output goes to a step log.
function Invoke-Checked([string]$label, [string]$commandLine) {
    $script:step++
    $log = Join-Path $logDir ('{0:D2}-{1}.log' -f $script:step, ($label -replace '[^A-Za-z0-9]+', '-'))
    Write-Host "    $label" -NoNewline
    $started = Get-Date
    cmd /c "$commandLine > `"$log`" 2>&1"
    $code = $LASTEXITCODE
    $seconds = [int]((Get-Date) - $started).TotalSeconds
    if ($code -ne 0) {
        Write-Host " FAILED ($seconds s)" -ForegroundColor Red
        Get-Content -LiteralPath $log -Tail 40 | ForEach-Object { Write-Host "      $_" }
        throw "$label failed (exit $code). Full log: $log"
    }
    Write-Host " ok ($seconds s)" -ForegroundColor Green
}

function Assert-GamesClosed([string]$profilePath) {
    $elden = Get-Process -ErrorAction SilentlyContinue | Where-Object {
        $_.ProcessName -eq 'eldenring' -or $_.ProcessName -like 'EasyAntiCheat*' -or $_.ProcessName -eq 'start_protected_game'
    }
    if ($elden) { throw 'Close Elden Ring first (from its menu: System > Quit Game).' }
    $minecraft = Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf($profilePath, [StringComparison]::OrdinalIgnoreCase) -ge 0 }
    if ($minecraft) { throw "Close Minecraft (EldenCraft profile, PID $(($minecraft | Select-Object -First 1).ProcessId)) first; its mods cannot be replaced while it runs." }
}

if (-not (Test-Path -LiteralPath $python)) { throw 'Run .\scripts\eldencraft.ps1 setup first.' }
$config = Get-Content -LiteralPath (Join-Path $project 'config\local.json') -Raw | ConvertFrom-Json
$profileDir = $config.minecraft_dev_profile_dir
if (-not $profileDir -or -not (Test-Path -LiteralPath (Join-Path $profileDir 'mods'))) {
    throw 'config\local.json must name the dedicated Modrinth profile (minecraft_dev_profile_dir) with a mods folder.'
}
$profileDir = (Resolve-Path -LiteralPath $profileDir).Path
Assert-GamesClosed $profileDir

$properties = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $project 'minecraft\gradle.properties')) {
    if ($line -match '^\s*([^#=]+?)\s*=\s*(.*)$') { $properties[$Matches[1]] = $Matches[2].Trim() }
}
$version = $properties['version']
$apiVersion = $properties['fabric_api_version']
Write-Host "EldenCraft $version -> $profileDir" -ForegroundColor White
Write-Host "Logs: $logDir"

# ---------------------------------------------------------------- build
Write-Step 'Building'
. (Join-Path $PSScriptRoot 'native-env.ps1')
Set-EldenCraftNativeEnvironment -RepoRoot $repo
$env:CARGO_TERM_COLOR = 'never'
$nativeManifest = Join-Path $project 'native\Cargo.toml'
Invoke-Checked 'native loader + core (release)' "cargo +1.99.0 build --manifest-path `"$nativeManifest`" --workspace --release --locked"
if (-not $SkipTests) { Invoke-Checked 'native tests' "cargo +1.99.0 test --manifest-path `"$nativeManifest`" --workspace --locked" }

$compositorBuild = Join-Path $repo '.local\compositor-build'
$reshadeInclude = Join-Path $repo '.local\research\reshade-6.8.0\include'
if (-not (Test-Path -LiteralPath (Join-Path $reshadeInclude 'reshade.hpp'))) { throw "ReShade 6.8.0 headers are missing: $reshadeInclude" }
Invoke-Checked 'compositor configure' "cmake -S `"$(Join-Path $project 'compositor')`" -B `"$compositorBuild`" -G Ninja -DRESHADE_INCLUDE_DIR=`"$reshadeInclude`" -DCMAKE_BUILD_TYPE=Release"
Invoke-Checked 'compositor add-on' "cmake --build `"$compositorBuild`""
if (-not $SkipTests) { Invoke-Checked 'compositor tests' "ctest --test-dir `"$compositorBuild`" --output-on-failure" }
$fxCheck = Join-Path $repo '.local\compositor-compiler\reshadefx-check.exe'
if (Test-Path -LiteralPath $fxCheck) {
    Invoke-Checked 'effect compile (ReShade FX)' "`"$fxCheck`" `"$(Join-Path $project 'compositor\shaders\EldenCraftPassthrough.fx')`""
}

$env:GRADLE_USER_HOME = Join-Path $repo '.cache\gradle'
$javaTmp = Join-Path $repo '.cache\tmp'
New-Item -ItemType Directory -Force -Path $javaTmp | Out-Null
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
$env:JAVA_TOOL_OPTIONS = "-Djava.net.preferIPv4Stack=true -Djdk.net.unixdomain.tmpdir=`"$javaTmp`""
try {
    $minecraftDir = Join-Path $project 'minecraft'
    # Full path: cmd may be configured not to run programs from the current directory.
    Invoke-Checked 'Minecraft guest + conformance suites' "cd /d `"$minecraftDir`" && `"$(Join-Path $minecraftDir 'gradlew.bat')`" --no-daemon build"
} finally { $env:JAVA_TOOL_OPTIONS = $previousJavaOptions }

$jar = Join-Path $project "minecraft\build\libs\eldencraft-bridge-$version.jar"
foreach ($artifact in @($jar, (Join-Path $repo '.local\native-build\release\eldencraft_native.dll'),
        (Join-Path $repo '.local\native-build\release\eldencraft_core.dll'), (Join-Path $compositorBuild 'EldenCraftCompositor.addon64'))) {
    if (-not (Test-Path -LiteralPath $artifact)) { throw "Build output missing: $artifact" }
}

# ---------------------------------------------------------------- Minecraft
Write-Step 'Installing into the Minecraft profile'
Assert-GamesClosed $profileDir
$mods = Join-Path $profileDir 'mods'
$api = Get-ChildItem -LiteralPath (Join-Path $repo ".cache\gradle\caches\modules-2\files-2.1\net.fabricmc.fabric-api\fabric-api\$apiVersion") -Recurse -Filter "fabric-api-$apiVersion.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
$installedApis = @(Get-ChildItem -LiteralPath $mods -Filter '*fabric-api*.jar')
if ($installedApis | Where-Object { $_.Name -ne "fabric-api-$apiVersion.jar" }) {
    throw "The profile has a different Fabric API than $apiVersion. Use the dedicated 26.3 EldenCraft profile."
}
Invoke-Checked 'Minecraft profile backup' "`"$python`" `"$(Join-Path $repo 'tools\backup.py')`" create `"$profileDir`" --name eldencraft-minecraft --note `"Before EldenCraft $version deploy`""
if (-not $installedApis) {
    if (-not $api) { throw "Fabric API $apiVersion is not in the Gradle cache; run the guest build once online." }
    Copy-Item -LiteralPath $api.FullName -Destination $mods
    Write-Host "    installed $($api.Name)"
}
$retired = Join-Path $repo ".local\profile-mod-backups\before-$version-$stamp"
$oldJars = @(Get-ChildItem -LiteralPath $mods -Filter 'eldencraft-bridge-*.jar')
if ($oldJars) {
    New-Item -ItemType Directory -Force -Path $retired | Out-Null
    foreach ($old in $oldJars) { Move-Item -LiteralPath $old.FullName -Destination $retired; Write-Host "    retired $($old.Name) -> $retired" }
}
$target = Join-Path $mods (Split-Path -Leaf $jar)
Copy-Item -LiteralPath $jar -Destination $target
$jarHash = (Get-FileHash -LiteralPath $jar -Algorithm SHA256).Hash
if ((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $jarHash) { throw 'Installed guest jar does not match the build output.' }
Write-Host "    installed $(Split-Path -Leaf $jar) (SHA256 $jarHash)" -ForegroundColor Green

$campaignConfig = if ($env:ELDENCRAFT_CAMPAIGN_CONFIG) { [IO.Path]::GetFullPath($env:ELDENCRAFT_CAMPAIGN_CONFIG) } else { Join-Path $repo 'config/campaign.json' }
$campaignDirectory = if ($env:ELDENCRAFT_CAMPAIGN_DIR) { [IO.Path]::GetFullPath($env:ELDENCRAFT_CAMPAIGN_DIR) } else { Join-Path $repo '.local/eldencraft-runtime/data/campaign' }
$guestConfigDirectory = Join-Path $profileDir 'config'
New-Item -ItemType Directory -Force -Path $guestConfigDirectory | Out-Null
[IO.File]::WriteAllText((Join-Path $guestConfigDirectory 'eldencraft-campaign-link.json'),
    (@{ config = $campaignConfig; directory = $campaignDirectory } | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
$env:ELDENCRAFT_CAMPAIGN_CONFIG = $campaignConfig
$env:ELDENCRAFT_CAMPAIGN_DIR = $campaignDirectory

# ---------------------------------------------------------------- Elden Ring
if ($NoLab) { Write-Host '    full offline lab flags left unchanged' }
else {
    foreach ($name in 'ELDENCRAFT_MELEE_LAB', 'ELDENCRAFT_NATIVE_COLLIDERS', 'ELDENCRAFT_NATIVE_MOBS') {
        if (-not [Environment]::GetEnvironmentVariable($name)) { Set-Item -Path "env:$name" -Value '1' }
    }
}
if ($HotReload) { $env:ELDENCRAFT_HOT_RELOAD = '1' }
if ($PoseLock) { $env:ELDENCRAFT_POSE_LOCK = '1' }
if ($NoGpuTransport) { $env:ELDENCRAFT_GPU_TRANSPORT = '0' }

if ($NoLaunch) {
    Write-Step 'Preparing the Elden Ring runtime (no launch)'
    & (Join-Path $PSScriptRoot 'launch-eldencraft.ps1') -PrepareOnly
} else {
    Write-Step 'Installing into the Elden Ring runtime and launching'
    Assert-GamesClosed $profileDir
    & (Join-Path $PSScriptRoot 'launch-eldencraft.ps1')
}
$flags = @('ELDENCRAFT_MELEE_LAB', 'ELDENCRAFT_NATIVE_COLLIDERS', 'ELDENCRAFT_NATIVE_MOBS', 'ELDENCRAFT_HOT_RELOAD', 'ELDENCRAFT_POSE_LOCK', 'ELDENCRAFT_GPU_TRANSPORT') |
    Where-Object { [Environment]::GetEnvironmentVariable($_) } | ForEach-Object { "$_=$([Environment]::GetEnvironmentVariable($_))" }
Write-Host ''
Write-Host "EldenCraft $version deployed. Flags: $(if ($flags) { $flags -join ', ' } else { 'none' })" -ForegroundColor Green
Write-Host 'Next: start the EldenCraft profile in Modrinth; its world opens automatically. Load an Elden Ring character to connect.'
if ($HotReload) { Write-Host 'Hot reload: .\scripts\eldencraft.ps1 reload-native swaps the native core while the game runs.' }
