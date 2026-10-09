# Real ZIP, log-sharing, redaction and broken-installation checks; no game launches.
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repo = Split-Path -Parent $PSScriptRoot
. (Join-Path $repo 'scripts/diagnostics.ps1')
$scratch = Join-Path $repo ('.local/diagnostics-tests/' + [guid]::NewGuid().ToString('N'))
$package = Join-Path $scratch 'release with spaces'
$data = Join-Path $scratch 'data with spaces'
$output = Join-Path $scratch 'output'
$script:checks = 0

function Assert-True([bool]$Value, [string]$Message) {
    $script:checks++
    if (-not $Value) { throw "Assertion failed: $Message" }
}
function Write-Fixture([string]$Path, [string]$Text) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}
function New-Bridge([string]$Path, [string]$Version) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    $zip = [IO.Compression.ZipFile]::Open($Path, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $writer = [IO.StreamWriter]::new($zip.CreateEntry('fabric.mod.json').Open())
        try { $writer.Write((@{ id = 'eldencraft_bridge'; version = $Version } | ConvertTo-Json)) }
        finally { $writer.Dispose() }
    } finally { $zip.Dispose() }
}
function Read-ZipText($Zip, [string]$Name) {
    $reader = [IO.StreamReader]::new($Zip.GetEntry($Name).Open())
    try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}
function Read-Bundle([string]$Path) {
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try { return (Read-ZipText $zip 'report.json' | ConvertFrom-Json) } finally { $zip.Dispose() }
}
# Deterministic fixtures bypass hardware queries, not file checks or collection.
function Get-SupportSystem { return [ordered]@{ windows = '10.0.19045'; x64_os = $true; x64_collector = $true; powershell = $PSVersionTable.PSVersion.ToString(); processes = @([pscustomobject]@{ ProcessName = 'steam'; Id = 42 }) } }

foreach ($relative in @('EldenCraft.cmd', 'Troubleshoot.cmd', 'scripts/windows.ps1', 'scripts/diagnostics.ps1', 'config/campaign.json', 'payload/eldencraft_native.dll', 'payload/eldencraft_core.dll', 'payload/addons/EldenCraftCompositor.addon64', 'payload/shaders/EldenCraftPassthrough.fx')) {
    Write-Fixture (Join-Path $package $relative) "fixture $relative"
}
$game = Join-Path $scratch 'Game/eldenring.exe'
Write-Fixture $game 'supported game fixture'
$dependencies = Get-Content -LiteralPath (Join-Path $repo 'config/windows-release.json') -Raw | ConvertFrom-Json
$dependencies.elden_ring.sha256 = (Get-FileHash -LiteralPath $game).Hash.ToLowerInvariant()
Write-Fixture (Join-Path $package 'config/windows-release.json') ($dependencies | ConvertTo-Json -Depth 8)
$builtBridge = Join-Path $package 'payload/eldencraft-bridge.jar'
New-Bridge $builtBridge '0.24.1'
$files = @(Get-ChildItem -LiteralPath $package -Recurse -File | ForEach-Object { @{ path = $_.FullName.Substring($package.Length + 1).Replace('\', '/'); size = $_.Length; sha256 = (Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant() } })
Write-Fixture (Join-Path $package 'release-manifest.json') (@{ schema_version = 1; version = '0.24.1'; files = $files } | ConvertTo-Json -Depth 8)
Write-Fixture (Join-Path $data 'settings.json') (@{ game_executable = $game } | ConvertTo-Json)
$launcherLog = Join-Path $data 'logs/launcher-20261008-120000.log'
$secretText = @"
Launcher: $($env:USERPROFILE)\private\file.txt
--accessToken super-secret-launch-token --username PrivatePlayer
{"refresh_token":"super-secret-refresh-token"}
Bearer super-secret-bearer
12345678-1234-1234-1234-123456789abc 12345678901234567
[CHAT] private chat fixture
Setting user: PrivatePlayer
Unsupported Elden Ring 2.7.0.0; SHA256 $($dependencies.elden_ring.sha256)
"@
Write-Fixture $launcherLog $secretText
Write-Fixture (Join-Path $data 'logs/do-not-collect.log') 'unrelated private fixture'
Write-Fixture (Join-Path $data 'minecraft/accounts.json') 'account credentials fixture'
Write-Fixture (Join-Path $data 'runtime/data/crash/eldencraft-crash-1.dmp') 'private memory fixture'
Write-Fixture (Join-Path $data 'minecraft/instances/EldenCraft/.minecraft/saves/EldenCraft/level.dat') 'save fixture'
Write-Fixture (Join-Path $data 'runtime/data/campaign/state.json') 'private campaign state fixture'
$initialHash = (Get-FileHash -LiteralPath $launcherLog).Hash
$heldLog = [IO.File]::Open($launcherLog, [IO.FileMode]::Open, [IO.FileAccess]::Write, [IO.FileShare]::ReadWrite)
try { $bundle = Invoke-SupportCollection -SourceRoot $package -DataRoot $data -OutputRoot $output -Headless }
finally { $heldLog.Dispose() }
Assert-True (Test-Path -LiteralPath $bundle) 'A live, shared log must be collectable.'
Assert-True ((Get-FileHash -LiteralPath $launcherLog).Hash -eq $initialHash) 'Collection must not modify logs.'
$zip = [IO.Compression.ZipFile]::OpenRead($bundle)
try {
    $text = Read-ZipText $zip 'logs/launcher/launcher-20261008-120000.log'
    foreach ($secret in @('super-secret-launch-token', 'super-secret-refresh-token', 'super-secret-bearer', 'PrivatePlayer', 'private chat fixture', $env:USERPROFILE, '12345678901234567', '12345678-1234-1234-1234-123456789abc')) { Assert-True (-not $text.Contains($secret)) "Must redact $secret" }
    Assert-True ($text.Contains('2.7.0.0')) 'Version numbers remain useful.'
    Assert-True ($text.Contains($dependencies.elden_ring.sha256)) 'Executable hashes remain useful.'
    $report = Read-ZipText $zip 'report.json' | ConvertFrom-Json
    Assert-True (@($report.findings | Where-Object { $_.code -eq 'COMPONENT_LOG_MISSING' }).Count -eq 4) 'Empty native/Minecraft log folders must produce useful clues.'
    Assert-True (@($report.release_files | Where-Object { $_.state -ne 'verified' }).Count -eq 0) 'Release payload fixture must verify.'
    Assert-True ((Read-ZipText $zip 'SUMMARY.txt').Contains('2026-')) 'Summary includes UTC log timestamps.'
    foreach ($entry in $zip.Entries) { Assert-True ($entry.FullName -notmatch 'accounts|level.dat|\.dmp$|state.json|do-not-collect') 'Private and unrelated data must stay out of the bundle.' }
} finally { $zip.Dispose() }

# Old/duplicate Minecraft bridges, changed template, stale native DLL and wrong game.
$modsDir = Join-Path $data 'minecraft/instances/EldenCraft/.minecraft/mods'
New-Bridge (Join-Path $modsDir 'eldencraft-bridge-0.20.1.jar') '0.20.1'
Copy-Item -LiteralPath $builtBridge -Destination (Join-Path $modsDir 'eldencraft-bridge-0.24.1.jar')
Write-Fixture (Join-Path $data 'runtime/eldencraft_core.dll') 'stale native fixture'
Write-Fixture (Join-Path $package 'config/campaign.json') '{}'
Write-Fixture $game 'wrong game fixture'
$bundle = Invoke-SupportCollection -SourceRoot $package -DataRoot $data -OutputRoot $output -Headless
$report = Read-Bundle $bundle
foreach ($code in @('STALE_BRIDGE', 'BRIDGE_FILE_MISMATCH', 'DUPLICATE_BRIDGE', 'STALE_RUNTIME', 'GAME_BUILD_MISMATCH', 'RELEASE_FILE_CHANGED')) { Assert-True (@($report.findings | Where-Object { $_.code -eq $code }).Count -gt 0) "Must detect $code" }

# Incomplete release and corrupt settings must still yield a support ZIP.
$broken = Join-Path $scratch 'broken release'
New-Item -ItemType Directory -Path $broken | Out-Null
Write-Fixture (Join-Path $data 'settings.json') '{'
$bundle = Invoke-SupportCollection -SourceRoot $broken -DataRoot $data -Game $game -OutputRoot $output -Headless
$report = Read-Bundle $bundle
foreach ($code in @('RELEASE_MANIFEST', 'SETTINGS_UNREADABLE')) { Assert-True (@($report.findings | Where-Object { $_.code -eq $code }).Count -gt 0) "Still collect after $code" }
Assert-True (@($report.files | Where-Object { $_.file -like 'logs/launcher/*' -and $_.state -eq 'collected' }).Count -eq 1) 'Bootstrap failures do not prevent collecting launcher evidence.'

# Bounded tails preserve the newest lines, including UTF-16 PowerShell transcripts.
$large = Join-Path $scratch 'large.log'
[IO.File]::WriteAllText($large, ('old line' + "`n") * 200000 + "most recent line`n", [Text.Encoding]::UTF8)
$tail = Read-SupportTail $large 4096
Assert-True ($tail.Length -lt 4300 -and $tail.Contains('most recent line')) 'UTF-8 log tails are bounded and recent.'
[IO.File]::WriteAllText($large, ('old line' + "`n") * 200000 + "most recent unicode line`n", [Text.Encoding]::Unicode)
$tail = Read-SupportTail $large 4097
Assert-True ($tail.Length -lt 4300 -and $tail.Contains('most recent unicode line') -and -not $tail.Contains([char]0)) 'UTF-16 tail detection works without a BOM at the tail.'
$unicode = Join-Path $scratch ('unicode-' + [char]0x142 + [char]0xf3 + 'd' + [char]0x17a + '.log')
Write-Fixture $unicode 'unicode path fixture'
Assert-True ((Read-SupportTail $unicode) -eq 'unicode path fixture') 'Non-ASCII paths work.'

# A changed manifest cannot add accounts to the fixed file allowlist.
Write-Fixture (Join-Path $broken 'accounts.json') 'private account marker'
Write-Fixture (Join-Path $broken 'release-manifest.json') '{"schema_version":1,"version":"0.24.1","files":[{"path":"accounts.json","sha256":"bad"}]}'
$bundle = Invoke-SupportCollection -SourceRoot $broken -DataRoot $data -Game $game -OutputRoot $output -Headless
$zip = [IO.Compression.ZipFile]::OpenRead($bundle)
try { Assert-True ($null -eq $zip.GetEntry('accounts.json')) 'Manifest-controlled files are never copied.' } finally { $zip.Dispose() }

# Links cannot redirect allowlisted log paths into private files.
$linkedData = Join-Path $scratch 'linked-data'
$private = Join-Path $scratch 'private'
Write-Fixture (Join-Path $private 'launcher-20261008.log') 'junction private marker'
New-Item -ItemType Directory -Path $linkedData | Out-Null
New-Item -ItemType Junction -Path (Join-Path $linkedData 'logs') -Target $private | Out-Null
$bundle = Invoke-SupportCollection -SourceRoot $broken -DataRoot $linkedData -Game $game -OutputRoot $output -Headless
$report = Read-Bundle $bundle
Assert-True (@($report.findings | Where-Object { $_.code -eq 'LOG_DIRECTORY_UNREADABLE' }).Count -gt 0) 'A junction is reported while other diagnostics finish.'
Assert-True (@($report.files | Where-Object { $_.file -like 'logs/launcher/*' -and $_.state -eq 'collected' }).Count -eq 0) 'A junction cannot redirect log collection.'

# The standalone CMD needs neither windows.ps1 nor a working release manifest.
$cliRoot = Join-Path $scratch 'standalone CLI'
New-Item -ItemType Directory -Path (Join-Path $cliRoot 'scripts') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $repo 'Troubleshoot.cmd') -Destination $cliRoot
Copy-Item -LiteralPath (Join-Path $repo 'scripts/diagnostics.ps1') -Destination (Join-Path $cliRoot 'scripts')
function Invoke-FixtureCmd([string]$Command, [string]$Mode = '') {
    $arguments = '/d /c ""' + (Join-Path $cliRoot $Command) + '" ' + $Mode + ' -DataDirectory "' + $data + '" -GamePath "' + $game + '" -OutputDirectory "' + $output + '" -NonInteractive"'
    $start = [Diagnostics.ProcessStartInfo]::new((Join-Path $env:SystemRoot 'System32/cmd.exe'), $arguments)
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($start)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        Assert-True ($process.WaitForExit(30000)) 'Cold CMD diagnostics finish.'
        $text = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        Assert-True ($process.ExitCode -eq 0) "Cold CMD reports success: $text"
        Assert-True ($text.Contains('Support ZIP:')) 'Cold CMD prints the bundle path.'
    } finally { $process.Dispose() }
}
Invoke-FixtureCmd 'Troubleshoot.cmd'
Copy-Item -LiteralPath (Join-Path $repo 'EldenCraft.cmd') -Destination $cliRoot
Copy-Item -LiteralPath (Join-Path $repo 'scripts/windows.ps1') -Destination (Join-Path $cliRoot 'scripts')
Invoke-FixtureCmd 'EldenCraft.cmd' 'diagnose'

Write-Host "PASS: $script:checks diagnostics checks; no games launched."
