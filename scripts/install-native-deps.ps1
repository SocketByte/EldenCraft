# Explicitly invoked dependency installer. Requires network and may request OS elevation.
param()
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$downloads = Join-Path $repo '.local\installers'
New-Item -ItemType Directory -Force -Path $downloads | Out-Null
$vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
$vsPath = if (Test-Path -LiteralPath $vswhere) {
    & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 Microsoft.VisualStudio.Component.VC.CMake.Project Microsoft.VisualStudio.Component.Windows11SDK.26100 -property installationPath
}
if (-not $vsPath) {
    $installer = Join-Path $downloads 'vs_BuildTools.exe'
    Invoke-WebRequest -Uri 'https://aka.ms/vs/17/release/vs_BuildTools.exe' -OutFile $installer
    $signature = Get-AuthenticodeSignature -LiteralPath $installer
    if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation') {
        throw 'Microsoft installer signature verification failed.'
    }
    $arguments = @('--quiet','--wait','--norestart','--nocache', '--installPath', '"C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools"',
        '--add','Microsoft.VisualStudio.Workload.VCTools', '--add','Microsoft.VisualStudio.Component.VC.CMake.Project',
        '--add','Microsoft.VisualStudio.Component.Windows11SDK.26100','--includeRecommended')
    $process = Start-Process -FilePath $installer -ArgumentList $arguments -WindowStyle Hidden -PassThru -Wait
    if ($process.ExitCode -notin @(0,3010)) { throw "Microsoft toolchain installation failed: $($process.ExitCode)" }
    if ($process.ExitCode -eq 3010) { Write-Warning 'The Microsoft installer requests a restart. No restart was performed.' }
}
$env:CARGO_HOME = Join-Path $repo '.tools\cargo'
$env:RUSTUP_HOME = Join-Path $repo '.tools\rustup'
$rustup = Join-Path $env:CARGO_HOME 'bin\rustup.exe'
if (-not (Test-Path -LiteralPath $rustup)) {
    $installer = Join-Path $downloads 'rustup-init.exe'
    $hashFile = Join-Path $downloads 'rustup-init.exe.sha256'
    $url = 'https://static.rust-lang.org/rustup/dist/x86_64-pc-windows-msvc/rustup-init.exe'
    Invoke-WebRequest -Uri $url -OutFile $installer
    Invoke-WebRequest -Uri "$url.sha256" -OutFile $hashFile
    $expected = (Get-Content -LiteralPath $hashFile -Raw).Trim().Split(' ')[0]
    if ((Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash -ine $expected) { throw 'Rustup checksum mismatch.' }
    & $installer -y --profile minimal --default-toolchain 1.99.0 --no-modify-path
    if ($LASTEXITCODE -ne 0) { throw 'Rust installation failed.' }
} else {
    & $rustup toolchain install 1.99.0 --profile minimal
    if ($LASTEXITCODE -ne 0) { throw 'Rust toolchain setup failed.' }
}
$me3Dir = Join-Path $repo '.tools\me3-v0.13.0'
if (-not (Test-Path -LiteralPath (Join-Path $me3Dir 'bin\me3.exe'))) {
    $archive = Join-Path $downloads 'me3-v0.13.0.zip'
    Invoke-WebRequest -Uri 'https://github.com/garyttierney/me3/releases/download/v0.13.0/me3-windows-amd64.zip' -OutFile $archive
    if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ine 'a8b693c574106f20532ab1e8b58b2452f37370679b938a884fe75f87f9b68c2b') {
        throw 'me3 release checksum mismatch.'
    }
    Expand-Archive -LiteralPath $archive -DestinationPath $me3Dir
}
. (Join-Path $PSScriptRoot 'native-env.ps1')
Set-EldenCraftNativeEnvironment -RepoRoot $repo
& rustc +1.99.0 --version
& cmake --version
& (Join-Path $me3Dir 'bin\me3.exe') --version
Write-Output 'Native tools ready. Game files and saves were not changed by this installer.'
