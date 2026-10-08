# Windows PowerShell 5.1; the release launcher needs no system-wide installations.
param(
    [Parameter(Position = 0)]
    [ValidateSet('play', 'setup', 'check')]
    [string]$Mode = 'play',
    [string]$GamePath,
    [string]$DataDirectory,
    [switch]$CpuFrames,
    [switch]$NonInteractive
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
# Load the built-in modules explicitly: cold CMD launches must not depend on a
# caller's module-analysis cache or imports made by an interactive shell.
foreach ($module in @('Microsoft.PowerShell.Utility', 'Microsoft.PowerShell.Management', 'CimCmdlets')) {
    Import-Module (Join-Path $PSHOME "Modules\$module\$module.psd1") -Scope Global -ErrorAction Stop
}

function Get-SafeChildPath([string]$Root, [string]$Relative) {
    $anchor = [IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    if ([string]::IsNullOrWhiteSpace($Relative) -or [IO.Path]::IsPathRooted($Relative)) {
        throw "Invalid relative path: $Relative"
    }
    foreach ($part in ($Relative -split '[\\/]')) {
        if ($part -in @('', '.', '..') -or $part -match '[<>:"|?*\x00-\x1f]' -or
            $part -match '[. ]$' -or $part -match '^(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)') {
            throw "Unsafe relative path: $Relative"
        }
    }
    $candidate = [IO.Path]::GetFullPath((Join-Path $anchor $Relative))
    if (-not $candidate.StartsWith($anchor + '\', [StringComparison]::OrdinalIgnoreCase)) {
        throw "Path escaped its destination: $Relative"
    }
    $cursor = $candidate
    while ($cursor.Length -ge $anchor.Length) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "A symbolic link, junction or cloud placeholder is not supported here: $cursor. Extract the full release to an ordinary local folder such as C:\Games\EldenCraft. For linked runtime data, pass -DataDirectory C:\Games\EldenCraftData."
            }
        }
        $cursor = Split-Path -Parent $cursor
        if (-not $cursor) { break }
    }
    return $candidate
}

function Assert-FileHash([string]$Path, [string]$Expected) {
    if ($Expected -notmatch '^[a-fA-F0-9]{64}$' -or
        -not (Test-Path -LiteralPath $Path -PathType Leaf) -or
        (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ine $Expected) {
        throw "Checksum verification failed: $Path"
    }
}

function Write-Utf8File([string]$Path, [string]$Text) {
    $temporary = "$Path.tmp-$([guid]::NewGuid().ToString('N'))"
    [IO.File]::WriteAllText($temporary, $Text, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $Path -Force
}

function Read-Release([string]$Root) {
    $manifest = Get-Content -LiteralPath (Join-Path $Root 'release-manifest.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($manifest.schema_version -ne 1 -or $manifest.version -notmatch '^\d+\.\d+\.\d+$') {
        throw 'Unsupported release manifest.'
    }
    $seen = @{}
    foreach ($file in $manifest.files) {
        if ($seen.ContainsKey($file.path)) { throw "Duplicate release path: $($file.path)" }
        $seen[$file.path] = $true
        $path = Get-SafeChildPath $Root $file.path
        try { Assert-FileHash $path $file.sha256 }
        catch {
            if ($file.path -eq 'config/campaign.json') {
                $dataRoot = if ($DataDirectory) { $DataDirectory } else { Join-Path $env:LOCALAPPDATA 'EldenCraft' }
                throw "The bundled campaign template was changed: $path. Restore it from the release ZIP, then edit the runtime copy at $(Join-Path $dataRoot 'campaign.json'). Setup preserves that copy. Restart both games after editing."
            }
            throw
        }
        if ((Get-Item -LiteralPath $path).Length -ne $file.size) { throw "Wrong file size: $path" }
    }
    foreach ($required in @('scripts/windows.ps1', 'config/windows-release.json', 'config/campaign.json',
        'payload/eldencraft_native.dll', 'payload/eldencraft_core.dll', 'payload/eldencraft-bridge.jar',
        'payload/addons/EldenCraftCompositor.addon64', 'payload/shaders/EldenCraftPassthrough.fx',
        'payload/EldenCraftPreset.ini')) {
        if (-not $seen.ContainsKey($required)) { throw "Incomplete release: $required" }
    }
    $dependencies = Get-Content -LiteralPath (Join-Path $Root 'config/windows-release.json') -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($dependencies.schema_version -ne 1) { throw 'Unsupported dependency manifest.' }
    return [pscustomobject]@{ Root = $Root; Version = $manifest.version; Dependencies = $dependencies }
}

function Get-VerifiedDownload([string]$Cache, [string]$Name, [string]$Url, [string]$Hash) {
    $path = Get-SafeChildPath $Cache $Name
    if (Test-Path -LiteralPath $path) {
        Assert-FileHash $path $Hash
        return $path
    }
    if (([uri]$Url).Scheme -ne 'https') { throw 'Dependencies must use HTTPS.' }
    New-Item -ItemType Directory -Force -Path $Cache | Out-Null
    $temporary = "$path.part"
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        try {
            Write-Host "Downloading $Name..."
            Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $temporary -TimeoutSec 60 -UserAgent 'EldenCraft-Windows-Setup'
            Assert-FileHash $temporary $Hash
            Move-Item -LiteralPath $temporary -Destination $path
            return $path
        } catch {
            if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force }
            if ($attempt -eq 3) { throw "Could not download $Name. Check your connection and rerun EldenCraft.cmd. $($_.Exception.Message)" }
            Start-Sleep -Seconds $attempt
        }
    }
}

function Expand-VerifiedArchive([string]$Archive, [string]$Destination, [string]$Hash, [string]$Executable) {
    Assert-FileHash $Archive $Hash
    $exe = Get-SafeChildPath $Destination $Executable
    $stamp = Join-Path $Destination '.archive-sha256'
    if ((Test-Path -LiteralPath $exe -PathType Leaf) -and (Test-Path -LiteralPath $stamp) -and
        (Get-Content -LiteralPath $stamp -Raw -Encoding UTF8).Trim() -ieq $Hash) {
        Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
        $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
        try {
            $entry = $zip.GetEntry($Executable.Replace('\', '/'))
            if (-not $entry) { throw 'Cached dependency has no matching executable in its archive.' }
            $inputStream = $entry.Open()
            $sha = [Security.Cryptography.SHA256]::Create()
            try { $expected = [BitConverter]::ToString($sha.ComputeHash($inputStream)).Replace('-', '') }
            finally { $sha.Dispose(); $inputStream.Dispose() }
            Assert-FileHash $exe $expected
        } finally { $zip.Dispose() }
        return $exe
    }
    if (Test-Path -LiteralPath $Destination) {
        throw "Incomplete dependency directory: $Destination. Rename it and rerun setup."
    }
    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
    $staging = "$Destination.unpack-$([guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Path $staging | Out-Null
    $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
    try {
        $total = [long]0
        $seen = @{}
        foreach ($entry in $zip.Entries) {
            $relative = $entry.FullName.TrimEnd('/', '\')
            $target = Get-SafeChildPath $staging $relative
            if ($seen.ContainsKey($relative)) { throw "Duplicate archive member: $relative" }
            $seen[$relative] = $true
            if ((($entry.ExternalAttributes -shr 16) -band 0xf000) -eq 0xa000) { throw 'Archive contains a symbolic link.' }
            $total += $entry.Length
            if ($total -gt 2GB) { throw 'Dependency archive exceeds the extraction limit.' }
            if ($entry.FullName.EndsWith('/') -or $entry.FullName.EndsWith('\')) {
                New-Item -ItemType Directory -Force -Path $target | Out-Null
                continue
            }
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
            $inputStream = $entry.Open()
            $outputStream = [IO.File]::Open($target, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
            try { $inputStream.CopyTo($outputStream) }
            finally { $outputStream.Dispose(); $inputStream.Dispose() }
        }
        if (-not (Test-Path -LiteralPath (Get-SafeChildPath $staging $Executable) -PathType Leaf)) {
            throw "Dependency archive is missing $Executable."
        }
        Write-Utf8File (Join-Path $staging '.archive-sha256') $Hash
    } finally { $zip.Dispose() }
    # Both resolved directories are direct siblings beneath the caller's tool directory.
    $parent = [IO.Path]::GetFullPath((Split-Path -Parent $Destination))
    foreach ($directory in @($staging, $Destination)) {
        if (-not [IO.Path]::GetFullPath($directory).StartsWith($parent + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Dependency directory escaped its destination.'
        }
    }
    [IO.Directory]::Move($staging, $Destination)
    return $exe
}

function Expand-ReShadeRuntime([string]$Installer, [string]$Directory, [string]$InstallerHash, [string]$RuntimeHash) {
    Assert-FileHash $Installer $InstallerHash
    $dll = Get-SafeChildPath $Directory 'ReShade64.dll'
    if (Test-Path -LiteralPath $dll) { Assert-FileHash $dll $RuntimeHash; return $dll }
    New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    # The official installer embeds a ZIP inside its PE resource/overlay. Slice out
    # the archive using its central-directory offsets, excluding the PE stub and
    # signature. Managed extraction also supports non-ASCII Windows usernames.
    if ((Get-Item -LiteralPath $Installer).Length -gt 64MB) { throw 'Unexpected ReShade installer size.' }
    $bytes = [IO.File]::ReadAllBytes($Installer)
    $record = -1
    for ($index = $bytes.Length - 22; $index -ge [Math]::Max(0, $bytes.Length - 65557); $index--) {
        if ($bytes[$index] -eq 0x50 -and $bytes[$index + 1] -eq 0x4b -and
            $bytes[$index + 2] -eq 0x05 -and $bytes[$index + 3] -eq 0x06) { $record = $index; break }
    }
    if ($record -lt 0 -or [BitConverter]::ToUInt16($bytes, $record + 4) -ne 0 -or
        [BitConverter]::ToUInt16($bytes, $record + 6) -ne 0) { throw 'ReShade installer has no supported embedded ZIP.' }
    $size = [BitConverter]::ToUInt32($bytes, $record + 12)
    $directoryOffset = [BitConverter]::ToUInt32($bytes, $record + 16)
    $offset = [long]$record - $size - $directoryOffset
    $length = [long]$record + 22 + [BitConverter]::ToUInt16($bytes, $record + 20) - $offset
    if ($offset -lt 0 -or $length -lt 22 -or $offset + $length -gt $bytes.Length) { throw 'Invalid embedded ZIP offsets.' }
    $archiveBytes = [byte[]]::new([int]$length)
    [Array]::Copy($bytes, $offset, $archiveBytes, 0, $length)
    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
    $stream = [IO.MemoryStream]::new($archiveBytes, $false)
    $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Read)
    try {
        $entry = $zip.GetEntry('ReShade64.dll')
        if (-not $entry) { throw 'Embedded ZIP is missing ReShade64.dll.' }
        $temporary = "$dll.part"
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $temporary, $true)
        Assert-FileHash $temporary $RuntimeHash
        Move-Item -LiteralPath $temporary -Destination $dll
    } finally { $zip.Dispose(); $stream.Dispose() }
    return $dll
}

function Find-EldenRing([string]$Explicit, [string]$Saved, [string]$SourceRoot) {
    $candidates = [Collections.Generic.List[string]]::new()
    if ($Explicit) {
        $candidate = $Explicit
        if (Test-Path -LiteralPath $candidate -PathType Container) { $candidate = Join-Path $candidate 'eldenring.exe' }
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw "Elden Ring was not found at $Explicit." }
        return [IO.Path]::GetFullPath($candidate)
    }
    if ($Saved) { $candidates.Add($Saved) }
    $legacy = Join-Path $SourceRoot 'config/local.json'
    if (Test-Path -LiteralPath $legacy) {
        $configuration = Get-Content -LiteralPath $legacy -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($configuration.elden_ring_game_dir) { $candidates.Add([string]$configuration.elden_ring_game_dir) }
    }
    $steam = Get-ItemProperty -LiteralPath 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue
    if ($steam -and $steam.SteamPath) {
        $libraries = [Collections.Generic.List[string]]::new()
        $libraries.Add([string]$steam.SteamPath)
        $vdf = Join-Path $steam.SteamPath 'steamapps/libraryfolders.vdf'
        if (Test-Path -LiteralPath $vdf) {
            foreach ($match in [regex]::Matches((Get-Content -LiteralPath $vdf -Raw -Encoding UTF8), '"path"\s+"([^"]+)"')) {
                $libraries.Add($match.Groups[1].Value.Replace('\\', '\'))
            }
        }
        foreach ($library in $libraries) {
            # Steam keeps libraries on drives that may be gone (an unplugged disk), and
            # Windows PowerShell's Join-Path throws for a missing drive. Build the paths
            # directly and skip any library entry that cannot be read.
            try {
                $manifest = [IO.Path]::Combine($library, 'steamapps', 'appmanifest_1245620.acf')
                if (-not (Test-Path -LiteralPath $manifest)) { continue }
                $match = [regex]::Match((Get-Content -LiteralPath $manifest -Raw -Encoding UTF8), '"installdir"\s+"([^"]+)"')
                if ($match.Success) { $candidates.Add([IO.Path]::Combine($library, 'steamapps', 'common', $match.Groups[1].Value, 'Game', 'eldenring.exe')) }
            } catch { continue }
        }
    }
    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Container) { $candidate = Join-Path $candidate 'eldenring.exe' }
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return [IO.Path]::GetFullPath($candidate) }
    }
    return $null
}

function Assert-GamesClosed([string]$MinecraftDirectory, [string]$PrismExecutable) {
    $protected = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
        $_.ProcessName -eq 'eldenring' -or $_.ProcessName -eq 'start_protected_game' -or $_.ProcessName -like 'EasyAntiCheat*'
    })
    if ($protected.Count) { throw 'Close Elden Ring and any game using Easy Anti-Cheat before running EldenCraft.' }
    foreach ($process in @(Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe' OR Name = 'prismlauncher.exe'")) {
        if (($process.CommandLine -and $process.CommandLine.Replace('/', '\').IndexOf($MinecraftDirectory, [StringComparison]::OrdinalIgnoreCase) -ge 0) -or
            ($PrismExecutable -and $process.ExecutablePath -eq $PrismExecutable)) {
            throw 'Close the EldenCraft Minecraft instance and its Prism Launcher before running setup or launching again.'
        }
    }
}

function New-VerifiedBackup([string]$Source, [string]$BackupDirectory, [string]$Name) {
    if (-not (Test-Path -LiteralPath $Source -PathType Container)) { return $null }
    $root = [IO.Path]::GetFullPath($Source).TrimEnd('\', '/')
    $backupRoot = [IO.Path]::GetFullPath($BackupDirectory)
    if ($backupRoot.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase) -or $backupRoot -ieq $root) {
        throw 'The backup directory must be outside the saved data.'
    }
    $entries = @(Get-Item -LiteralPath $root -Force) + @(Get-ChildItem -LiteralPath $root -Recurse -Force -ErrorAction Stop)
    if (@($entries | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count) {
        throw "Save directory contains a link or junction: $root"
    }
    $files = @($entries | Where-Object { -not $_.PSIsContainer })
    if (-not $files.Count) { return $null }
    if ($files.Count -gt 100000) { throw 'Save backup contains too many files.' }
    if (($files | Measure-Object -Property Length -Sum).Sum -gt 20GB) { throw 'Save backup exceeds 20 GiB.' }
    New-Item -ItemType Directory -Force -Path $BackupDirectory | Out-Null
    $path = Join-Path $BackupDirectory ($Name + '-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.zip')
    $temporary = "$path.part"
    $records = @{}
    Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::Open($temporary, [IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in $files) {
            $relative = $file.FullName.Substring($root.Length + 1).Replace('\', '/')
            if ($relative -eq '_eldencraft_manifest.json') { throw 'Save directory contains a reserved backup filename.' }
            $safe = Get-SafeChildPath $root $relative
            $before = (Get-FileHash -LiteralPath $safe -Algorithm SHA256).Hash.ToLowerInvariant()
            $inputStream = [IO.File]::Open($safe, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
            $entry = $zip.CreateEntry($relative, [IO.Compression.CompressionLevel]::Optimal)
            $outputStream = $entry.Open()
            try { $inputStream.CopyTo($outputStream); $size = $inputStream.Length }
            finally { $outputStream.Dispose(); $inputStream.Dispose() }
            if ((Get-FileHash -LiteralPath $safe -Algorithm SHA256).Hash -ine $before) { throw "Save changed during backup: $relative" }
            $records[$relative] = @{ size = $size; sha256 = $before }
        }
        $entry = $zip.CreateEntry('_eldencraft_manifest.json')
        $writer = [IO.StreamWriter]::new($entry.Open(), [Text.UTF8Encoding]::new($false))
        try { $writer.Write((@{ schema = 1; files = $records } | ConvertTo-Json -Depth 5)) }
        finally { $writer.Dispose() }
    } finally { $zip.Dispose() }
    $zip = [IO.Compression.ZipFile]::OpenRead($temporary)
    try {
        foreach ($relative in $records.Keys) {
            $entry = $zip.GetEntry($relative)
            $inputStream = $entry.Open()
            $sha = [Security.Cryptography.SHA256]::Create()
            try { $actual = [BitConverter]::ToString($sha.ComputeHash($inputStream)).Replace('-', '') }
            finally { $sha.Dispose(); $inputStream.Dispose() }
            if ($entry.Length -ne $records[$relative].size -or $actual -ine $records[$relative].sha256) {
                throw "Backup verification failed: $relative"
            }
        }
    } finally { $zip.Dispose() }
    Move-Item -LiteralPath $temporary -Destination $path
    Write-Host "Verified backup: $path"
    return $path
}

function Set-MinecraftProfile($Release, [string]$Directory, [string]$Java, [string]$FabricApi, [string]$BackupDirectory) {
    $marker = Get-SafeChildPath $Directory '.eldencraft-owned'
    New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    if (-not (Test-Path -LiteralPath $marker) -and @(Get-ChildItem -LiteralPath $Directory -Force).Count) {
        throw "Refusing to replace an unrelated Minecraft instance: $Directory"
    }
    Write-Utf8File $marker '1'
    $mc = $Release.Dependencies.minecraft
    $pack = @{ formatVersion = 1; components = @(
        @{ uid = 'org.lwjgl3'; version = $mc.lwjgl; dependencyOnly = $true },
        @{ uid = 'net.minecraft'; version = $mc.version; important = $true },
        @{ uid = 'net.fabricmc.intermediary'; version = $mc.version; dependencyOnly = $true },
        @{ uid = 'net.fabricmc.fabric-loader'; version = $mc.fabric_loader }
    ) }
    Write-Utf8File (Join-Path $Directory 'mmc-pack.json') ($pack | ConvertTo-Json -Depth 5)
    if ($Java -match '[\r\n]') { throw 'Java path cannot contain newlines.' }
    $settings = @"
[General]
InstanceType=OneSix
name=EldenCraft
iconKey=grass
OverrideJavaLocation=true
AutomaticJava=false
JavaPath=$($Java.Replace('\', '/'))
OverrideJavaArgs=true
JvmArgs=--enable-native-access=ALL-UNNAMED -Djava.net.preferIPv4Stack=true
OverrideMemory=true
MinMemAlloc=1024
MaxMemAlloc=4096
OverrideWindow=true
MinecraftWinWidth=1280
MinecraftWinHeight=720
OverrideMiscellaneous=true
CloseAfterLaunch=false
QuitAfterGameStop=true
"@
    Write-Utf8File (Join-Path $Directory 'instance.cfg') $settings
    $mods = Get-SafeChildPath $Directory '.minecraft/mods'
    New-Item -ItemType Directory -Force -Path $mods | Out-Null
    $expectedMods = @{}
    $expectedMods["eldencraft-bridge-$($Release.Version).jar"] = Join-Path $Release.Root 'payload/eldencraft-bridge.jar'
    $expectedMods["fabric-api-$($mc.fabric_api.version).jar"] = $FabricApi
    foreach ($old in @(Get-ChildItem -LiteralPath $mods -File -Filter '*.jar')) {
        if ($old.Name -like 'eldencraft-bridge*.jar' -or $old.Name -like 'fabric-api-*.jar') {
            if ($expectedMods.ContainsKey($old.Name) -and
                (Get-FileHash -LiteralPath $old.FullName -Algorithm SHA256).Hash -eq
                (Get-FileHash -LiteralPath $expectedMods[$old.Name] -Algorithm SHA256).Hash) { continue }
            $retired = Join-Path $BackupDirectory ('mods-' + [guid]::NewGuid().ToString('N'))
            New-Item -ItemType Directory -Path $retired | Out-Null
            Move-Item -LiteralPath $old.FullName -Destination (Join-Path $retired $old.Name)
        }
    }
    foreach ($name in $expectedMods.Keys) {
        $target = Get-SafeChildPath $mods $name
        if (-not (Test-Path -LiteralPath $target)) { Copy-Item -LiteralPath $expectedMods[$name] -Destination $target }
    }
}

function Set-NativeRuntime($Release, [string]$Directory, [string]$ReShade) {
    Get-SafeChildPath $Directory 'ReShade.ini' | Out-Null
    New-Item -ItemType Directory -Force -Path $Directory, (Join-Path $Directory 'data'), (Join-Path $Directory 'logs'),
        (Join-Path $Directory 'addons'), (Join-Path $Directory 'shaders') | Out-Null
    foreach ($relative in @('eldencraft_native.dll', 'eldencraft_core.dll', 'addons/EldenCraftCompositor.addon64', 'shaders/EldenCraftPassthrough.fx')) {
        Copy-Item -LiteralPath (Get-SafeChildPath $Release.Root "payload/$relative") -Destination (Get-SafeChildPath $Directory $relative) -Force
    }
    Copy-Item -LiteralPath $ReShade -Destination (Join-Path $Directory 'ReShade64.asi') -Force
    if (-not (Test-Path -LiteralPath (Join-Path $Directory 'EldenCraftPreset.ini'))) {
        Copy-Item -LiteralPath (Join-Path $Release.Root 'payload/EldenCraftPreset.ini') -Destination (Join-Path $Directory 'EldenCraftPreset.ini')
    }
    if ($Directory -match '[\r\n]') { throw 'Runtime path cannot contain newlines.' }
    Write-Utf8File (Join-Path $Directory 'ReShade.ini') @"
[ADDON]
AddonPath=$Directory\addons
[GENERAL]
EffectSearchPaths=$Directory\shaders
PresetPath=$Directory\EldenCraftPreset.ini
[INPUT]
InputProcessing=2
[OVERLAY]
TutorialProgress=4
"@
    Write-Utf8File (Join-Path $Directory 'eldencraft.me3') @'
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
}

function Set-CampaignProfile($Release, [string]$DataDirectory, [string]$Instance, [string]$Runtime) {
    $campaignPath = Join-Path $DataDirectory 'campaign.json'
    if ($env:ELDENCRAFT_CAMPAIGN_CONFIG) {
        $campaignPath = [IO.Path]::GetFullPath($env:ELDENCRAFT_CAMPAIGN_CONFIG)
        if (-not (Test-Path -LiteralPath $campaignPath -PathType Leaf)) { throw "Campaign configuration not found: $campaignPath" }
    } elseif (-not (Test-Path -LiteralPath $campaignPath)) {
        Copy-Item -LiteralPath (Join-Path $Release.Root 'config/campaign.json') -Destination $campaignPath
    }
    $campaignDirectory = Join-Path $Runtime 'data/campaign'
    if ($env:ELDENCRAFT_CAMPAIGN_DIR) { $campaignDirectory = [IO.Path]::GetFullPath($env:ELDENCRAFT_CAMPAIGN_DIR) }
    $configDirectory = Get-SafeChildPath $Instance '.minecraft/config'
    New-Item -ItemType Directory -Force -Path $configDirectory | Out-Null
    Write-Utf8File (Join-Path $configDirectory 'eldencraft-campaign-link.json') (@{
        config = $campaignPath; directory = $campaignDirectory
    } | ConvertTo-Json)
    return [pscustomobject]@{ Config = $campaignPath; Directory = $campaignDirectory }
}

function Assert-NoReShadeConflict([string]$Executable, [string]$Runtime) {
    $gameDirectory = Split-Path -Parent $Executable
    foreach ($name in @('dxgi.dll', 'd3d12.dll', 'd3d11.dll', 'd3d9.dll', 'opengl32.dll')) {
        $path = Join-Path $gameDirectory $name
        if ((Test-Path -LiteralPath $path -PathType Leaf) -and
            (Get-Item -LiteralPath $path).VersionInfo.ProductName -eq 'ReShade') {
            throw "A separate ReShade installation conflicts with EldenCraft: $path. Uninstall that ReShade installation or move its proxy DLL out of the Game folder, then rerun. EldenCraft supplies ReShade itself; no Game\dxgi.dll is required."
        }
    }
    $ini = Join-Path $gameDirectory 'ReShade.ini'
    if (Test-Path -LiteralPath $ini -PathType Leaf) {
        $installSection = $false
        foreach ($line in (Get-Content -LiteralPath $ini -Encoding UTF8)) {
            if ($line -match '^\s*\[([^\]]+)\]') { $installSection = $Matches[1] -ieq 'INSTALL' }
            elseif ($installSection -and $line -match '^\s*BasePath\s*=\s*(\S.*?)\s*$') {
                $base = $Matches[1]
                if ($Runtime) {
                    if (-not [IO.Path]::IsPathRooted($base)) { $base = Join-Path $gameDirectory $base }
                    if ([IO.Path]::GetFullPath($base).TrimEnd('\', '/') -ieq [IO.Path]::GetFullPath($Runtime).TrimEnd('\', '/')) { continue }
                }
                throw "An old ReShade installation overrides EldenCraft's runtime path: $ini [INSTALL] BasePath. Move that old ReShade.ini out of the Game folder, then rerun. Your EldenCraft shader settings are stored in the runtime folder."
            }
        }
    }
}

# Windows PowerShell 5.1 can lose a redirected child's exit code if its process
# handle was never opened before it exited. Keep that handle alive immediately.
function Start-LoggedProcess([string]$Executable, [string[]]$Arguments, [string]$Directory, [string]$Stdout, [string]$Stderr) {
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments `
        -WorkingDirectory $Directory -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $Stdout -RedirectStandardError $Stderr
    $processHandle = $process.Handle
    return $process
}

function Start-EldenRingLoader([string]$Executable, [string]$Runtime, [string]$Profile, [string]$Game, [string]$Logs, [string]$Stamp) {
    return Start-LoggedProcess -Executable $Executable `
        -Arguments @('--crash-reporting=false', 'launch', '--skip-steam-init', '--profile', ('"' + $Profile + '"'), '--exe', ('"' + $Game + '"')) `
        -Directory $Runtime -Stdout (Join-Path $Logs "me3-$Stamp.stdout.log") -Stderr (Join-Path $Logs "me3-$Stamp.stderr.log")
}

function Assert-LoaderStarted($Process, [string]$Logs, [string]$Stamp) {
    $Process.Refresh()
    if (-not $Process.HasExited) { return }
    $code = $Process.ExitCode
    if ($null -eq $code) { throw "Elden Ring loader exited without an available exit code. See $(Join-Path $Logs "me3-$Stamp.stderr.log")." }
    if ($code -ne 0) {
        $stderr = Join-Path $Logs "me3-$Stamp.stderr.log"
        $detail = if (Test-Path -LiteralPath $stderr) { (Get-Content -LiteralPath $stderr -Tail 8) -join "`n" } else { 'No loader error log was written.' }
        throw "Elden Ring loader failed (exit $code). See $stderr.`n$detail"
    }
}

function Invoke-EldenCraftCore {
    param([string]$SourceRoot = (Split-Path -Parent $PSScriptRoot))
    $releaseRoot = $sourceRoot
    if (-not (Test-Path -LiteralPath (Join-Path $releaseRoot 'release-manifest.json'))) {
        $pointer = Join-Path $sourceRoot '.local/releases/current.txt'
        if (-not (Test-Path -LiteralPath $pointer)) {
            throw 'This is a source checkout. Download and extract the Windows release ZIP, or build it with scripts/build-release.ps1 first.'
        }
        $releaseRoot = Get-SafeChildPath (Split-Path -Parent $pointer) (Get-Content -LiteralPath $pointer -Raw -Encoding UTF8).Trim()
    }
    $release = Read-Release $releaseRoot
    $deps = $release.Dependencies
    if (-not [Environment]::Is64BitOperatingSystem -or $env:PROCESSOR_ARCHITECTURE -ne 'AMD64' -or
        [Environment]::OSVersion.Version.Build -lt $deps.windows_min_build) {
        throw 'EldenCraft requires Windows 10/11 on an x64 PC (Windows build 19041 or newer).'
    }
    if (-not $DataDirectory) { $DataDirectory = Join-Path $env:LOCALAPPDATA 'EldenCraft' }
    $DataDirectory = [IO.Path]::GetFullPath($DataDirectory)
    Get-SafeChildPath $DataDirectory 'setup.lock' | Out-Null
    if ($DataDirectory -match '[\r\n]') { throw 'Data directory cannot contain newlines.' }
    $settingsPath = Join-Path $DataDirectory 'settings.json'
    $savedGame = $null
    if (Test-Path -LiteralPath $settingsPath) { $savedGame = (Get-Content -LiteralPath $settingsPath -Raw -Encoding UTF8 | ConvertFrom-Json).game_executable }
    $exe = Find-EldenRing $GamePath $savedGame $sourceRoot
    if (-not $exe -and -not $NonInteractive -and $Mode -ne 'check') {
        Add-Type -AssemblyName System.Windows.Forms
        $dialog = [Windows.Forms.OpenFileDialog]::new()
        try {
            $dialog.Title = 'Select the supported Elden Ring executable'
            $dialog.Filter = 'Elden Ring|eldenring.exe'
            if ($dialog.ShowDialog() -eq [Windows.Forms.DialogResult]::OK) { $exe = $dialog.FileName }
        } finally { $dialog.Dispose() }
    }
    if (-not $exe) { throw 'Install Elden Ring through Steam, or specify -GamePath "D:\Games\ELDEN RING\Game\eldenring.exe".' }
    try { Assert-FileHash $exe $deps.elden_ring.sha256 }
    catch {
        $actualVersion = (Get-Item -LiteralPath $exe).VersionInfo.ProductVersion
        $advice = if ($actualVersion -eq '2.7.0.0') { 'Update Elden Ring from App Ver. 1.17 to 1.17.1 through Steam, then verify its installed files.' } else { 'Verify installed files in Steam and use an EldenCraft release matching your game build.' }
        throw "Unsupported Elden Ring executable at $exe (product version '$actualVersion'). This release requires $($deps.elden_ring.product_version), SHA256 $($deps.elden_ring.sha256). $advice If you have multiple installations, select the Steam copy with -GamePath."
    }
    Assert-NoReShadeConflict $exe (Join-Path $DataDirectory 'runtime')
    if ($Mode -eq 'check') {
        Write-Host "Release $($release.Version): all packaged files verified."
        Write-Host "Elden Ring $($deps.elden_ring.product_version): executable verified."
        Write-Host "Runtime data: $DataDirectory"
        return
    }
    New-Item -ItemType Directory -Force -Path $DataDirectory | Out-Null
    $lock = $null
    try {
        try { $lock = [IO.File]::Open((Join-Path $DataDirectory 'setup.lock'), [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None) }
        catch { throw 'Another EldenCraft setup or launch is already running.' }
        $logs = Join-Path $DataDirectory 'logs'
        New-Item -ItemType Directory -Force -Path $logs | Out-Null
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $cache = Get-SafeChildPath $DataDirectory 'downloads'
        $tools = Get-SafeChildPath $DataDirectory 'tools'
        $prismData = Get-SafeChildPath $DataDirectory 'minecraft'
        $instance = Get-SafeChildPath $DataDirectory 'minecraft/instances/EldenCraft'
        $runtime = Get-SafeChildPath $DataDirectory 'runtime'
        $backups = Get-SafeChildPath $DataDirectory 'backups'
        New-Item -ItemType Directory -Force -Path $tools | Out-Null
        Assert-GamesClosed $prismData (Join-Path $tools "prism-$($deps.prism.version)/prismlauncher.exe")
        $installed = @{}
        foreach ($name in @('prism', 'java', 'me3')) {
            $dependency = $deps.$name
            $archive = Get-VerifiedDownload $cache "$name-$($dependency.version).zip" $dependency.url $dependency.sha256
            $destination = Get-SafeChildPath $tools "$name-$($dependency.version)"
            $installed[$name] = Expand-VerifiedArchive $archive $destination $dependency.sha256 $dependency.executable
        }
        $setup = Get-VerifiedDownload $cache "ReShade-$($deps.reshade.version)-Addon.exe" $deps.reshade.url $deps.reshade.sha256
        $reshadeDirectory = Get-SafeChildPath $tools "reshade-$($deps.reshade.version)"
        $reshade = Expand-ReShadeRuntime $setup $reshadeDirectory $deps.reshade.sha256 $deps.reshade.runtime_sha256
        $api = Get-VerifiedDownload $cache "fabric-api-$($deps.minecraft.fabric_api.version).jar" $deps.minecraft.fabric_api.url $deps.minecraft.fabric_api.sha256
        Assert-GamesClosed $prismData $installed.prism
        $saveDirectory = Join-Path $env:APPDATA 'EldenRing'
        New-VerifiedBackup $saveDirectory $backups 'elden-ring' | Out-Null
        New-VerifiedBackup (Join-Path $instance '.minecraft/saves') $backups 'minecraft' | Out-Null
        Set-MinecraftProfile $release $instance $installed.java $api $backups
        Set-NativeRuntime $release $runtime $reshade
        $campaignProfile = Set-CampaignProfile $release $DataDirectory $instance $runtime
        Write-Host "Editable campaign settings: $($campaignProfile.Config) (restart both games after changes)."
        New-VerifiedBackup $campaignProfile.Directory $backups 'campaign' | Out-Null
        $prismSettings = Join-Path $prismData 'prismlauncher.cfg'
        if (-not (Test-Path -LiteralPath $prismSettings)) {
            Write-Utf8File $prismSettings @"
[General]
ConfigVersion=1.3
Language=en
JavaPath=$($installed.java.Replace('\', '/'))
AutomaticJavaDownload=false
AutomaticJavaSwitch=false
UserAskedAboutAutomaticJavaDownload=true
ApplicationTheme=system
IconTheme=pe_colored
"@
        }
        Write-Utf8File $settingsPath (@{ schema_version = 1; game_executable = $exe } | ConvertTo-Json)
        $signedIn = Join-Path $prismData '.first-run-complete'
        if (-not (Test-Path -LiteralPath $signedIn)) {
            if ($NonInteractive -and $Mode -eq 'setup') {
                Write-Host 'Portable setup complete. Run EldenCraft.cmd interactively to sign in and play.'
                return
            }
            Write-Host 'First-time setup: sign in to your Minecraft Microsoft account in Prism Launcher.'
            Write-Host 'Close Prism Launcher when finished. EldenCraft will continue here.'
            if ($NonInteractive) { throw 'First-time Minecraft sign-in needs an interactive run of EldenCraft.cmd.' }
            Start-Process -FilePath $installed.prism -ArgumentList @('--dir', ('"' + $prismData + '"')) -Wait
            Write-Utf8File $signedIn '1'
        }
        if ($Mode -eq 'setup') { Write-Host 'Setup complete. Run EldenCraft.cmd to play.'; return }
        Assert-GamesClosed $prismData $installed.prism
        $steam = Get-ItemProperty -LiteralPath 'HKCU:\Software\Valve\Steam' -ErrorAction SilentlyContinue
        if (-not (Get-Process -Name steam -ErrorAction SilentlyContinue) -and $steam -and $steam.SteamExe) {
            Start-Process -FilePath $steam.SteamExe -ArgumentList '-silent' -WindowStyle Hidden | Out-Null
        }
        $env:ELDENCRAFT_DATA_DIR = Join-Path $runtime 'data'
        $env:ELDENCRAFT_CAMPAIGN_CONFIG = $campaignProfile.Config
        $env:ELDENCRAFT_CAMPAIGN_DIR = $campaignProfile.Directory
        $env:ELDENCRAFT_EXPECTED_SHA256 = $deps.elden_ring.sha256
        $env:RESHADE_BASE_PATH_OVERRIDE = $runtime
        $env:ELDENCRAFT_MELEE_LAB = '1'
        $env:ELDENCRAFT_NATIVE_COLLIDERS = '1'
        $env:ELDENCRAFT_NATIVE_MOBS = '1'
        $env:ELDENCRAFT_GPU_TRANSPORT = if ($CpuFrames) { '0' } else { '1' }
        # The dedicated launcher's process receives the same bridge environment as me3.
        $guest = Start-Process -FilePath $installed.prism -ArgumentList @('--dir', ('"' + $prismData + '"'), '--launch', 'EldenCraft', '--show-window') -PassThru
        Write-Host 'Starting Minecraft. It will create or open the dedicated EldenCraft world automatically.'
        $deadline = [DateTime]::UtcNow.AddMinutes(10)
        $ready = $false
        while ([DateTime]::UtcNow -lt $deadline) {
            foreach ($process in @(Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe' OR Name = 'java.exe'")) {
                if ($process.CommandLine -and $process.CommandLine.Replace('/', '\').IndexOf($instance, [StringComparison]::OrdinalIgnoreCase) -ge 0) { $ready = $true; break }
            }
            if ($ready) { break }
            $guest.Refresh()
            if ($guest.HasExited) { throw 'Minecraft did not start. Open Prism Launcher, finish sign-in and review the EldenCraft instance log, then rerun.' }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { throw 'Minecraft startup timed out after ten minutes. Finish its downloads in Prism Launcher, close it and rerun.' }
        $profile = Join-Path $runtime 'eldencraft.me3'
        $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
        $hostProcess = Start-EldenRingLoader $installed.me3 $runtime $profile $exe $logs $stamp
        Start-Sleep -Seconds 2
        Assert-LoaderStarted $hostProcess $logs $stamp
        Write-Host 'Elden Ring is starting offline with the separate EldenCraft.sl2 save.'
        Write-Host 'Load or create an Elden Ring character to connect to the Minecraft world.'
        Write-Host "Logs and verified backups: $DataDirectory"
    } finally {
        if ($lock) { $lock.Dispose() }
    }
}

function Invoke-EldenCraft {
    param([string]$SourceRoot = (Split-Path -Parent $PSScriptRoot))
    if (-not $DataDirectory) { $DataDirectory = Join-Path $env:LOCALAPPDATA 'EldenCraft' }
    $DataDirectory = [IO.Path]::GetFullPath($DataDirectory)
    Get-SafeChildPath $DataDirectory 'logs' | Out-Null
    if ($DataDirectory -match '[\r\n]') { throw 'Data directory cannot contain newlines.' }
    $logs = Join-Path $DataDirectory 'logs'
    New-Item -ItemType Directory -Force -Path $logs | Out-Null
    $log = Join-Path $logs ('launcher-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + $PID + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.log')
    Start-Transcript -Path $log | Out-Null
    try {
        Write-Host "Launcher log: $log"
        Write-Host "Release folder: $SourceRoot"
        Write-Host "Runtime data: $DataDirectory"
        Write-Host "Minecraft log: $(Join-Path $DataDirectory 'minecraft/instances/EldenCraft/.minecraft/logs/latest.log')"
        Write-Host "Native logs: $(Join-Path $DataDirectory 'runtime/data')"
        Write-Host "ReShade log: $(Join-Path $DataDirectory 'runtime/ReShade.log')"
        Invoke-EldenCraftCore -SourceRoot $SourceRoot
    } catch {
        Write-Host "EldenCraft: $($_.Exception.Message)" -ForegroundColor Red
        throw
    } finally { Stop-Transcript | Out-Null }
}

if ($MyInvocation.InvocationName -ne '.') {
    try { Invoke-EldenCraft }
    catch { Write-Host "EldenCraft: $($_.Exception.Message)" -ForegroundColor Red; exit 1 }
}
