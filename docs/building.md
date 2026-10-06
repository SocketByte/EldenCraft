# Building EldenCraft

The source tree contains a Fabric client mod, two Rust DLLs and a C++ ReShade
add-on. Player releases contain the built components and the Windows launcher.

## Toolchain

Build on Windows x64 with Java 25, Python 3.12+, uv, Git, the Windows SDK and
Visual Studio 2022 C++ Build Tools. Rust 1.99.0, ReShade 6.8.0 SDK headers and me3
are pinned by the setup scripts. Minecraft and Fabric versions are pinned in
`minecraft/gradle.properties` and `config/windows-release.json`.

From PowerShell in the repository root:

```powershell
.\scripts\eldencraft.ps1 setup
.\scripts\eldencraft.ps1 setup-native
.\scripts\build-release.ps1
```

`setup-native` downloads local Rust/me3 dependencies and installs Microsoft's
C++ Build Tools if required. That installer may request administrator privileges.
`build-release.ps1` runs the Python, Rust, Java and compositor checks, builds all
components, checks source files, collects dependency licenses and packages the
Windows release.

Outputs:

- `.local/releases/EldenCraft-<version>-windows-x64.zip`
- A matching `.zip.sha256` checksum file.
- A verified extracted package used by the root `EldenCraft.cmd`.

Use `build-release.ps1 -SkipBuild` to package artifacts that have already passed
the checks. It still validates versions, architecture, source files and native
runtime dependencies. ZIP entries have fixed timestamps and sorted paths, making
packaging deterministic for identical build artifacts.

## Reproducible builds

Release builds are byte-for-byte reproducible: the same source and toolchain
produce the same release ZIP, whatever folder the repository is cloned into.

- **Rust DLLs:** `scripts/native-env.ps1` links with `/Brepro` (no timestamps; the
  PDB identity comes from the content) and remaps the repository and Cargo paths
  to `/eldencraft` and `/cargo`. Builds therefore embed no machine paths.
- **Compositor add-on:** compiled and linked with `/Brepro`; Release builds carry no
  debug information.
- **Minecraft jar:** Loom writes fixed entry timestamps. Gradle's configuration
  cache is off because it made Loom's client-only manifest list depend on leftovers
  from earlier builds.
- **Release ZIP:** `tools/package_release.py` writes sorted entries with fixed
  timestamps.

Use these exact toolchain versions to reproduce a release:

| Tool | Version |
| --- | --- |
| Rust | 1.99.0 (installed by `setup-native`) |
| MSVC (Visual Studio 2022 Build Tools) | toolset 14.44.35207, `cl` 19.44.35229, `link` 14.44.35229.0 |
| Windows SDK | 10.0.26100.0 |
| CMake / Ninja | 3.31.6 / 1.12.1 (bundled with the Build Tools) |
| Java | Eclipse Temurin 25.0.4.1 |

A different MSVC version changes linker metadata and the static C runtime, so
the DLL hashes then differ even though the code is the same. To verify a
release, build it, then compare checksums:

```powershell
.\scripts\eldencraft.ps1 setup
.\scripts\eldencraft.ps1 setup-native
.\scripts\build-release.ps1
Get-Content .local\releases\EldenCraft-<version>-windows-x64.zip.sha256
```

## Individual checks

```powershell
.\scripts\eldencraft.ps1 test
.\scripts\eldencraft.ps1 test-native --offline
.\scripts\eldencraft.ps1 build-minecraft --offline
.\scripts\eldencraft.ps1 check-source
powershell -NoProfile -ExecutionPolicy Bypass -File tests\windows-launcher.Tests.ps1
```

The Minecraft build runs its Java conformance suites. Compositor build and CTest
checks run through `scripts/setup-passthrough.ps1`. The Windows launcher tests use
temporary directories and fixtures; they do not start either game.

Python uses Black 26.5.1, Java uses google-java-format 1.37.0, and Rust uses the
Rust 1.99.0 toolchain's rustfmt. Use those deterministic tools when editing source.

## Layout

| Directory | Responsibility |
| --- | --- |
| `minecraft/` | Fabric client, vanilla simulation and rendering |
| `native/src/` | Elden Ring camera, movement, input, collision and damage |
| `native/loader/` | Persistent loader and optional core hot reload |
| `compositor/` | D3D12 composition, GPU sharing and shader |
| `passthrough/` | Frame ABI readers and capture utilities |
| `tools/` | Discovery, verified backups and release packaging |
| `scripts/` | Player launcher and developer build commands |
| `tests/` | Tool and launcher regression checks |

Dependencies, builds, local configuration and runtime state stay in ignored
`.tools`, `.cache`, `.venv`, `.local` and `config/local.json`. Runtime releases use
`%LOCALAPPDATA%\EldenCraft` by default. Never include game files, extracted assets,
personal configuration, saves, credentials or crash dumps in a release.

## Release checks

Run the automated checks and create the Windows ZIP. Before publishing, test that
ZIP on a clean supported Windows PC: sign-in, first-run downloads, world creation,
both game launches, inventory, movement, mining, placement, combat, camera modes,
restart and upgrade with existing saves. Check stale-state handling when either
game closes or enters a menu. Builds and fixture tests do not replace this check.

### Publishing

Releases are published by GitHub Actions. Set the same version in
`minecraft/gradle.properties` and `pyproject.toml`, then push a matching tag:

```powershell
git tag v0.21.9
git push origin v0.21.9
```

`.github/workflows/release.yml` rejects a tag that differs from those versions,
runs the full Windows build and test workflow, verifies the packaged checksum and
creates the GitHub Release with the ZIP, its `.sha256` file, install notes and
generated release notes. Re-running the workflow for an existing release replaces
its assets. Ordinary pushes and pull requests run the same build without
publishing.

Update compatibility pins together when a supported game build changes. Preserve third-party notices and
include the collected license directory in every binary distribution.
