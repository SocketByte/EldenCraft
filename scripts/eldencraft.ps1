param(
    [Parameter(Position = 0)]
    [ValidateSet('setup', 'setup-native', 'setup-passthrough', 'doctor', 'test', 'prepare-profile', 'build-minecraft', 'build-native', 'test-native', 'reload-native', 'launch-native', 'check-source')]
    [string]$Command = 'doctor',
    [switch]$PrepareOnly,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ExtraArgs = @()
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$project = $repo
$python = Join-Path $repo '.venv\Scripts\python.exe'
$env:UV_CACHE_DIR = Join-Path $repo '.cache\uv'
$env:UV_PYTHON_INSTALL_DIR = Join-Path $repo '.tools\python'
$env:GRADLE_USER_HOME = Join-Path $repo '.cache\gradle'
Push-Location $repo
try {
    if ($Command -eq 'setup-native') {
        & (Join-Path $PSScriptRoot 'install-native-deps.ps1')
    } elseif ($Command -eq 'setup-passthrough') {
        & (Join-Path $PSScriptRoot 'setup-passthrough.ps1')
    } elseif ($Command -eq 'setup') {
        if (-not (Get-Command uv -ErrorAction SilentlyContinue)) { throw 'Install uv first: https://docs.astral.sh/uv/getting-started/installation/' }
        & uv sync --locked --group dev
        if ($LASTEXITCODE -ne 0) { throw 'Python dependency setup failed.' }
        & $python (Join-Path $project 'tools\workspace.py') init
    } else {
        if (-not (Test-Path -LiteralPath $python)) { throw 'Run .\scripts\eldencraft.ps1 setup first.' }
        switch ($Command) {
            'doctor' { & $python (Join-Path $project 'tools\workspace.py') doctor @ExtraArgs }
            'test' { & $python -m pytest -q tests passthrough/test_frames.py @ExtraArgs }
            'prepare-profile' { & $python (Join-Path $project 'tools\workspace.py') prepare-profile }
            'build-minecraft' {
                $javaTmp = Join-Path $repo '.cache\tmp'
                New-Item -ItemType Directory -Force -Path $javaTmp | Out-Null
                $previousJavaOptions = $env:JAVA_TOOL_OPTIONS
                try {
                    $env:JAVA_TOOL_OPTIONS = "$previousJavaOptions -Djava.net.preferIPv4Stack=true -Djdk.net.unixdomain.tmpdir=`"$javaTmp`""
                    Set-Location (Join-Path $project 'minecraft')
                    & .\gradlew.bat --no-daemon build @ExtraArgs
                } finally { $env:JAVA_TOOL_OPTIONS = $previousJavaOptions }
            }
            'build-native' {
                . (Join-Path $PSScriptRoot 'native-env.ps1')
                Set-EldenCraftNativeEnvironment -RepoRoot $repo
                # Workspace: eldencraft_native.dll (persistent loader) and eldencraft_core.dll (reloadable core).
                & cargo +1.99.0 build --manifest-path (Join-Path $project 'native\Cargo.toml') --workspace --release --locked @ExtraArgs
            }
            'test-native' {
                . (Join-Path $PSScriptRoot 'native-env.ps1')
                Set-EldenCraftNativeEnvironment -RepoRoot $repo
                & cargo +1.99.0 test --manifest-path (Join-Path $project 'native\Cargo.toml') --workspace --locked @ExtraArgs
            }
            'reload-native' {
                # Rebuild and hand a new core to a running game launched with ELDENCRAFT_HOT_RELOAD=1.
                # The loader copies it, retires the old core on the game thread and starts the new one.
                . (Join-Path $PSScriptRoot 'native-env.ps1')
                Set-EldenCraftNativeEnvironment -RepoRoot $repo
                & cargo +1.99.0 build --manifest-path (Join-Path $project 'native\Cargo.toml') --workspace --release --locked @ExtraArgs
                if ($LASTEXITCODE -ne 0) { throw 'Native build failed; the running core was left untouched.' }
                $runtimeCore = Join-Path $repo '.local\eldencraft-runtime\eldencraft_core.dll'
                Copy-Item -LiteralPath (Join-Path $repo '.local\native-build\release\eldencraft_core.dll') -Destination $runtimeCore -Force
                Write-Output "Copied the new core to $runtimeCore. A game launched with ELDENCRAFT_HOT_RELOAD=1 reloads it within about a second (see eldencraft-data\eldencraft-loader.log)."
            }
            'launch-native' {
                if ($ExtraArgs.Count) { throw 'Unknown launcher arguments. Use -PrepareOnly for a launch-free check.' }
                & (Join-Path $PSScriptRoot 'launch-eldencraft.ps1') -PrepareOnly:$PrepareOnly
            }
            'check-source' { & $python (Join-Path $project 'tools\workspace.py') check-source }
        }
    }
    if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) { throw "EldenCraft '$Command' failed (exit $LASTEXITCODE)." }
} finally { Pop-Location }
