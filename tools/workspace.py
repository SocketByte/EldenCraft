"""Workspace discovery, dedicated-profile preparation and source validation."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone

PROJECT = Path(__file__).resolve().parents[1]
ROOT = PROJECT
CONFIG = PROJECT / "config" / "local.json"
sys.path.insert(0, str(ROOT))


def accessible_directory(path: Path) -> bool:
    try:
        return path.is_dir()
    except OSError:
        return False


def command_version(command: list[str]) -> str | None:
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=15)
        return (
            (result.stdout + result.stderr).strip().splitlines()[0]
            if result.returncode == 0
            else None
        )
    except (OSError, subprocess.TimeoutExpired, IndexError):
        return None


def first_file(paths) -> Path | None:
    for path in paths:
        if path and Path(path).is_file():
            return Path(path)
    return None


def version_key(path: Path) -> tuple[int, ...]:
    return tuple(int(part) for part in re.findall(r"\d+", path.name))


def native_tools(config: dict) -> dict:
    """Read installation locations; a developer shell or global PATH edits are unnecessary."""
    program_files = Path(os.environ.get("ProgramFiles", "C:/Program Files"))
    program_files_x86 = Path(
        os.environ.get("ProgramFiles(x86)", "C:/Program Files (x86)")
    )
    vswhere = first_file(
        [
            program_files_x86 / "Microsoft Visual Studio/Installer/vswhere.exe",
            shutil.which("vswhere"),
        ]
    )
    installation = None
    if vswhere:
        try:
            found = subprocess.run(
                [
                    str(vswhere),
                    "-latest",
                    "-products",
                    "*",
                    "-requires",
                    "Microsoft.VisualStudio.Component.VC.Tools.x86.x64",
                    "-property",
                    "installationPath",
                ],
                capture_output=True,
                text=True,
                timeout=15,
            )
            if found.returncode == 0 and found.stdout.strip():
                candidate = Path(found.stdout.strip().splitlines()[0])
                if candidate.is_dir():
                    installation = candidate
        except (OSError, subprocess.TimeoutExpired):
            pass
    compiler = linker = toolset_version = None
    visual_studio_cmake = None
    if installation:
        toolsets = sorted(
            (installation / "VC/Tools/MSVC").glob("*"), key=version_key, reverse=True
        )
        for toolset in toolsets:
            candidate = toolset / "bin/Hostx64/x64/cl.exe"
            if candidate.is_file():
                compiler = candidate
                linker = first_file([candidate.parent / "link.exe"])
                toolset_version = toolset.name
                break
        visual_studio_cmake = (
            installation
            / "Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe"
        )
    cmake = first_file(
        [
            visual_studio_cmake,
            program_files / "CMake/bin/cmake.exe",
            shutil.which("cmake"),
        ]
    )

    toolchains = ROOT / ".tools/rustup/toolchains"
    preferred_rust = toolchains / "1.99.0-x86_64-pc-windows-msvc/bin/rustc.exe"
    other_rust = [
        toolchain / "bin/rustc.exe"
        for toolchain in sorted(toolchains.glob("*"), key=version_key, reverse=True)
    ]
    rustc = first_file([preferred_rust, *other_rust, shutil.which("rustc")])
    cargo = first_file(
        [rustc.parent / "cargo.exe" if rustc else None, shutil.which("cargo")]
    )
    me3_candidates = (
        [Path(config["me3_executable"])] if config.get("me3_executable") else []
    )
    for bundle in sorted((ROOT / ".tools").glob("me3*"), key=version_key, reverse=True):
        me3_candidates.extend([bundle / "bin/me3.exe", bundle / "me3.exe"])
    me3 = first_file([*me3_candidates, shutil.which("me3")])
    return {
        "visual_studio_installation": str(installation) if installation else None,
        "msvc_toolset_version": toolset_version,
        "cpp_compiler": (
            str(compiler)
            if compiler
            else next(
                (
                    shutil.which(name)
                    for name in ("cl", "clang++", "g++")
                    if shutil.which(name)
                ),
                None,
            )
        ),
        "msvc_linker": str(linker) if linker else None,
        "cmake_executable": str(cmake) if cmake else None,
        "cmake": command_version([str(cmake), "--version"]) if cmake else None,
        "rustc_executable": str(rustc) if rustc else None,
        "rustc": command_version([str(rustc), "--version"]) if rustc else None,
        "cargo_executable": str(cargo) if cargo else None,
        "cargo": command_version([str(cargo), "--version"]) if cargo else None,
        "me3": str(me3) if me3 else None,
        "me3_version": command_version([str(me3), "--version"]) if me3 else None,
    }


def file_sha256(path: Path) -> str:
    with path.open("rb") as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def binary_file_version(path: Path) -> str | None:
    """Read an optional Windows version resource without loading or executing the DLL."""
    if os.name != "nt":
        return None
    try:
        import ctypes
        from ctypes import wintypes

        library = ctypes.WinDLL("version", use_last_error=True)
        library.GetFileVersionInfoSizeW.argtypes = [
            wintypes.LPCWSTR,
            ctypes.POINTER(wintypes.DWORD),
        ]
        library.GetFileVersionInfoSizeW.restype = wintypes.DWORD
        library.GetFileVersionInfoW.argtypes = [
            wintypes.LPCWSTR,
            wintypes.DWORD,
            wintypes.DWORD,
            ctypes.c_void_p,
        ]
        library.GetFileVersionInfoW.restype = wintypes.BOOL
        library.VerQueryValueW.argtypes = [
            ctypes.c_void_p,
            wintypes.LPCWSTR,
            ctypes.POINTER(ctypes.c_void_p),
            ctypes.POINTER(wintypes.UINT),
        ]
        library.VerQueryValueW.restype = wintypes.BOOL
        ignored = wintypes.DWORD()
        size = library.GetFileVersionInfoSizeW(str(path), ctypes.byref(ignored))
        if not size:
            return None
        buffer = ctypes.create_string_buffer(size)
        if not library.GetFileVersionInfoW(str(path), 0, size, buffer):
            return None
        pointer, length = ctypes.c_void_p(), wintypes.UINT()
        if (
            not library.VerQueryValueW(
                buffer, "\\", ctypes.byref(pointer), ctypes.byref(length)
            )
            or length.value < 52
        ):
            return None
        info = ctypes.cast(pointer, ctypes.POINTER(wintypes.DWORD))
        if info[0] != 0xFEEF04BD:
            return None
        return ".".join(
            str(value)
            for value in (
                info[2] >> 16,
                info[2] & 0xFFFF,
                info[3] >> 16,
                info[3] & 0xFFFF,
            )
        )
    except (OSError, AttributeError, ValueError):
        return None


def native_artifact() -> dict:
    dll = ROOT / ".local/native-build/release/eldencraft_native.dll"
    result = {
        "path": str(dll),
        "present": dll.is_file(),
        "binary_version": None,
        "source_package_version": None,
        "sha256": None,
    }
    try:
        import tomllib

        manifest = tomllib.loads(
            (PROJECT / "native/Cargo.toml").read_text(encoding="utf-8")
        )
        result["source_package_version"] = manifest.get("package", {}).get("version")
    except (ImportError, OSError, ValueError):
        pass
    core = dll.with_name("eldencraft_core.dll")
    result["core"] = {
        "path": str(core),
        "present": core.is_file(),
        "sha256": file_sha256(core) if core.is_file() else None,
    }
    if result["present"]:
        try:
            result["binary_version"] = binary_file_version(dll)
            result["size_bytes"] = dll.stat().st_size
            result["sha256"] = file_sha256(dll)
        except OSError:
            result["read_status"] = "unreadable"
    # Cargo package metadata is source metadata, not proof of the binary's version or runtime state.
    result["version_status"] = (
        "Windows version resource"
        if result["binary_version"]
        else "No binary version resource; source package version reported separately"
    )
    return result


def initialize() -> None:
    if CONFIG.exists():
        print("Kept existing config/local.json.")
        return
    config = json.loads((CONFIG.parent / "local.example.json").read_text())
    from tools.steam import find_elden_ring

    game = find_elden_ring()
    if game:
        config["elden_ring_game_dir"] = str(game)
    roaming = Path(os.environ.get("APPDATA", str(Path.home() / "AppData/Roaming")))
    for key, path in (
        ("elden_ring_save_dir", roaming / "EldenRing"),
        ("modrinth_profiles_dir", roaming / "ModrinthApp/profiles"),
    ):
        if accessible_directory(path):
            config[key] = str(path)
    config["me3_executable"] = shutil.which("me3")
    if config.get("modrinth_profiles_dir"):
        dev_profile = Path(config["modrinth_profiles_dir"]) / "EldenCraft"
        if accessible_directory(dev_profile):
            config["minecraft_dev_profile_dir"] = str(dev_profile)
    CONFIG.write_text(json.dumps(config, indent=2) + "\n", encoding="utf-8")
    print("Created ignored config/local.json from read-only discovery.")


def doctor(output: Path) -> None:
    config = json.loads(CONFIG.read_text(encoding="utf-8")) if CONFIG.exists() else {}
    game_dir = config.get("elden_ring_game_dir")
    exe = Path(game_dir) / "eldenring.exe" if game_dir else None
    checks: dict = {
        "python": sys.version.split()[0],
        "uv": command_version(["uv", "--version"]),
        "java": command_version(["java", "-version"]),
        "javac": command_version(["javac", "-version"]),
        "cpp_compiler_on_path": next(
            (
                shutil.which(name)
                for name in ("cl", "clang++", "g++")
                if shutil.which(name)
            ),
            None,
        ),
        "ffmpeg": command_version(["ffmpeg", "-version"]),
        "local_config_exists": CONFIG.exists(),
        "elden_ring_executable_exists": bool(exe and exe.is_file()),
        "elden_ring_sha256": None,
        "modrinth_profiles": [],
        "minecraft_dev_profile_dir": config.get("minecraft_dev_profile_dir"),
        **native_tools(config),
        "native_dll": native_artifact(),
    }
    if exe and exe.is_file():
        try:
            checks["elden_ring_sha256"] = file_sha256(exe)
        except OSError:
            checks["elden_ring_hash_status"] = "unreadable"
    profiles = config.get("modrinth_profiles_dir")
    if profiles:
        try:
            for profile in sorted(Path(profiles).iterdir()):
                if profile.is_dir():
                    # Filenames only; never open launcher auth databases or world data.
                    try:
                        apis = [
                            p.name for p in (profile / "mods").glob("*fabric-api*.jar")
                        ]
                    except OSError:
                        apis = []
                    checks["modrinth_profiles"].append(
                        {"name": profile.name, "fabric_api_files": apis}
                    )
        except OSError:
            checks["modrinth_profiles_status"] = "folder not accessible"
    report = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "stage": "minecraft-passthrough",
        "game_integration_verified": False,
        "verification_status": "Tool and artifact inventory only; gameplay requires a live offline check.",
        "checks": checks,
        "next_requirements": [
            *(
                ["Run setup-native to install missing native build dependencies."]
                if not all(
                    checks.get(name)
                    for name in ("cpp_compiler", "cmake", "rustc", "cargo", "me3")
                )
                else []
            ),
            *(
                ["Run build-native to create the release DLL."]
                if not checks["native_dll"]["present"]
                else []
            ),
            "Build the Minecraft guest and native compositor before preparing the offline runtime.",
            "Verify the combined rendering, controls, movement and gameplay in the real game during an offline session.",
        ],
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    for key, value in checks.items():
        print(f'{key}: {value if value is not None else "not found"}')
    print(
        f"Diagnostics saved to {output}. Tool and artifact discovery does not verify gameplay."
    )


def prepare_profile() -> None:
    """Copy built mods to the explicitly configured dedicated profile, never replace conflicts."""
    config = json.loads(CONFIG.read_text(encoding="utf-8"))
    profile_name = config.get("minecraft_dev_profile_dir")
    profiles_name = config.get("modrinth_profiles_dir")
    if not profile_name or not profiles_name:
        raise ValueError(
            "Set minecraft_dev_profile_dir and modrinth_profiles_dir in config/local.json first."
        )
    profile, profiles = Path(profile_name).resolve(), Path(profiles_name).resolve()
    if not profile.is_dir() or profile.parent != profiles:
        raise ValueError(
            "The dedicated profile must exist directly inside the configured Modrinth profiles folder."
        )
    properties = {}
    for line in (PROJECT / "minecraft/gradle.properties").read_text().splitlines():
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    artifacts = [
        p
        for p in (PROJECT / "minecraft/build/libs").glob("*.jar")
        if not p.name.endswith("-sources.jar")
    ]
    api_version = properties["fabric_api_version"]
    api_cache = (
        ROOT
        / ".cache/gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api"
        / api_version
    )
    apis = list(api_cache.glob(f"*/fabric-api-{api_version}.jar"))
    if len(artifacts) != 1 or not apis:
        raise ValueError(
            "Run build-minecraft successfully first; expected one bridge jar and the resolved Fabric API jar."
        )
    # Prevent accidentally mixing the older everyday Modrinth instance with this pinned dev target.
    for installed in (profile / "mods").glob("*fabric-api*.jar"):
        if installed.name != apis[0].name:
            raise ValueError(
                "The selected profile has a different Fabric API version. Use the clean 26.3 instance."
            )
    copies = [
        (source, profile / "mods" / source.name) for source in (artifacts[0], apis[0])
    ]
    for source, target in copies:
        if target.exists() and target.read_bytes() != source.read_bytes():
            raise ValueError(f"Refusing to overwrite existing mod: {target.name}")
    mods = profile / "mods"
    if mods.exists() and not mods.is_dir():
        raise ValueError("The profile mods path must be a directory.")
    for source, target in copies:
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            shutil.copyfile(source, target)
        print(f"Ready in dedicated profile: {target.name}")
    campaign_link = profile / "config/eldencraft-campaign-link.json"
    if not campaign_link.exists():
        campaign_link.parent.mkdir(parents=True, exist_ok=True)
        campaign_link.write_text(
            json.dumps(
                {
                    "config": str(ROOT / "config/campaign.json"),
                    "directory": str(ROOT / ".local/eldencraft-runtime/data/campaign"),
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
    print(
        "Dedicated profile prepared. Start Minecraft; its EldenCraft world opens automatically."
    )


def check_source() -> int:
    from tools.source_check import check

    return check(ROOT)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command", choices=["init", "doctor", "prepare-profile", "check-source"]
    )
    parser.add_argument(
        "--output", type=Path, default=ROOT / ".local/evidence/doctor.json"
    )
    args = parser.parse_args()
    if args.command == "init":
        initialize()
    elif args.command == "doctor":
        doctor(args.output)
    elif args.command == "prepare-profile":
        prepare_profile()
    else:
        return check_source()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
