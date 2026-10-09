# Filesystem/process regressions and an offline first-install fixture, without game launches.
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path -Parent $PSScriptRoot
$scratch = Join-Path $repo ('.local/windows-tests/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $scratch | Out-Null
. (Join-Path $repo 'scripts/windows.ps1')
$script:checks = 0

function Assert-True([bool]$Value, [string]$Message) {
    $script:checks++
    if (-not $Value) { throw "Assertion failed: $Message" }
}
function Assert-Throws([scriptblock]$Action, [string]$Pattern) {
    $caught = $false
    try { & $Action | Out-Null }
    catch { $caught = $true; Assert-True ($_.Exception.Message -match $Pattern) "Expected '$Pattern', got '$($_.Exception.Message)'" }
    Assert-True $caught 'Operation should have failed.'
}
function New-FixtureZip([string]$Path, [hashtable]$Members) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::Open($Path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($name in $Members.Keys) {
            $writer = [IO.StreamWriter]::new($zip.CreateEntry($name).Open(), [Text.UTF8Encoding]::new($false))
            try { $writer.Write([string]$Members[$name]) } finally { $writer.Dispose() }
        }
    } finally { $zip.Dispose() }
}
function Get-Hash([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }

foreach ($bad in @('../escape', 'C:\escape', 'a/../escape', 'a:b', 'NUL.txt', 'a./file')) {
    Assert-Throws { Get-SafeChildPath $scratch $bad } 'Invalid|Unsafe|escaped'
}
$archive = Join-Path $scratch 'tool.zip'
New-FixtureZip $archive @{ 'bin/tool.exe' = 'portable tool fixture'; 'bin/data.txt' = 'data' }
$hash = Get-Hash $archive
$destination = Join-Path $scratch 'tool'
$exe = Expand-VerifiedArchive $archive $destination $hash 'bin/tool.exe'
Assert-True (Test-Path -LiteralPath $exe) 'Valid archive should extract.'
Assert-True ((Expand-VerifiedArchive $archive $destination $hash 'bin/tool.exe') -eq $exe) 'Setup should reuse verified tools.'
Write-Utf8File $exe 'corrupt'
Assert-Throws { Expand-VerifiedArchive $archive $destination $hash 'bin/tool.exe' } 'Checksum'
$unsafeArchive = Join-Path $scratch 'unsafe.zip'
New-FixtureZip $unsafeArchive @{ '../escaped.txt' = 'escape'; 'tool.exe' = 'fixture' }
Assert-Throws { Expand-VerifiedArchive $unsafeArchive (Join-Path $scratch 'unsafe') (Get-Hash $unsafeArchive) 'tool.exe' } 'Unsafe'
Assert-True (-not (Test-Path -LiteralPath (Join-Path $scratch 'escaped.txt'))) 'Traversal must not create files.'
$download = Join-Path $scratch 'cached.zip'
Write-Utf8File $download 'corrupt cached download'
Assert-Throws { Get-VerifiedDownload $scratch 'cached.zip' 'https://example.invalid/tool.zip' $hash } 'Checksum'

# Real short-lived redirected processes reproduce the Windows PowerShell 5.1
# missing-exit-code bug. They do not start either game or a launcher.
foreach ($exitCode in @(0, 7)) {
    $stamp = "exit-$exitCode"
    $process = Start-LoggedProcess -Executable (Join-Path $env:SystemRoot 'System32/cmd.exe') `
        -Arguments @('/d', '/c', "exit $exitCode") -Directory $scratch `
        -Stdout (Join-Path $scratch "me3-$stamp.stdout.log") -Stderr (Join-Path $scratch "me3-$stamp.stderr.log")
    try {
        Assert-True ($process.WaitForExit(10000)) 'Short-lived process must finish.'
        Assert-True ($process.ExitCode -eq $exitCode) 'Redirected process retains its actual exit code.'
        if ($exitCode -eq 0) { Assert-LoaderStarted $process $scratch $stamp }
        else { Assert-Throws { Assert-LoaderStarted $process $scratch $stamp } 'exit 7' }
    } finally { $process.Dispose() }
}
Assert-Throws { Assert-LoaderStarted ([pscustomobject]@{ HasExited = $true; ExitCode = $null } | Add-Member ScriptMethod Refresh {} -PassThru) $scratch 'missing' } 'without an available exit code'

# A detached CMD must give extraction instructions instead of PowerShell's
# opaque -File error. Passing check avoids an interactive pause in this fixture.
$bootstrap = Join-Path $scratch 'EldenCraft.cmd'
Copy-Item -LiteralPath (Join-Path $repo 'EldenCraft.cmd') -Destination $bootstrap
$process = Start-LoggedProcess -Executable (Join-Path $env:SystemRoot 'System32/cmd.exe') `
    -Arguments @('/d', '/c', ('""' + $bootstrap + '" check"')) -Directory $scratch `
    -Stdout (Join-Path $scratch 'bootstrap.stdout.log') -Stderr (Join-Path $scratch 'bootstrap.stderr.log')
try {
    Assert-True ($process.WaitForExit(10000)) 'Detached CMD must finish without starting PowerShell.'
    Assert-True ($process.ExitCode -eq 1) 'Missing bootstrap script fails clearly.'
    Assert-True ((Get-Content -LiteralPath (Join-Path $scratch 'bootstrap.stdout.log') -Raw) -match 'Extract the entire release ZIP') 'Missing script reports the recovery step.'
} finally { $process.Dispose() }

# Loader argv includes the authorized Steam initialization workaround and
# preserves spaces in both profile and executable paths.
function Start-LoggedProcess {
    param($Executable, $Arguments, $Directory, $Stdout, $Stderr)
    return [pscustomobject]@{ Executable = $Executable; Arguments = $Arguments; Directory = $Directory; Stdout = $Stdout; Stderr = $Stderr }
}
$launch = Start-EldenRingLoader 'me3.exe' 'runtime folder' 'runtime folder/profile.me3' 'game folder/eldenring.exe' $scratch 'argv'
Assert-True ($launch.Arguments -contains '--skip-steam-init') 'Steam API preflight cannot block the loader.'
Assert-True ($launch.Arguments -contains '"game folder/eldenring.exe"') 'Game path spaces remain quoted.'
Assert-True ($launch.Arguments -contains '"runtime folder/profile.me3"') 'Profile path spaces remain quoted.'
Remove-Item -LiteralPath function:Start-LoggedProcess

$conflictDirectory = Join-Path $scratch 'reshade-conflict'
New-Item -ItemType Directory -Path $conflictDirectory | Out-Null
$conflictExe = Join-Path $conflictDirectory 'eldenring.exe'
$conflictIni = Join-Path $conflictDirectory 'ReShade.ini'
Write-Utf8File (Join-Path $conflictDirectory 'dxgi.dll') 'unrelated proxy fixture'
Assert-NoReShadeConflict $conflictExe
Write-Utf8File $conflictIni "[GENERAL]`nBasePath=C:\Unrelated"
Assert-NoReShadeConflict $conflictExe
Write-Utf8File $conflictIni "[INSTALL]`nBasePath=C:\OldReShade"
Assert-Throws { Assert-NoReShadeConflict $conflictExe } 'old ReShade installation.*BasePath'
Assert-NoReShadeConflict $conflictExe 'C:\OldReShade'
Write-Utf8File $conflictIni "[INSTALL]`nBasePath=runtime"
Assert-NoReShadeConflict $conflictExe (Join-Path $conflictDirectory 'runtime')

# Mock only operating-system process discovery; attempts to launch anything are test failures.
function Get-Process { param($Name, $ErrorAction) return @() }
function Get-CimInstance { param($ClassName, $Filter) return @() }
function Start-Process { throw 'A regression test attempted to launch a process.' }

$package = Join-Path $scratch 'release'
$data = Join-Path $scratch ('user ' + [char]0x017c + ' data')
$cache = Join-Path $data 'downloads'
New-Item -ItemType Directory -Force -Path $package, $cache, (Join-Path $package 'config'), (Join-Path $package 'scripts'),
    (Join-Path $package 'payload/addons'), (Join-Path $package 'payload/shaders') | Out-Null
Copy-Item -LiteralPath (Join-Path $repo 'scripts/windows.ps1') -Destination (Join-Path $package 'scripts/windows.ps1')
$dependencies = Get-Content -LiteralPath (Join-Path $repo 'config/windows-release.json') -Raw -Encoding UTF8 | ConvertFrom-Json
foreach ($name in @('prism', 'java', 'me3')) {
    $dependency = $dependencies.$name
    $path = Join-Path $cache "$name-$($dependency.version).zip"
    New-FixtureZip $path @{ $dependency.executable = "$name executable fixture" }
    $dependency.sha256 = Get-Hash $path
}
$reshadeSetup = Join-Path $cache "ReShade-$($dependencies.reshade.version)-Addon.exe"
New-FixtureZip $reshadeSetup @{ 'ReShade64.dll' = 'graphics runtime fixture' }
$dependencies.reshade.sha256 = Get-Hash $reshadeSetup
$reshadeFixture = Join-Path $scratch 'reshade.dll'
Write-Utf8File $reshadeFixture 'graphics runtime fixture'
$dependencies.reshade.runtime_sha256 = Get-Hash $reshadeFixture
$api = Join-Path $cache "fabric-api-$($dependencies.minecraft.fabric_api.version).jar"
Write-Utf8File $api 'fabric fixture'
$dependencies.minecraft.fabric_api.sha256 = Get-Hash $api
$game = Join-Path $scratch 'eldenring.exe'
Write-Utf8File $game 'game executable fixture'
$dependencies.elden_ring.sha256 = Get-Hash $game
Write-Utf8File (Join-Path $package 'config/windows-release.json') ($dependencies | ConvertTo-Json -Depth 8)
Copy-Item -LiteralPath (Join-Path $repo 'config/campaign.json') -Destination (Join-Path $package 'config/campaign.json')
foreach ($relative in @('eldencraft_native.dll', 'eldencraft_core.dll', 'eldencraft-bridge.jar',
    'addons/EldenCraftCompositor.addon64', 'shaders/EldenCraftPassthrough.fx', 'EldenCraftPreset.ini')) {
    Write-Utf8File (Join-Path $package "payload/$relative") "fixture: $relative"
}
$files = @(Get-ChildItem -LiteralPath $package -Recurse -File | ForEach-Object {
    @{ path = $_.FullName.Substring($package.Length + 1).Replace('\', '/'); size = $_.Length; sha256 = Get-Hash $_.FullName }
})
Write-Utf8File (Join-Path $package 'release-manifest.json') (@{ schema_version = 1; version = '0.20.1'; files = $files } | ConvertTo-Json -Depth 6)
$release = Read-Release $package
Assert-True ($release.Version -eq '0.20.1') 'Complete fixture manifest should validate.'
Write-Utf8File (Join-Path $package 'payload/eldencraft_core.dll') 'modified'
Assert-Throws { Read-Release $package } 'Checksum'
Write-Utf8File (Join-Path $package 'payload/eldencraft_core.dll') 'fixture: eldencraft_core.dll'
Write-Utf8File (Join-Path $package 'config/campaign.json') '{}'
Assert-Throws { Read-Release $package } 'bundled campaign template.*runtime copy'
Copy-Item -LiteralPath (Join-Path $repo 'config/campaign.json') -Destination (Join-Path $package 'config/campaign.json') -Force
Assert-Throws { Find-EldenRing (Join-Path $scratch 'missing.exe') $game $package } 'not found'

# A Steam library on a drive that no longer exists (an unplugged disk) must be
# skipped, not abort discovery: Windows PowerShell's Join-Path throws for it.
$driveLetters = @(Get-PSDrive -PSProvider FileSystem | Select-Object -ExpandProperty Name)
$missingDrive = [char[]](68..90) | Where-Object { [string]$_ -notin $driveLetters } | Select-Object -First 1
$steamRoot = Join-Path $scratch 'steam'
$library = Join-Path $scratch 'library'
New-Item -ItemType Directory -Force -Path (Join-Path $steamRoot 'steamapps'), (Join-Path $library 'steamapps/common/ELDEN RING/Game') | Out-Null
Write-Utf8File (Join-Path $steamRoot 'steamapps/libraryfolders.vdf') @"
"libraryfolders"
{
    "0" { "path" "$($missingDrive):\\SteamLibrary" }
    "1" { "path" "$($library.Replace('\', '\\'))" }
}
"@
Write-Utf8File (Join-Path $library 'steamapps/appmanifest_1245620.acf') '"AppState" { "installdir" "ELDEN RING" }'
$libraryGame = Join-Path $library 'steamapps/common/ELDEN RING/Game/eldenring.exe'
Write-Utf8File $libraryGame 'game executable fixture'
function Get-ItemProperty { param($LiteralPath, $ErrorAction) return [pscustomobject]@{ SteamPath = $steamRoot } }
try {
    Assert-True ([bool]$missingDrive) 'A free drive letter is needed for the missing-drive fixture.'
    Assert-True ((Find-EldenRing '' '' $package) -eq [IO.Path]::GetFullPath($libraryGame)) 'A missing-drive Steam library must be skipped.'
} finally { Remove-Item -LiteralPath function:Get-ItemProperty }

$oldAppData = $env:APPDATA
try {
    $env:APPDATA = Join-Path $scratch 'appdata'
    $saveDirectory = Join-Path $env:APPDATA 'EldenRing/123'
    New-Item -ItemType Directory -Force -Path $saveDirectory | Out-Null
    $save = Join-Path $saveDirectory 'ER0000.sl2'
    Write-Utf8File $save 'original save data'
    $saveHash = Get-Hash $save
    $Mode = 'setup'
    $NonInteractive = $true
    $DataDirectory = $data
    $GamePath = $game
    # Compatibility errors occur before setup. They still need a transcript,
    # even when native runtime/data has never been created.
    Write-Utf8File $game 'unsupported game fixture'
    Assert-Throws { Invoke-EldenCraft -SourceRoot $package } 'Unsupported Elden Ring executable.*GamePath'
    $earlyLog = Get-ChildItem -LiteralPath (Join-Path $data 'logs') -File -Filter 'launcher-*.log' | Select-Object -First 1
    Assert-True ([bool]$earlyLog) 'Early compatibility failures write launcher logs.'
    Assert-True ((Get-Content -LiteralPath $earlyLog.FullName -Raw) -match 'Unsupported Elden Ring executable') 'Transcript contains the actual early error.'
    Assert-True ((Get-Content -LiteralPath $earlyLog.FullName -Raw) -match 'Troubleshoot.cmd -DataDirectory') 'Launcher failures point to a support bundle using the same data directory.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $data 'runtime/data'))) 'Early failure does not start the native runtime.'
    Write-Utf8File $game 'game executable fixture'
    Invoke-EldenCraft -SourceRoot $package
    Assert-True ((Get-Hash $save) -eq $saveHash) 'Setup must not modify original saves.'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $data 'minecraft/.first-run-complete'))) 'Headless setup must not claim account sign-in.'
    $instance = Join-Path $data 'minecraft/instances/EldenCraft'
    Assert-True (Test-Path -LiteralPath (Join-Path $instance '.eldencraft-owned')) 'Dedicated instance marker should exist.'
    $campaign = Join-Path $data 'campaign.json'
    Assert-True (Test-Path -LiteralPath $campaign) 'Setup installs editable campaign rules.'
    $campaignRules = Get-Content -LiteralPath $campaign -Raw | ConvertFrom-Json
    $campaignRules.stamina.regenPerSecond = 17
    Write-Utf8File $campaign ($campaignRules | ConvertTo-Json -Depth 14)
    $campaignHash = Get-Hash $campaign
    $link = Get-Content -LiteralPath (Join-Path $instance '.minecraft/config/eldencraft-campaign-link.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-True ([IO.Path]::GetFullPath($link.config) -eq [IO.Path]::GetFullPath($campaign)) 'Minecraft and native host share one campaign rules file.'
    $world = Join-Path $instance '.minecraft/saves/EldenCraft'
    New-Item -ItemType Directory -Force -Path $world | Out-Null
    Write-Utf8File (Join-Path $world 'level.dat') 'existing world fixture'
    Write-Utf8File (Join-Path $instance '.minecraft/options.txt') 'user preferences'
    Write-Utf8File (Join-Path $data 'runtime/EldenCraftPreset.ini') 'custom shader preferences'
    Write-Utf8File (Join-Path $data 'minecraft/accounts.json') 'private account fixture'
    $campaignJournal = Join-Path $data 'runtime/data/campaign/native-ledger.json'
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $campaignJournal) | Out-Null
    Write-Utf8File $campaignJournal '{"characters":{}}'
    Invoke-EldenCraft -SourceRoot $package
    Assert-True ((Get-Content -LiteralPath (Join-Path $world 'level.dat') -Raw) -eq 'existing world fixture') 'Repeat setup must preserve worlds.'
    Assert-True ((Get-Hash $campaign) -eq $campaignHash) 'Repeat setup preserves custom campaign rules.'
    $campaignBackups = @(Get-ChildItem -LiteralPath (Join-Path $data 'backups') -File -Filter 'campaign-*.zip')
    Assert-True ($campaignBackups.Count -eq 1) 'Setup backs up existing campaign journals with paired saves.'
    $campaignZip = [IO.Compression.ZipFile]::OpenRead($campaignBackups[0].FullName)
    try { Assert-True ($null -ne $campaignZip.GetEntry('native-ledger.json')) 'Campaign backup contains the native transaction journal.' }
    finally { $campaignZip.Dispose() }
    Assert-True ((Get-Content -LiteralPath (Join-Path $instance '.minecraft/options.txt') -Raw) -eq 'user preferences') 'Repeat setup must preserve Minecraft settings.'
    Assert-True ((Get-Content -LiteralPath (Join-Path $data 'runtime/EldenCraftPreset.ini') -Raw) -eq 'custom shader preferences') 'Repeat setup must preserve shader settings.'
    Assert-True ((Get-Content -LiteralPath (Join-Path $data 'minecraft/accounts.json') -Raw) -eq 'private account fixture') 'Setup must leave account data untouched.'
    Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $data 'backups') -Directory -Filter 'mods-*').Count -eq 0) 'Unchanged mods should not generate redundant backups.'
    foreach ($snapshot in @(Get-ChildItem -LiteralPath (Join-Path $data 'backups') -File -Filter '*.zip')) {
        & (Join-Path $repo '.venv/Scripts/python.exe') (Join-Path $repo 'tools/backup.py') verify $snapshot.FullName | Out-Null
        Assert-True ($LASTEXITCODE -eq 0) 'PowerShell snapshots must pass the independent Python verifier.'
        $zip = [IO.Compression.ZipFile]::OpenRead($snapshot.FullName)
        try { Assert-True ($null -eq $zip.GetEntry('accounts.json')) 'Backups must exclude account credentials.' }
        finally { $zip.Dispose() }
    }
    $unrelated = Join-Path $scratch 'unrelated-instance'
    New-Item -ItemType Directory -Path $unrelated | Out-Null
    Write-Utf8File (Join-Path $unrelated 'instance.cfg') 'unrelated profile'
    Assert-Throws { Set-MinecraftProfile $release $unrelated 'javaw.exe' $api (Join-Path $data 'backups') } 'unrelated'
    Write-Utf8File (Join-Path $world '_eldencraft_manifest.json') 'reserved'
    $committed = @(Get-ChildItem -LiteralPath (Join-Path $data 'backups') -Filter '*.zip').Count
    Assert-Throws { New-VerifiedBackup $world (Join-Path $data 'backups') 'invalid' } 'reserved'
    Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $data 'backups') -Filter '*.zip').Count -eq $committed) 'Failed backups must not be committed.'
} finally { $env:APPDATA = $oldAppData }

Write-Host "PASS: $script:checks Windows launcher checks; no games launched."
