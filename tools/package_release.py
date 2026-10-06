"""Build a verified Windows distribution from an explicit list of release artifacts."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def properties(root: Path) -> dict[str, str]:
    return dict(
        line.strip().split("=", 1)
        for line in (root / "minecraft/gradle.properties").read_text().splitlines()
        if "=" in line and not line.lstrip().startswith("#")
    )


def require_x64(data: bytes, name: str) -> None:
    try:
        pe_offset = struct.unpack_from("<I", data, 0x3C)[0]
        if data[:2] != b"MZ" or data[pe_offset : pe_offset + 4] != b"PE\0\0":
            raise ValueError
        if struct.unpack_from("<H", data, pe_offset + 4)[0] != 0x8664:
            raise ValueError
    except (ValueError, struct.error):
        raise ValueError(f"Release artifact is not a Windows x64 PE: {name}") from None


def collect(
    root: Path, cargo_metadata: Path | None = None
) -> tuple[str, dict[str, bytes]]:
    props = properties(root)
    version = props["version"]
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Invalid release version")
    project = tomllib.loads((root / "pyproject.toml").read_text())
    if project["project"]["version"] != version:
        raise ValueError("Python and Minecraft release versions differ")
    deps = json.loads((root / "config/windows-release.json").read_text())
    for key, expected in (
        ("minecraft_version", deps["minecraft"]["version"]),
        ("loader_version", deps["minecraft"]["fabric_loader"]),
        ("fabric_api_version", deps["minecraft"]["fabric_api"]["version"]),
    ):
        if props[key] != expected:
            raise ValueError(f"Dependency manifest differs from Gradle: {key}")
    inputs = {
        "EldenCraft.cmd": "EldenCraft.cmd",
        "scripts/windows.ps1": "scripts/windows.ps1",
        "config/windows-release.json": "config/windows-release.json",
        "config/campaign.json": "config/campaign.json",
        "README.md": "README.md",
        "LICENSE": "LICENSE",
        "THIRD_PARTY_NOTICES.md": "THIRD_PARTY_NOTICES.md",
        "docs/building.md": "docs/building.md",
        "docs/campaign-configuration.md": "docs/campaign-configuration.md",
        "assets/eldencraft-logo.png": "assets/eldencraft-logo.png",
        "assets/showcase/torrent.jpg": "assets/showcase/torrent.jpg",
        "assets/showcase/nether.webp": "assets/showcase/nether.webp",
        "assets/showcase/mining.webp": "assets/showcase/mining.webp",
        "assets/showcase/merchant.webp": "assets/showcase/merchant.webp",
        "assets/showcase/building.webp": "assets/showcase/building.webp",
        "assets/showcase/combat.webp": "assets/showcase/combat.webp",
        "payload/eldencraft_native.dll": ".local/native-build/release/eldencraft_native.dll",
        "payload/eldencraft_core.dll": ".local/native-build/release/eldencraft_core.dll",
        "payload/addons/EldenCraftCompositor.addon64": ".local/compositor-build/EldenCraftCompositor.addon64",
        "payload/shaders/EldenCraftPassthrough.fx": "compositor/shaders/EldenCraftPassthrough.fx",
        "payload/EldenCraftPreset.ini": "compositor/config/EldenCraftPreset.ini",
        "payload/eldencraft-bridge.jar": f"minecraft/build/libs/eldencraft-bridge-{version}.jar",
    }
    files = {name: (root / source).read_bytes() for name, source in inputs.items()}
    for name in files:
        if name.endswith((".dll", ".addon64")):
            require_x64(files[name], name)
    jar = root / inputs["payload/eldencraft-bridge.jar"]
    with zipfile.ZipFile(jar) as archive:
        metadata = json.loads(archive.read("fabric.mod.json"))
        if metadata["id"] != "eldencraft_bridge" or metadata["version"] != version:
            raise ValueError("Minecraft artifact version does not match the release")
        if metadata["depends"]["minecraft"] != deps["minecraft"]["version"]:
            raise ValueError(
                "Minecraft artifact compatibility does not match the release"
            )
        if (
            archive.read("eldencraft-campaign-default.json")
            != files["config/campaign.json"]
        ):
            raise ValueError(
                "Minecraft artifact campaign defaults differ from the release"
            )
    for path in sorted((root / "licenses").glob("*")):
        if path.is_file():
            files[f"licenses/{path.name}"] = path.read_bytes()
    if cargo_metadata is not None:
        metadata = json.loads(cargo_metadata.read_text(encoding="utf-8-sig"))
        nodes = metadata.get("resolve", {}).get("nodes")
        selected = {node["id"] for node in nodes} if nodes is not None else None
        for package in metadata["packages"]:
            if package["source"] is None:
                continue
            if selected is not None and package["id"] not in selected:
                continue
            directory = Path(package["manifest_path"]).parent
            notices = []
            for candidate in (directory, *list(directory.parents)[:3]):
                notices = sorted(
                    path
                    for path in candidate.iterdir()
                    if path.is_file()
                    and path.name.lower().startswith(
                        ("license", "licence", "copying", "notice")
                    )
                )
                if notices:
                    break
            if not notices:
                override = (
                    root
                    / "licenses/overrides"
                    / f"{package['name']}-{package['version']}.txt"
                )
                if not override.is_file():
                    raise ValueError(
                        f"Missing third-party license for {package['name']}"
                    )
                notices = [override]
            for path in notices:
                files[
                    f"licenses/rust/{package['name']}-{package['version']}/{path.name}"
                ] = path.read_bytes()
    manifest = {
        "schema_version": 1,
        "version": version,
        "files": [
            {"path": name, "sha256": digest(data), "size": len(data)}
            for name, data in sorted(files.items())
        ],
    }
    files["release-manifest.json"] = (
        json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    ).encode()
    return version, files


def build(root: Path, output: Path, cargo_metadata: Path | None = None) -> Path:
    version, files = collect(root, cargo_metadata)
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f"EldenCraft-{version}-windows-x64.zip"
    temporary = archive.with_suffix(".zip.part")
    with zipfile.ZipFile(
        temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9
    ) as zipped:
        for name, data in sorted(files.items()):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = 0o100644 << 16
            zipped.writestr(entry, data, compresslevel=9)
    with zipfile.ZipFile(temporary) as zipped:
        if zipped.testzip() is not None:
            raise ValueError("Release ZIP integrity check failed")
        for name, expected in files.items():
            if zipped.read(name) != expected:
                raise ValueError(f"Release ZIP content differs: {name}")
    checksum = digest(temporary.read_bytes())
    temporary.replace(archive)
    (output / (archive.name + ".sha256")).write_text(
        f"{checksum}  {archive.name}\n", encoding="utf-8"
    )
    # A new immutable directory per content hash lets the source launcher use the built package.
    unpacked = output / f"EldenCraft-{version}-{checksum[:12]}"
    if not unpacked.exists():
        unpacked.mkdir()
        for name, data in files.items():
            destination = unpacked / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
    for name, expected in files.items():
        if (unpacked / name).read_bytes() != expected:
            raise ValueError(f"Existing extracted release was modified: {name}")
    pointer = output / "current.txt.part"
    pointer.write_text(unpacked.name + "\n", encoding="utf-8")
    pointer.replace(output / "current.txt")
    return archive


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / ".local/releases")
    parser.add_argument("--cargo-metadata", type=Path)
    args = parser.parse_args()
    try:
        archive = build(ROOT, args.output, args.cargo_metadata)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print(f"Release packaging failed: {error}", file=sys.stderr)
        return 1
    print(f"Windows release: {archive}")
    print(f"SHA256: {digest(archive.read_bytes())}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
