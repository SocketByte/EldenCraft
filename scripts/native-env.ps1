function Set-EldenCraftNativeEnvironment {
    param([string]$RepoRoot = (Split-Path -Parent $PSScriptRoot))
    $env:CARGO_HOME = Join-Path $RepoRoot '.tools\cargo'
    $env:RUSTUP_HOME = Join-Path $RepoRoot '.tools\rustup'
    $env:CARGO_TARGET_DIR = Join-Path $RepoRoot '.local\native-build'
    # Reproducible DLLs: /Brepro derives the PE timestamp and PDB identity from
    # the content, and dependency source paths in panic locations must not
    # depend on where the repository was cloned. rustc applies the last matching
    # remap, so the nested Cargo home follows the repository root. These flags
    # replace .cargo/config.toml's, so the static C runtime is repeated here.
    $env:CARGO_ENCODED_RUSTFLAGS = @(
        '-Ctarget-feature=+crt-static',
        '-Clink-arg=/Brepro',
        "--remap-path-prefix=$RepoRoot=/eldencraft",
        "--remap-path-prefix=$($env:CARGO_HOME)=/cargo"
    ) -join [char]0x1f
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (-not (Test-Path -LiteralPath $vswhere)) { throw 'Run scripts/install-native-deps.ps1 first (Microsoft C++ Build Tools missing).' }
    $vsPath = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $vsPath) { throw 'No Visual Studio installation has the x64 C++ toolset.' }
    Import-Module (Join-Path $vsPath 'Common7\Tools\Microsoft.VisualStudio.DevShell.dll')
    Enter-VsDevShell -VsInstallPath $vsPath -SkipAutomaticLocation -DevCmdArguments '-arch=x64 -host_arch=x64' | Out-Null
    $cmakeBin = Join-Path $vsPath 'Common7\IDE\CommonExtensions\Microsoft\CMake\CMake\bin'
    $toolPath = "$(Join-Path $env:CARGO_HOME 'bin');$cmakeBin;$env:Path"
    # MSBuild's legacy task runner cannot handle duplicate PATH/Path spellings.
    [Environment]::SetEnvironmentVariable('PATH', $null, 'Process')
    [Environment]::SetEnvironmentVariable('Path', $null, 'Process')
    [Environment]::SetEnvironmentVariable('Path', $toolPath, 'Process')
}
