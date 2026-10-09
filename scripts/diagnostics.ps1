# Standalone Windows PowerShell 5.1 collector. Works without setup or a valid release.
param(
    [string]$DataDirectory,
    [string]$GamePath,
    [string]$OutputDirectory,
    [switch]$NonInteractive
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
foreach ($module in @('Microsoft.PowerShell.Utility', 'Microsoft.PowerShell.Management')) {
    Import-Module (Join-Path $PSHOME "Modules\$module\$module.psd1") -Scope Global -ErrorAction Stop
}
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem

function Protect-SupportText([string]$Text) {
    foreach ($prefix in @($env:USERPROFILE, $env:APPDATA, $env:LOCALAPPDATA)) {
        if ($prefix) {
            $Text = [regex]::Replace($Text, [regex]::Escape($prefix.Replace('\', '\\')), '<USER_PATH>', 'IgnoreCase')
            $Text = [regex]::Replace($Text, [regex]::Escape($prefix.Replace('\', '/')), '<USER_PATH>', 'IgnoreCase')
            $Text = [regex]::Replace($Text, [regex]::Escape($prefix), '<USER_PATH>', 'IgnoreCase')
        }
    }
    $Text = [regex]::Replace($Text, '(?i)[A-Z]:[\\/]+Users[\\/]+[^\\/\r\n" ]+', '<USER_PATH>')
    $Text = [regex]::Replace($Text, '(?i)(--(?:accessToken|clientToken|uuid|username)\s+)(?:"[^"]*"|\S+)', '$1<REDACTED>')
    $Text = [regex]::Replace($Text, '(?i)("?(?:access_?token|refresh_?token|client_?token|password)"?\s*[:=]\s*)(?:"[^"]*"|[^\s,}]+)', '$1"<REDACTED>"')
    $Text = [regex]::Replace($Text, '(?i)(Bearer\s+)\S+', '$1<REDACTED>')
    $Text = [regex]::Replace($Text, '(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b|\b[0-9a-f]{32}\b|\b\d{17}\b', '<ACCOUNT_ID>')
    $Text = [regex]::Replace($Text, '(?im)(\[CHAT\]).*$', '$1 <CHAT REDACTED>')
    $Text = [regex]::Replace($Text, '(?im)(Setting user:|Logged in as)\s*\S+', '$1 <PLAYER>')
    if ($env:USERNAME) { $Text = [regex]::Replace($Text, '\b' + [regex]::Escape($env:USERNAME) + '\b', '<USER>', 'IgnoreCase') }
    return $Text
}

# Do not follow junctions into unrelated data. Missing files are ordinary findings.
function Assert-SupportPath([string]$Path) {
    $cursor = [IO.Path]::GetFullPath($Path)
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw 'Path contains a symbolic link, junction or cloud placeholder.'
            }
        }
        $cursor = [IO.Path]::GetDirectoryName($cursor.TrimEnd('\', '/'))
    }
}

function Get-SupportProperty($Object, [string]$Name, $Fallback = $null) {
    if ($Object -is [Collections.IDictionary] -and $Object.Contains($Name)) { return $Object[$Name] }
    if ($null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name]) { return $Object.$Name }
    return $Fallback
}

function Read-SupportTail([string]$Path, [int]$Limit = 1MB) {
    Assert-SupportPath $Path
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
    try {
        $length = $stream.Length
        $bom = [byte[]]::new(2)
        $bomRead = $stream.Read($bom, 0, 2)
        $encoding = if ($bomRead -eq 2 -and $bom[0] -eq 255 -and $bom[1] -eq 254) { [Text.Encoding]::Unicode } else { [Text.Encoding]::UTF8 }
        $count = [int][Math]::Min($Limit, $length)
        $offset = $length - $count
        if ($encoding -eq [Text.Encoding]::Unicode -and ($offset % 2)) { $offset++; $count-- }
        $stream.Seek($offset, [IO.SeekOrigin]::Begin) | Out-Null
        $buffer = [byte[]]::new($count)
        $read = 0
        while ($read -lt $count) {
            $n = $stream.Read($buffer, $read, $count - $read)
            if ($n -eq 0) { break }
            $read += $n
        }
        # PowerShell transcripts can be UTF-16; game logs are UTF-8.
        $text = $encoding.GetString($buffer, 0, $read).TrimStart([char]0xfeff)
        if ($length -gt $count) {
            $newline = $text.IndexOf("`n")
            if ($newline -ge 0) { $text = $text.Substring($newline + 1) }
            $text = "[Only the most recent $Limit bytes were collected.]`r`n" + $text
        }
        return $text
    } finally { $stream.Dispose() }
}

function Read-SupportJson([string]$Path) {
    Assert-SupportPath $Path
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    if ((Get-Item -LiteralPath $Path).Length -gt 2MB) { throw 'JSON file exceeds the diagnostics limit.' }
    return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Add-SupportFinding($Context, [string]$Level, [string]$Code, [string]$Detail) {
    $Context.findings.Add([ordered]@{ level = $Level; code = $Code; detail = $Detail })
}

function Add-SupportEntry($Context, [string]$Name, [string]$Text) {
    $entry = $Context.zip.CreateEntry($Name, [IO.Compression.CompressionLevel]::Optimal)
    $writer = [IO.StreamWriter]::new($entry.Open(), [Text.UTF8Encoding]::new($false))
    try { $writer.Write((Protect-SupportText $Text)) } finally { $writer.Dispose() }
}

function Add-SupportLog($Context, [string]$Root, [string]$Relative, [string]$Name) {
    $path = [IO.Path]::Combine($Root, $Relative)
    try {
        Assert-SupportPath $path
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            $Context.logs.Add([ordered]@{ file = $Name; state = 'missing' })
            return
        }
        $info = Get-Item -LiteralPath $path
        Add-SupportEntry $Context $Name (Read-SupportTail $path)
        $Context.logs.Add([ordered]@{ file = $Name; state = 'collected'; bytes = $info.Length; modified_utc = $info.LastWriteTimeUtc.ToString('o'); truncated = ($info.Length -gt 1MB) })
    } catch {
        $Context.logs.Add([ordered]@{ file = $Name; state = 'unreadable'; error = $_.Exception.Message })
        Add-SupportFinding $Context 'WARN' 'LOG_UNREADABLE' "$Name : $($_.Exception.Message)"
    }
}

function Add-SupportRecentLogs($Context, [string]$Root, [string]$Relative, [string]$Pattern, [string]$Prefix, [int]$Count = 3) {
    $directory = [IO.Path]::Combine($Root, $Relative)
    try {
        Assert-SupportPath $directory
        if (-not (Test-Path -LiteralPath $directory -PathType Container)) { return }
        foreach ($file in @(Get-ChildItem -LiteralPath $directory -File -Filter $Pattern | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First $Count)) {
            Add-SupportLog $Context $directory $file.Name "$Prefix/$($file.Name)"
        }
    } catch { Add-SupportFinding $Context 'WARN' 'LOG_DIRECTORY_UNREADABLE' "$Prefix : $($_.Exception.Message)" }
}

function Find-SupportGame([string]$Explicit, [string]$Saved, [string]$SourceRoot) {
    if ($Explicit) {
        if (Test-Path -LiteralPath $Explicit -PathType Container) { return [IO.Path]::Combine($Explicit, 'eldenring.exe') }
        return [IO.Path]::GetFullPath($Explicit)
    }
    $candidates = [Collections.Generic.List[string]]::new()
    if ($Saved) { $candidates.Add($Saved) }
    try {
        $local = Read-SupportJson ([IO.Path]::Combine($SourceRoot, 'config/local.json'))
        $gameDir = Get-SupportProperty $local 'elden_ring_game_dir'
        if ($gameDir) { $candidates.Add([IO.Path]::Combine($gameDir, 'eldenring.exe')) }
    } catch { }
    $steam = Get-ItemProperty -LiteralPath 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue
    $steamPath = Get-SupportProperty $steam 'SteamPath'
    if ($steamPath) {
        $libraries = [Collections.Generic.List[string]]::new()
        $libraries.Add($steamPath)
        try {
            $vdf = [IO.Path]::Combine($steamPath, 'steamapps/libraryfolders.vdf')
            if (Test-Path -LiteralPath $vdf) {
                foreach ($match in [regex]::Matches((Read-SupportTail $vdf), '"path"\s+"([^"]+)"')) { $libraries.Add($match.Groups[1].Value.Replace('\\', '\')) }
            }
        } catch { }
        foreach ($library in $libraries) {
            try {
                $acf = [IO.Path]::Combine($library, 'steamapps/appmanifest_1245620.acf')
                if (-not (Test-Path -LiteralPath $acf)) { continue }
                $match = [regex]::Match((Read-SupportTail $acf), '"installdir"\s+"([^"]+)"')
                if ($match.Success) { $candidates.Add([IO.Path]::Combine($library, 'steamapps/common', $match.Groups[1].Value, 'Game/eldenring.exe')) }
            } catch { }
        }
    }
    foreach ($candidate in $candidates) { if (Test-Path -LiteralPath $candidate -PathType Leaf) { return [IO.Path]::GetFullPath($candidate) } }
    return $null
}

function Get-SupportSystem {
    $system = [ordered]@{ windows = [Environment]::OSVersion.Version.ToString(); x64_os = [Environment]::Is64BitOperatingSystem; x64_collector = [Environment]::Is64BitProcess; powershell = $PSVersionTable.PSVersion.ToString() }
    try {
        Import-Module (Join-Path $PSHOME 'Modules/CimCmdlets/CimCmdlets.psd1') -ErrorAction Stop
        $system.gpus = @(Get-CimInstance Win32_VideoController -OperationTimeoutSec 5 | Select-Object Name, DriverVersion)
        $system.memory_gib = [Math]::Round((Get-CimInstance Win32_ComputerSystem -OperationTimeoutSec 5).TotalPhysicalMemory / 1GB, 1)
    } catch { $system.hardware_error = $_.Exception.Message }
    # Names and IDs only. Java command lines can contain account tokens.
    $system.processes = @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.ProcessName -in @('eldenring', 'start_protected_game', 'java', 'javaw', 'steam', 'prismlauncher') -or $_.ProcessName -like 'EasyAntiCheat*' } | Select-Object ProcessName, Id)
    $system.host_modules = @()
    foreach ($process in @(Get-Process -Name eldenring -ErrorAction SilentlyContinue)) {
        try {
            $system.host_modules += @($process.Modules | Where-Object { $_.ModuleName -like 'eldencraft*' -or $_.ModuleName -eq 'ReShade64.asi' } | Select-Object ModuleName, FileName)
            $system.host_modules_checked = $true
        } catch { $system.host_modules_error = $_.Exception.Message }
    }
    return $system
}

function Invoke-SupportCollection {
    param([string]$SourceRoot = (Split-Path -Parent $PSScriptRoot), [string]$DataRoot, [string]$Game, [string]$OutputRoot, [switch]$Headless)
    if (-not $DataRoot) { $DataRoot = Join-Path $env:LOCALAPPDATA 'EldenCraft' }
    if (-not $OutputRoot) { $OutputRoot = Join-Path ([IO.Path]::GetTempPath()) 'EldenCraftDiagnostics' }
    $DataRoot = [IO.Path]::GetFullPath($DataRoot)
    $SourceRoot = [IO.Path]::GetFullPath($SourceRoot)
    $OutputRoot = [IO.Path]::GetFullPath($OutputRoot)
    Assert-SupportPath $OutputRoot
    New-Item -ItemType Directory -Force -Path $OutputRoot | Out-Null
    $id = (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
    $archive = Join-Path $OutputRoot "EldenCraft-support-$id.zip"
    $temporary = "$archive.part"
    $context = @{ findings = [Collections.Generic.List[object]]::new(); logs = [Collections.Generic.List[object]]::new(); zip = [IO.Compression.ZipFile]::Open($temporary, [IO.Compression.ZipArchiveMode]::Create) }
    $report = [ordered]@{ schema_version = 1; bundle_id = $id; collected_utc = [DateTime]::UtcNow.ToString('o'); data_directory = $DataRoot; release_directory = $SourceRoot }
    try {
        Write-Host 'Collecting EldenCraft diagnostics (no downloads or game changes)...'
        $releaseRoot = $SourceRoot
        try {
            $pointer = [IO.Path]::Combine($SourceRoot, '.local/releases/current.txt')
            if (-not (Test-Path -LiteralPath (Join-Path $SourceRoot 'release-manifest.json')) -and (Test-Path -LiteralPath $pointer)) {
                $name = (Read-SupportTail $pointer 1024).Trim()
                if ($name -notmatch '^EldenCraft-\d+\.\d+\.\d+-[a-f0-9]{12}$') { throw 'Invalid local release pointer.' }
                $releaseRoot = Join-Path (Split-Path -Parent $pointer) $name
            }
            $report.release_directory = $releaseRoot
        } catch { Add-SupportFinding $context 'WARN' 'RELEASE_POINTER' $_.Exception.Message }
        $manifest = $null
        $deps = $null
        $expected = @{}
        try {
            $manifest = Read-SupportJson (Join-Path $releaseRoot 'release-manifest.json')
            if (-not $manifest -or (Get-SupportProperty $manifest 'schema_version') -ne 1) { throw 'Release manifest is missing or unsupported. Extract the complete release ZIP.' }
            $report.release_version = Get-SupportProperty $manifest 'version' 'unknown'
            foreach ($file in @((Get-SupportProperty $manifest 'files' @()))) { $expected[[string]$file.path] = $file }
        } catch { Add-SupportFinding $context 'WARN' 'RELEASE_MANIFEST' $_.Exception.Message }
        try { $deps = Read-SupportJson (Join-Path $releaseRoot 'config/windows-release.json') }
        catch { Add-SupportFinding $context 'WARN' 'DEPENDENCY_CONFIG' $_.Exception.Message }
        $artifacts = [Collections.Generic.List[object]]::new()
        # Fixed allowlist: a changed manifest cannot make the collector read arbitrary files.
        foreach ($relative in @('EldenCraft.cmd', 'Troubleshoot.cmd', 'scripts/windows.ps1', 'scripts/diagnostics.ps1', 'config/windows-release.json', 'config/campaign.json', 'payload/eldencraft_native.dll', 'payload/eldencraft_core.dll', 'payload/addons/EldenCraftCompositor.addon64', 'payload/eldencraft-bridge.jar', 'payload/shaders/EldenCraftPassthrough.fx')) {
            $path = Join-Path $releaseRoot $relative
            $record = [ordered]@{ file = $relative; state = 'missing' }
            try {
                Assert-SupportPath $path
                if (Test-Path -LiteralPath $path -PathType Leaf) {
                    if ((Get-Item -LiteralPath $path).Length -gt 64MB) { throw 'Artifact exceeds the diagnostics limit.' }
                    $record.sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
                    $record.state = if ($expected.ContainsKey($relative) -and $record.sha256 -eq $expected[$relative].sha256) { 'verified' } else { 'unverified' }
                    if ($manifest -and -not $expected.ContainsKey($relative)) { Add-SupportFinding $context 'WARN' 'MANIFEST_ENTRY_MISSING' "$relative is not listed in this release manifest. Check that the intended release was extracted." }
                    if ($expected.ContainsKey($relative) -and $record.state -ne 'verified') {
                        $advice = if ($relative -eq 'config/campaign.json') { 'Restore the bundled template; edit the runtime campaign.json instead.' } else { 'Extract a fresh release ZIP; check antivirus quarantine.' }
                        Add-SupportFinding $context 'FAIL' 'RELEASE_FILE_CHANGED' "$relative differs from the release. $advice"
                    }
                } else { Add-SupportFinding $context 'WARN' 'RELEASE_FILE_MISSING' "$relative is missing. Extract the complete release ZIP; check antivirus quarantine." }
            } catch { $record.state = 'unreadable'; Add-SupportFinding $context 'WARN' 'RELEASE_FILE_UNREADABLE' "$relative : $($_.Exception.Message)" }
            $artifacts.Add($record)
        }
        $report.release_files = @($artifacts.ToArray())
        $settings = $null
        try { $settings = Read-SupportJson (Join-Path $DataRoot 'settings.json') }
        catch { Add-SupportFinding $context 'WARN' 'SETTINGS_UNREADABLE' $_.Exception.Message }
        try {
            $gameExe = Find-SupportGame $Game (Get-SupportProperty $settings 'game_executable') $SourceRoot
            if (-not $gameExe -or -not (Test-Path -LiteralPath $gameExe -PathType Leaf)) { throw 'Elden Ring was not found. Pass -GamePath with the Steam installation to diagnose it.' }
            Assert-SupportPath $gameExe
            if ((Get-Item -LiteralPath $gameExe).Length -gt 1GB) { throw 'Game executable exceeds the diagnostics limit.' }
            $gameHash = (Get-FileHash -LiteralPath $gameExe -Algorithm SHA256).Hash.ToLowerInvariant()
            $gameVersion = (Get-Item -LiteralPath $gameExe).VersionInfo.ProductVersion
            $gameDeps = Get-SupportProperty $deps 'elden_ring'
            $report.game = [ordered]@{ path = $gameExe; product_version = $gameVersion; sha256 = $gameHash; expected_version = (Get-SupportProperty $gameDeps 'product_version'); expected_sha256 = (Get-SupportProperty $gameDeps 'sha256') }
            if ($gameDeps -and $gameHash -ne $gameDeps.sha256) { Add-SupportFinding $context 'FAIL' 'GAME_BUILD_MISMATCH' "Elden Ring $gameVersion differs from the supported $($gameDeps.product_version) executable. Update and verify Elden Ring in Steam; check for a second installation." }
            $gameDir = Split-Path -Parent $gameExe
            foreach ($dll in @('dxgi.dll', 'd3d12.dll', 'd3d11.dll', 'd3d9.dll', 'opengl32.dll')) {
                $proxy = Join-Path $gameDir $dll
                Assert-SupportPath $proxy
                if ((Test-Path -LiteralPath $proxy) -and (Get-Item -LiteralPath $proxy).VersionInfo.ProductName -eq 'ReShade') { Add-SupportFinding $context 'FAIL' 'MANUAL_RESHADE' "Game/$dll is a separate ReShade installation. Remove that manual installation; EldenCraft supplies its own ReShade." }
            }
            $gameIni = Join-Path $gameDir 'ReShade.ini'
            if (Test-Path -LiteralPath $gameIni) {
                if ((Read-SupportTail $gameIni) -match '(?ims)^\s*\[INSTALL\][^\[]*?^\s*BasePath\s*=\s*(\S[^\r\n]*)') { Add-SupportFinding $context 'WARN' 'RESHADE_BASE_PATH' 'Game/ReShade.ini has an INSTALL BasePath override. Check whether it points at an old manual installation.' }
            }
            Add-SupportLog $context $gameDir 'ReShade.log' 'logs/game-folder-ReShade.log'
        } catch { Add-SupportFinding $context 'WARN' 'GAME_CHECK' $_.Exception.Message }
        try {
            Assert-SupportPath $DataRoot
            $report.data_exists = Test-Path -LiteralPath $DataRoot -PathType Container
            if (-not $report.data_exists) { Add-SupportFinding $context 'WARN' 'DATA_MISSING' 'Runtime data does not exist here. Setup may not have reached installation, or this launch used a custom -DataDirectory.' }
        } catch { Add-SupportFinding $context 'WARN' 'DATA_PATH' $_.Exception.Message }
        $runtime = Join-Path $DataRoot 'runtime'
        $minecraft = Join-Path $DataRoot 'minecraft/instances/EldenCraft/.minecraft'
        Add-SupportRecentLogs $context $DataRoot 'logs' 'launcher-*.log' 'logs/launcher'
        Add-SupportRecentLogs $context $DataRoot 'logs' 'me3-*.stdout.log' 'logs/me3'
        Add-SupportRecentLogs $context $DataRoot 'logs' 'me3-*.stderr.log' 'logs/me3'
        Add-SupportLog $context $runtime 'ReShade.log' 'logs/ReShade.log'
        Add-SupportLog $context $runtime 'data/eldencraft-loader.log' 'logs/eldencraft-loader.log'
        Add-SupportLog $context $runtime 'data/eldencraft-native.log' 'logs/eldencraft-native.log'
        Add-SupportLog $context $minecraft 'logs/latest.log' 'logs/minecraft-latest.log'
        Add-SupportRecentLogs $context $runtime 'data/crash' 'eldencraft-crash-*.txt' 'logs/native-crash' 2
        Add-SupportRecentLogs $context $minecraft 'crash-reports' 'crash-*.txt' 'logs/minecraft-crash' 2
        $runtimeFiles = [Collections.Generic.List[object]]::new()
        foreach ($mapping in @(@('eldencraft_native.dll', 'payload/eldencraft_native.dll'), @('eldencraft_core.dll', 'payload/eldencraft_core.dll'), @('addons/EldenCraftCompositor.addon64', 'payload/addons/EldenCraftCompositor.addon64'), @('ReShade64.asi', 'reshade'))) {
            $path = Join-Path $runtime $mapping[0]
            $runtimeFile = [ordered]@{ file = $mapping[0]; state = 'missing' }
            $runtimeFiles.Add($runtimeFile)
            try {
                Assert-SupportPath $path
                if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { Add-SupportFinding $context 'WARN' 'RUNTIME_FILE_MISSING' "runtime/$($mapping[0]) is missing. Setup may be incomplete; check antivirus quarantine."; continue }
                $targetHash = if ($mapping[1] -eq 'reshade') { Get-SupportProperty (Get-SupportProperty $deps 'reshade') 'runtime_sha256' } elseif ($expected.ContainsKey($mapping[1])) { $expected[$mapping[1]].sha256 } else { $null }
                if ((Get-Item -LiteralPath $path).Length -gt 64MB) { throw 'Runtime artifact exceeds the diagnostics limit.' }
                $runtimeFile.sha256 = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
                $runtimeFile.modified_utc = (Get-Item -LiteralPath $path).LastWriteTimeUtc.ToString('o')
                $runtimeFile.expected_sha256 = $targetHash
                $runtimeFile.state = if (-not $targetHash) { 'unverified' } elseif ($runtimeFile.sha256 -ieq $targetHash) { 'verified' } else { 'mismatch' }
                if ($runtimeFile.state -eq 'mismatch') { Add-SupportFinding $context 'FAIL' 'STALE_RUNTIME' "runtime/$($mapping[0]) differs from this release. Close both games and run EldenCraft.cmd setup from the intended release." }
            } catch { $runtimeFile.state = 'unreadable'; Add-SupportFinding $context 'WARN' 'RUNTIME_FILE_UNREADABLE' "$($mapping[0]) : $($_.Exception.Message)" }
        }
        $report.runtime_files = @($runtimeFiles.ToArray())
        $mods = [Collections.Generic.List[object]]::new()
        try {
            $modsDir = Join-Path $minecraft 'mods'
            Assert-SupportPath $modsDir
            if (Test-Path -LiteralPath $modsDir -PathType Container) {
                $jars = @(Get-ChildItem -LiteralPath $modsDir -File -Filter '*.jar' | Select-Object -First 128)
                foreach ($jar in $jars) {
                    $mod = [ordered]@{ file = $jar.Name }
                    if ($jar.Name -like 'eldencraft*') {
                        Assert-SupportPath $jar.FullName
                        $z = [IO.Compression.ZipFile]::OpenRead($jar.FullName)
                        try {
                            $entry = $z.GetEntry('fabric.mod.json')
                            if (-not $entry -or $entry.Length -gt 64KB) { throw 'Bridge has no readable Fabric metadata.' }
                            $reader = [IO.StreamReader]::new($entry.Open())
                            try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
                            $mod.id = Get-SupportProperty $metadata 'id'
                            $mod.version = Get-SupportProperty $metadata 'version'
                            if ($manifest -and $mod.version -ne $manifest.version) { Add-SupportFinding $context 'FAIL' 'STALE_BRIDGE' "Minecraft has bridge $($mod.version), release is $($manifest.version). Close both games and rerun setup; launch the dedicated EldenCraft instance." }
                            if ((Get-Item -LiteralPath $jar.FullName).Length -gt 64MB) { throw 'Bridge artifact exceeds the diagnostics limit.' }
                            $mod.sha256 = (Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
                            $mod.modified_utc = $jar.LastWriteTimeUtc.ToString('o')
                            if ($expected.ContainsKey('payload/eldencraft-bridge.jar') -and $mod.sha256 -ine $expected['payload/eldencraft-bridge.jar'].sha256) { Add-SupportFinding $context 'FAIL' 'BRIDGE_FILE_MISMATCH' 'The installed bridge JAR differs from this package, even if its version is the same. Restart both games after setup from the intended package.' }
                        } finally { $z.Dispose() }
                    }
                    $mods.Add($mod)
                }
            }
            $bridges = @($mods.ToArray() | Where-Object { $_.file -like 'eldencraft*' })
            if ($bridges.Count -eq 0) { Add-SupportFinding $context 'WARN' 'BRIDGE_MISSING' 'No EldenCraft bridge was found in the dedicated Minecraft instance. Run setup or check that the correct data directory was selected.' }
            elseif ($bridges.Count -gt 1) { Add-SupportFinding $context 'FAIL' 'DUPLICATE_BRIDGE' 'Multiple EldenCraft JARs are installed. Close both games and run setup to replace the mods in the dedicated instance.' }
        } catch { Add-SupportFinding $context 'WARN' 'MOD_CHECK' $_.Exception.Message }
        $report.minecraft_mods = @($mods.ToArray())
        # Only known configuration files; never recurse through accounts, saves or campaign state.
        foreach ($config in @(@($runtime, 'ReShade.ini', 'config/ReShade.ini'), @($runtime, 'EldenCraftPreset.ini', 'config/EldenCraftPreset.ini'), @($runtime, 'eldencraft.me3', 'config/eldencraft.me3'), @($DataRoot, 'campaign.json', 'config/campaign.json'), @($minecraft, 'config/eldencraft-host.json', 'config/eldencraft-host.json'), @($minecraft, 'config/eldencraft-campaign-link.json', 'config/eldencraft-campaign-link.json'))) {
            Add-SupportLog $context $config[0] $config[1] $config[2]
        }
        Add-SupportLog $context (Split-Path -Parent $minecraft) 'mmc-pack.json' 'config/mmc-pack.json'
        foreach ($name in @('logs/eldencraft-loader.log', 'logs/eldencraft-native.log', 'logs/ReShade.log', 'logs/minecraft-latest.log')) {
            $log = @($context.logs.ToArray() | Where-Object { $_.file -eq $name })
            if ($log.Count -and $log[0].state -ne 'collected') { Add-SupportFinding $context 'WARN' 'COMPONENT_LOG_MISSING' "$name was not collected. This component may not have started, or the data directory differs. See launcher/me3 logs first." }
        }
        $report.system = Get-SupportSystem
        if ((Get-SupportProperty $report.system 'host_modules_checked' $false) -and -not @($report.system.host_modules | Where-Object { $_.ModuleName -eq 'eldencraft_native.dll' }).Count) { Add-SupportFinding $context 'WARN' 'NATIVE_NOT_LOADED' 'Elden Ring is running but its EldenCraft native loader is not loaded. Reproduce by launching through EldenCraft.cmd and review the me3 logs.' }
        if (-not $report.system.x64_os -or -not $report.system.x64_collector -or ($deps -and [Environment]::OSVersion.Version.Build -lt $deps.windows_min_build)) { Add-SupportFinding $context 'FAIL' 'WINDOWS_UNSUPPORTED' 'This release requires x64 Windows 10/11 build 19041 or newer and the x64 collector.' }
        if (-not @($report.system.processes | Where-Object { $_.ProcessName -eq 'steam' }).Count) { Add-SupportFinding $context 'INFO' 'STEAM_NOT_RUNNING' 'Steam was not running at collection time. Start Steam before reproducing launch problems.' }
        $report.findings = @($context.findings.ToArray())
        $report.files = @($context.logs.ToArray())
        $summary = [Collections.Generic.List[string]]::new()
        $summary.Add("EldenCraft support bundle $id")
        $summary.Add("Collected UTC: $($report.collected_utc)")
        $summary.Add("Release: $(Get-SupportProperty $manifest 'version' 'unknown')")
        $summary.Add("Data directory: $DataRoot")
        $summary.Add('')
        $summary.Add('Findings (file checks are conclusive; missing logs are clues):')
        foreach ($finding in $context.findings) { $summary.Add("[$($finding.level)] $($finding.code): $($finding.detail)") }
        if (-not $context.findings.Count) { $summary.Add('No installation issue was detected. Review the logs for the gameplay symptom.') }
        $summary.Add('')
        $summary.Add("Windows: $($report.system.windows); PowerShell: $($report.system.powershell)")
        if ($report.Contains('game')) { $summary.Add("Elden Ring: $($report.game.product_version); SHA256: $($report.game.sha256)") }
        foreach ($gpu in @((Get-SupportProperty $report.system 'gpus' @()))) { $summary.Add("GPU: $($gpu.Name); driver: $($gpu.DriverVersion)") }
        foreach ($mod in $mods) { $summary.Add("Minecraft mod: $($mod.file) $(Get-SupportProperty $mod 'version' '')") }
        $summary.Add('')
        $summary.Add('Collected files (UTC times distinguish old logs from the failed run):')
        foreach ($log in $context.logs) { $summary.Add("$($log.file): $($log.state) $(Get-SupportProperty $log 'modified_utc' '')") }
        $summary.Add('')
        $summary.Add('Send this ZIP and a short description: what happened, what you expected, and how to reproduce it. Mention any custom data directory or other Minecraft profile.')
        $summary.Add('Collection makes no game changes and sends nothing online. Saves, account files, memory dumps, process arguments and chat content are excluded. Common paths, account identifiers and tokens are redacted; review the contents before posting publicly.')
        Add-SupportEntry $context 'SUMMARY.txt' ($summary -join "`r`n")
        Add-SupportEntry $context 'report.json' ($report | ConvertTo-Json -Depth 12)
    } finally { $context.zip.Dispose() }
    # Only publish after the central directory has been written successfully.
    [IO.File]::Move($temporary, $archive)
    Write-Host "Support ZIP: $archive" -ForegroundColor Green
    Write-Host 'Send this ZIP with a short description of the problem. SUMMARY.txt contains the findings.'
    if (-not $Headless) {
        try { Start-Process -FilePath (Join-Path $env:SystemRoot 'explorer.exe') -ArgumentList ('/select,"' + $archive + '"') | Out-Null }
        catch { Write-Host 'Open the Support ZIP path above to find the bundle.' }
    }
    return $archive
}

if ($MyInvocation.InvocationName -ne '.') {
    try { Invoke-SupportCollection -DataRoot $DataDirectory -Game $GamePath -OutputRoot $OutputDirectory -Headless:$NonInteractive | Out-Null; exit 0 }
    catch { Write-Host "EldenCraft diagnostics: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
}
