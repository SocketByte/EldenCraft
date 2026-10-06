"""Profile setup is exercised only against isolated temporary directories."""

import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

MODULE_PATH = Path(__file__).resolve().parents[1] / "tools/workspace.py"
SPEC = importlib.util.spec_from_file_location(
    "eldencraft_workspace_under_test", MODULE_PATH
)
workspace = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(workspace)

API_VERSION = "0.142.3+26.3"
TOKEN = "temporary-test-credential-" + "a" * 40


def tree_snapshot(root):
    """Include directories too so failed preflight cannot leave empty folders."""
    return {
        str(path.relative_to(root)): path.read_bytes() if path.is_file() else None
        for path in root.rglob("*")
    }


@pytest.fixture
def lab(tmp_path, monkeypatch):
    project = tmp_path
    config_path = project / "config/local.json"
    config_path.parent.mkdir(parents=True)
    profiles = tmp_path / "launcher/profiles"
    profile = profiles / "EldenCraft"
    profile.mkdir(parents=True)
    configuration = {
        "minecraft_dev_profile_dir": str(profile),
        "modrinth_profiles_dir": str(profiles),
    }
    config_path.write_text(json.dumps(configuration), encoding="utf-8")
    minecraft = project / "minecraft"
    artifacts = minecraft / "build/libs"
    artifacts.mkdir(parents=True)
    (minecraft / "gradle.properties").write_text(
        f"# Isolated fixture, not downloaded game content\nfabric_api_version={API_VERSION}\n",
        encoding="utf-8",
    )
    mod = artifacts / "eldencraft-bridge-0.1.0.jar"
    mod.write_bytes(b"temporary fixture: bridge")
    (artifacts / "eldencraft-bridge-0.1.0-sources.jar").write_bytes(
        b"sources are not installed"
    )
    api = (
        tmp_path
        / ".cache/gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api"
        / API_VERSION
        / "fixture-hash"
        / f"fabric-api-{API_VERSION}.jar"
    )
    api.parent.mkdir(parents=True)
    api.write_bytes(b"temporary fixture: api")
    monkeypatch.setattr(workspace, "ROOT", tmp_path)
    monkeypatch.setattr(workspace, "PROJECT", project)
    monkeypatch.setattr(workspace, "CONFIG", config_path)
    return {
        "root": tmp_path,
        "project": project,
        "profile": profile,
        "config_path": config_path,
        "configuration": configuration,
        "mod": mod,
        "api": api,
    }


def test_valid_copy_is_idempotent_and_does_not_create_relay_credentials(lab, capsys):
    workspace.prepare_profile()
    profile = lab["profile"]
    for source in (lab["mod"], lab["api"]):
        assert (profile / "mods" / source.name).read_bytes() == source.read_bytes()
    assert sorted(path.name for path in (profile / "mods").iterdir()) == sorted(
        [lab["mod"].name, lab["api"].name]
    )
    assert not (lab["root"] / ".local/bridge-token.txt").exists()
    assert not (profile / "config/eldencraft-bridge.json").exists()
    after_first = tree_snapshot(lab["root"])
    workspace.prepare_profile()
    assert tree_snapshot(lab["root"]) == after_first
    captured = capsys.readouterr()
    assert TOKEN not in captured.out + captured.err


@pytest.mark.parametrize("source_key", ["mod", "api"])
def test_conflicting_jar_rejected_before_any_writes(lab, source_key):
    installed = lab["profile"] / "mods" / lab[source_key].name
    installed.parent.mkdir(parents=True)
    installed.write_bytes(b"existing user mod: preserve exactly")
    before = tree_snapshot(lab["root"])
    with pytest.raises(ValueError, match="overwrite existing mod"):
        workspace.prepare_profile()
    assert tree_snapshot(lab["root"]) == before


def test_obsolete_relay_configuration_is_ignored_and_preserved(lab, capsys):
    config = lab["profile"] / "config/eldencraft-bridge.json"
    config.parent.mkdir(parents=True)
    contents = ("retired invalid config " + TOKEN).encode("utf-8")
    config.write_bytes(contents)
    workspace.prepare_profile()
    assert config.read_bytes() == contents
    assert (lab["profile"] / "mods" / lab["mod"].name).read_bytes() == lab[
        "mod"
    ].read_bytes()
    assert not (lab["root"] / ".local/bridge-token.txt").exists()
    captured = capsys.readouterr()
    assert TOKEN not in captured.out + captured.err


def test_profile_outside_configured_parent_is_rejected(lab):
    unrelated = lab["root"] / "unrelated/EldenCraft"
    unrelated.mkdir(parents=True)
    lab["configuration"]["minecraft_dev_profile_dir"] = str(unrelated)
    lab["config_path"].write_text(json.dumps(lab["configuration"]), encoding="utf-8")
    before = tree_snapshot(lab["root"])
    with pytest.raises(ValueError, match="directly inside"):
        workspace.prepare_profile()
    assert tree_snapshot(lab["root"]) == before


def test_wrong_api_version_rejected_without_changes(lab):
    installed = lab["profile"] / "mods/fabric-api-0.100.0+1.21.jar"
    installed.parent.mkdir(parents=True)
    installed.write_bytes(b"user's older everyday API")
    before = tree_snapshot(lab["root"])
    with pytest.raises(ValueError, match="different Fabric API version"):
        workspace.prepare_profile()
    assert tree_snapshot(lab["root"]) == before


def test_doctor_discovers_native_tools_without_path(lab, monkeypatch):
    program_files = lab["root"] / "Program Files"
    program_files_x86 = lab["root"] / "Program Files x86"
    vswhere = program_files_x86 / "Microsoft Visual Studio/Installer/vswhere.exe"
    installation = program_files / "Microsoft Visual Studio/2022/BuildTools"
    compiler = installation / "VC/Tools/MSVC/14.44.35207/bin/Hostx64/x64/cl.exe"
    linker = compiler.with_name("link.exe")
    cmake = (
        installation
        / "Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe"
    )
    rustc = (
        lab["root"]
        / ".tools/rustup/toolchains/1.99.0-x86_64-pc-windows-msvc/bin/rustc.exe"
    )
    cargo = rustc.with_name("cargo.exe")
    me3 = lab["root"] / ".tools/me3-v0.13.0/bin/me3.exe"
    for executable in (vswhere, compiler, linker, cmake, rustc, cargo, me3):
        executable.parent.mkdir(parents=True, exist_ok=True)
        executable.write_bytes(b"test fixture, never executed")
    monkeypatch.setenv("ProgramFiles", str(program_files))
    monkeypatch.setenv("ProgramFiles(x86)", str(program_files_x86))
    monkeypatch.setattr(workspace.shutil, "which", lambda name: None)
    commands = []

    def discover(command, **kwargs):
        commands.append(command)
        assert command[0] == str(vswhere)
        return SimpleNamespace(returncode=0, stdout=str(installation) + "\n", stderr="")

    monkeypatch.setattr(workspace.subprocess, "run", discover)
    monkeypatch.setattr(
        workspace, "command_version", lambda command: "verified fixture version"
    )
    tools = workspace.native_tools(
        {"me3_executable": str(lab["root"] / "missing-me3.exe")}
    )
    assert tools["cpp_compiler"] == str(compiler)
    assert tools["msvc_linker"] == str(linker)
    assert tools["msvc_toolset_version"] == "14.44.35207"
    assert tools["cmake_executable"] == str(cmake)
    assert tools["rustc_executable"] == str(rustc)
    assert tools["cargo_executable"] == str(cargo)
    assert tools["me3"] == str(me3)
    assert "Microsoft.VisualStudio.Component.VC.Tools.x86.x64" in commands[0]


def test_native_artifact_does_not_claim_source_version_is_binary_version(
    lab, monkeypatch
):
    manifest = lab["project"] / "native/Cargo.toml"
    manifest.parent.mkdir(parents=True)
    manifest.write_text(
        '[package]\nname = "fixture"\nversion = "0.1.0"\n', encoding="utf-8"
    )
    dll = lab["root"] / ".local/native-build/release/eldencraft_native.dll"
    dll.parent.mkdir(parents=True)
    dll.write_bytes(b"native fixture")
    monkeypatch.setattr(workspace, "binary_file_version", lambda path: None)
    report = workspace.native_artifact()
    assert report["present"] is True
    assert report["source_package_version"] == "0.1.0"
    assert report["binary_version"] is None
    assert report["sha256"] == workspace.hashlib.sha256(b"native fixture").hexdigest()


def test_doctor_reports_current_architecture_without_promoting_build_to_game_verification(
    lab, monkeypatch, capsys
):
    configuration = {**lab["configuration"], "unused_secret": TOKEN}
    lab["config_path"].write_text(json.dumps(configuration), encoding="utf-8")
    monkeypatch.setattr(workspace, "command_version", lambda command: None)
    monkeypatch.setattr(workspace.shutil, "which", lambda name: None)
    monkeypatch.setattr(
        workspace,
        "native_tools",
        lambda config: {
            "cpp_compiler": "local-cl",
            "cmake": "local-cmake",
            "rustc": "rustc 1.99.0",
            "cargo": "cargo 1.99.0",
            "me3": "local-me3",
        },
    )
    monkeypatch.setattr(
        workspace,
        "native_artifact",
        lambda: {
            "present": True,
            "source_package_version": "0.1.0",
            "binary_version": None,
        },
    )
    output = lab["root"] / "evidence/doctor.json"
    workspace.doctor(output)
    report = json.loads(output.read_text(encoding="utf-8"))
    assert report["stage"] == "minecraft-passthrough"
    assert report["game_integration_verified"] is False
    assert not any(
        "setup-native" in item or "build-native" in item
        for item in report["next_requirements"]
    )
    assert any("real game" in item for item in report["next_requirements"])
    captured = capsys.readouterr()
    assert TOKEN not in captured.out + captured.err + output.read_text(encoding="utf-8")
