"""Standalone discovery, backup integrity and source checks use disposable data."""

import hashlib
import json
from pathlib import Path
import sys
import zipfile

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools import backup, source_check, steam


def test_backup_round_trip_preserves_newer_files_and_backs_up_overwritten_state(
    tmp_path,
):
    source = tmp_path / "saves"
    source.mkdir()
    (source / "save.sl2").write_bytes(b"original game state")
    (source / "config").mkdir()
    (source / "config/options.ini").write_text("original options")
    root = tmp_path / "backups"
    snapshot = backup.create(source, "game-saves", "test snapshot", root)
    manifest = backup.verify(snapshot)
    assert (
        manifest["files"]["save.sl2"]["sha256"]
        == hashlib.sha256(b"original game state").hexdigest()
    )
    (source / "save.sl2").write_bytes(b"newer game state")
    (source / "new.txt").write_text("preserve this")
    backup.restore(snapshot, source, root)
    assert (source / "save.sl2").read_bytes() == b"original game state"
    assert (source / "new.txt").read_text() == "preserve this"
    previous = next((root / "pre-restore").glob("*.zip"))
    with zipfile.ZipFile(previous) as archive:
        assert archive.read("save.sl2") == b"newer game state"


@pytest.mark.parametrize("name", ["../escape", "bad/name", "", "bad\\name"])
def test_backup_rejects_unsafe_names_before_writing(tmp_path, name):
    source = tmp_path / "saves"
    source.mkdir()
    (source / "save").write_bytes(b"game state")
    root = tmp_path / "backups"
    with pytest.raises(ValueError, match="backup name"):
        backup.create(source, name, root=root)
    assert not root.exists()


def test_backup_rejects_empty_and_recursive_sources(tmp_path):
    source = tmp_path / "saves"
    source.mkdir()
    with pytest.raises(ValueError, match="empty"):
        backup.create(source, "saves", root=tmp_path / "backups")
    (source / "save").write_bytes(b"game state")
    with pytest.raises(ValueError, match="outside"):
        backup.create(source, "saves", root=source / "backups")
    assert not (source / "backups").exists()


@pytest.mark.parametrize(
    "member",
    ["../escape", "/escape", "C:/escape", "folder\\escape", "folder/../escape"],
)
def test_restore_rejects_traversal_before_target_changes(tmp_path, member):
    archive = tmp_path / "invalid.zip"
    data = b"invalid member"
    manifest = {
        "schema": 1,
        "files": {
            member: {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        },
    }
    with zipfile.ZipFile(archive, "w") as out:
        out.writestr(member, data)
        out.writestr(backup.MANIFEST, json.dumps(manifest))
    target = tmp_path / "restore"
    with pytest.raises(ValueError, match="member path"):
        backup.restore(archive, target, tmp_path / "backups")
    assert not target.exists()


def test_restore_rejects_corruption_before_overwriting(tmp_path):
    archive = tmp_path / "invalid.zip"
    manifest = {"schema": 1, "files": {"save": {"size": 8, "sha256": "0" * 64}}}
    with zipfile.ZipFile(archive, "w") as out:
        out.writestr("save", b"corrupt!")
        out.writestr(backup.MANIFEST, json.dumps(manifest))
    target = tmp_path / "restore"
    target.mkdir()
    (target / "save").write_bytes(b"current state")
    with pytest.raises(ValueError, match="content"):
        backup.restore(archive, target, tmp_path / "backups")
    assert (target / "save").read_bytes() == b"current state"
    assert not (tmp_path / "backups").exists()


def test_backup_propagates_unreadable_directory_errors(tmp_path, monkeypatch):
    source = tmp_path / "saves"
    source.mkdir()

    def inaccessible(path, **kwargs):
        kwargs["onerror"](PermissionError("unreadable source"))
        return iter(())

    monkeypatch.setattr(backup.os, "walk", inaccessible)
    with pytest.raises(PermissionError, match="unreadable"):
        backup.create(source, "saves", root=tmp_path / "backups")
    assert not (tmp_path / "backups").exists()


def test_steam_discovery_uses_library_manifest_and_existing_executable(tmp_path):
    steam_root = tmp_path / "Steam"
    library = tmp_path / "Library"
    (steam_root / "steamapps").mkdir(parents=True)
    (steam_root / "steamapps/libraryfolders.vdf").write_text(
        '"libraryfolders" { "0" { "path" "' + library.as_posix() + '" } }'
    )
    (library / "steamapps").mkdir(parents=True)
    (library / "steamapps/appmanifest_1245620.acf").write_text(
        '"AppState" { "installdir" "ELDEN RING" }'
    )
    game = library / "steamapps/common/ELDEN RING/Game"
    assert steam.find_elden_ring([steam_root]) is None
    game.mkdir(parents=True)
    (game / "eldenring.exe").write_bytes(b"fixture, never executed")
    assert steam.find_elden_ring([steam_root]) == game


def test_source_check_works_without_git_and_excludes_private_runtime_state(
    tmp_path, capsys
):
    (tmp_path / "source.py").write_text("value = 1\n")
    (tmp_path / "resource.json").write_text('{"value":1}')
    for folder in (".local", ".cache", "build"):
        (tmp_path / folder).mkdir()
        (tmp_path / folder / "generated.py").write_text("invalid generated code !")
    (tmp_path / "config").mkdir()
    (tmp_path / "config/local.json").write_text("private machine configuration")
    assert source_check.check(tmp_path) == 0
    assert "2 source files" in capsys.readouterr().out
    (tmp_path / "resource.json").write_text('{"broken":')
    assert source_check.check(tmp_path) == 1
