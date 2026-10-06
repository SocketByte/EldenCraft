"""Release contents, compatibility checks and deterministic packaging."""

import hashlib
import json
import shutil
import struct
import zipfile

import pytest

from tools import package_release


@pytest.fixture
def source(tmp_path):
    root = tmp_path / "source"
    originals = (
        "EldenCraft.cmd",
        "scripts/windows.ps1",
        "config/windows-release.json",
        "config/campaign.json",
        "minecraft/gradle.properties",
        "pyproject.toml",
        "README.md",
        "LICENSE",
        "THIRD_PARTY_NOTICES.md",
        "docs/building.md",
        "docs/campaign-configuration.md",
    )
    for name in originals:
        destination = root / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(package_release.ROOT / name, destination)
    for name, data in {
        "assets/eldencraft-logo.png": b"logo fixture",
        "assets/showcase/torrent.jpg": b"showcase fixture",
        "assets/showcase/nether.webp": b"showcase fixture",
        "assets/showcase/mining.webp": b"showcase fixture",
        "assets/showcase/merchant.webp": b"showcase fixture",
        "assets/showcase/building.webp": b"showcase fixture",
        "assets/showcase/combat.webp": b"showcase fixture",
        "compositor/shaders/EldenCraftPassthrough.fx": b"shader fixture",
        "compositor/config/EldenCraftPreset.ini": b"preset fixture",
    }.items():
        destination = root / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(data)
    binary = bytearray(256)
    binary[:2] = b"MZ"
    struct.pack_into("<I", binary, 0x3C, 128)
    binary[128:132] = b"PE\0\0"
    struct.pack_into("<H", binary, 132, 0x8664)
    for name in (
        ".local/native-build/release/eldencraft_core.dll",
        ".local/native-build/release/eldencraft_native.dll",
        ".local/compositor-build/EldenCraftCompositor.addon64",
    ):
        destination = root / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(binary)
    version = package_release.properties(root)["version"]
    jar = root / f"minecraft/build/libs/eldencraft-bridge-{version}.jar"
    jar.parent.mkdir(parents=True)
    with zipfile.ZipFile(jar, "w") as archive:
        archive.writestr(
            "eldencraft-campaign-default.json",
            (root / "config/campaign.json").read_bytes(),
        )
        archive.writestr(
            "fabric.mod.json",
            json.dumps(
                {
                    "id": "eldencraft_bridge",
                    "version": version,
                    "depends": {"minecraft": "26.3"},
                }
            ),
        )
    return root


def test_release_contains_only_public_files_and_valid_hashes(source, tmp_path):
    for name in ("config/local.json", ".local/saves/player.sl2", ".env"):
        path = source / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("private fixture")
    archive = package_release.build(source, tmp_path / "release")
    with zipfile.ZipFile(archive) as zipped:
        names = set(zipped.namelist())
        assert not names & {"config/local.json", ".env"}
        assert not any("saves" in name or name.endswith(".sl2") for name in names)
        assert "EldenCraft.cmd" in names
        assert "payload/eldencraft-bridge.jar" in names
        manifest = json.loads(zipped.read("release-manifest.json"))
        assert {entry["path"] for entry in manifest["files"]} == names - {
            "release-manifest.json"
        }
        for entry in manifest["files"]:
            data = zipped.read(entry["path"])
            assert entry["size"] == len(data)
            assert entry["sha256"] == hashlib.sha256(data).hexdigest()
    assert (
        archive.with_suffix(".zip.sha256")
        .read_text()
        .startswith(hashlib.sha256(archive.read_bytes()).hexdigest())
    )


def test_packaging_is_reproducible_across_output_directories(source, tmp_path):
    first = package_release.build(source, tmp_path / "first")
    second = package_release.build(source, tmp_path / "second")
    assert first.read_bytes() == second.read_bytes()


def test_wrong_native_architecture_is_rejected(source, tmp_path):
    path = source / ".local/native-build/release/eldencraft_core.dll"
    data = bytearray(path.read_bytes())
    struct.pack_into("<H", data, 132, 0x14C)
    path.write_bytes(data)
    with pytest.raises(ValueError, match="Windows x64"):
        package_release.build(source, tmp_path / "release")
    assert not (tmp_path / "release").exists()


def test_dependency_drift_is_rejected(source, tmp_path):
    path = source / "config/windows-release.json"
    config = json.loads(path.read_text())
    config["minecraft"]["fabric_loader"] = "0.0.0"
    path.write_text(json.dumps(config))
    with pytest.raises(ValueError, match="differs from Gradle"):
        package_release.build(source, tmp_path / "release")


def test_stale_minecraft_artifact_is_rejected(source, tmp_path):
    version = package_release.properties(source)["version"]
    path = source / f"minecraft/build/libs/eldencraft-bridge-{version}.jar"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr(
            "fabric.mod.json",
            json.dumps({"id": "eldencraft_bridge", "version": "0.0.0"}),
        )
    with pytest.raises(ValueError, match="artifact version"):
        package_release.build(source, tmp_path / "release")


def test_stale_campaign_defaults_are_rejected(source, tmp_path):
    path = source / "config/campaign.json"
    config = json.loads(path.read_text())
    config["stamina"]["regenPerSecond"] = 17
    path.write_text(json.dumps(config))
    with pytest.raises(ValueError, match="campaign defaults differ"):
        package_release.build(source, tmp_path / "release")


def test_modified_extracted_release_is_rejected(source, tmp_path):
    output = tmp_path / "release"
    package_release.build(source, output)
    extracted = output / (output / "current.txt").read_text().strip()
    (extracted / "payload/eldencraft_core.dll").write_bytes(b"corrupt")
    with pytest.raises(ValueError, match="was modified"):
        package_release.build(source, output)


def test_pinned_license_override_and_platform_filter(source, tmp_path):
    dependency = tmp_path / "dependency"
    dependency.mkdir()
    override = source / "licenses/overrides/example-1.2.3.txt"
    override.parent.mkdir(parents=True)
    override.write_text("pinned upstream notice")
    metadata = tmp_path / "cargo.json"
    package = {
        "id": "selected-package",
        "name": "example",
        "version": "1.2.3",
        "source": "registry+fixture",
        "manifest_path": str(dependency / "Cargo.toml"),
    }
    metadata.write_text(
        json.dumps(
            {
                "packages": [
                    package,
                    {**package, "id": "other-platform", "name": "unused"},
                ],
                "resolve": {"nodes": [{"id": "selected-package"}]},
            }
        )
    )
    archive = package_release.build(source, tmp_path / "release", metadata)
    with zipfile.ZipFile(archive) as zipped:
        assert (
            zipped.read("licenses/rust/example-1.2.3/example-1.2.3.txt")
            == b"pinned upstream notice"
        )
        assert not any("unused" in name for name in zipped.namelist())


def test_dependency_licenses_are_included(source, tmp_path):
    dependency = tmp_path / "dependency"
    dependency.mkdir()
    (dependency / "LICENSE-MIT").write_text("dependency notice")
    metadata = tmp_path / "cargo.json"
    metadata.write_text(
        json.dumps(
            {
                "packages": [
                    {
                        "name": "example",
                        "version": "1.2.3",
                        "source": "registry+fixture",
                        "manifest_path": str(dependency / "Cargo.toml"),
                    }
                ]
            }
        )
    )
    archive = package_release.build(source, tmp_path / "release", metadata)
    with zipfile.ZipFile(archive) as zipped:
        assert (
            zipped.read("licenses/rust/example-1.2.3/LICENSE-MIT")
            == b"dependency notice"
        )
