"""Create and verify local save/profile snapshots before EldenCraft changes them."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import sys
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = "_eldencraft_manifest.json"


def safe_relative(name: str) -> Path:
    path = PurePosixPath(name)
    if (
        not name
        or "\\" in name
        or ":" in name
        or path.is_absolute()
        or any(part in {"", ".", ".."} for part in name.split("/"))
    ):
        raise ValueError("Invalid snapshot member path")
    return Path(*path.parts)


def verify(snapshot: Path) -> dict:
    with zipfile.ZipFile(snapshot) as archive:
        manifest = json.loads(archive.read(MANIFEST))
        if (
            manifest.get("schema") != 1
            or not isinstance(manifest.get("files"), dict)
            or not manifest["files"]
        ):
            raise ValueError("Invalid backup manifest")
        for name in manifest["files"]:
            safe_relative(name)
        infos = archive.infolist()
        names = [entry.filename for entry in infos]
        if len(names) != len(set(names)) or set(names) != set(manifest["files"]) | {
            MANIFEST
        }:
            raise ValueError("Snapshot members do not match the manifest")
        for entry in infos:
            safe_relative(entry.filename)
            if stat.S_ISLNK(entry.external_attr >> 16):
                raise ValueError("Snapshot contains a symbolic link")
        for name, expected in manifest["files"].items():
            safe_relative(name)
            digest = hashlib.sha256()
            size = 0
            with archive.open(name) as stream:
                for chunk in iter(lambda: stream.read(1 << 20), b""):
                    digest.update(chunk)
                    size += len(chunk)
            if size != expected["size"] or digest.hexdigest() != expected["sha256"]:
                raise ValueError("Backup content does not match its manifest")
    return manifest


def create(
    source: Path, name: str, note: str = "", root: Path = ROOT / ".local/backups"
) -> Path:
    source = source.resolve(strict=True)
    root = root.resolve()
    if not source.is_dir():
        raise ValueError("Backup source must be a directory")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", name):
        raise ValueError("Invalid backup name")
    if root == source or source in root.parents:
        raise ValueError("Backup destination must be outside the source")
    paths = []

    def fail_scan(error):
        raise error

    for directory, children, files in os.walk(
        source, followlinks=False, onerror=fail_scan
    ):
        for item in [
            *(Path(directory) / child for child in children),
            *(Path(directory) / file for file in files),
        ]:
            if (
                item.is_symlink()
                or getattr(item.lstat(), "st_file_attributes", 0)
                & stat.FILE_ATTRIBUTE_REPARSE_POINT
            ):
                raise ValueError("Backup source contains a link or junction")
        paths.extend(Path(directory) / file for file in files)
    if not paths:
        raise ValueError("Refusing an empty backup")
    if sum(path.stat().st_size for path in paths) > 20 << 30:
        raise ValueError("Backup source exceeds 20 GiB")
    if any(path.relative_to(source).as_posix() == MANIFEST for path in paths):
        raise ValueError("Backup source contains the reserved manifest name")
    destination = root / name
    destination.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    snapshot = destination / f"{stamp}-{uuid.uuid4().hex[:8]}.zip"
    partial = snapshot.with_suffix(".partial")
    manifest = {
        "schema": 1,
        "source": str(source),
        "created": stamp,
        "note": note,
        "files": {},
    }
    try:
        with zipfile.ZipFile(
            partial, "x", zipfile.ZIP_DEFLATED, compresslevel=6
        ) as archive:
            for path in sorted(paths):
                relative = path.relative_to(source).as_posix()
                safe_relative(relative)
                before = path.stat()
                digest = hashlib.sha256()
                size = 0
                with (
                    path.open("rb") as incoming,
                    archive.open(relative, "w", force_zip64=True) as outgoing,
                ):
                    for chunk in iter(lambda: incoming.read(1 << 20), b""):
                        outgoing.write(chunk)
                        digest.update(chunk)
                        size += len(chunk)
                after = path.stat()
                if (before.st_size, before.st_mtime_ns) != (
                    after.st_size,
                    after.st_mtime_ns,
                ) or size != before.st_size:
                    raise ValueError("Source changed during backup")
                manifest["files"][relative] = {
                    "size": size,
                    "sha256": digest.hexdigest(),
                }
            archive.writestr(MANIFEST, json.dumps(manifest, indent=2))
        verify(partial)
        partial.rename(snapshot)
    except BaseException:
        partial.unlink(missing_ok=True)
        raise
    print(f"Verified backup: {snapshot} ({len(paths)} files)")
    return snapshot


def restore(snapshot: Path, target: Path, root: Path = ROOT / ".local/backups") -> None:
    manifest = verify(snapshot)
    target = target.resolve()
    # Preflight every destination before backing up or replacing anything.
    for name in manifest["files"]:
        destination = target / safe_relative(name)
        if target not in destination.resolve().parents:
            raise ValueError("Restore destination escapes the target")
        if destination.exists() and not destination.is_file():
            raise ValueError("Restore destination conflicts with a directory")
    if target.exists() and any(target.rglob("*")):
        create(target, "pre-restore", f"Before restoring {snapshot.name}", root)
    with zipfile.ZipFile(snapshot) as archive:
        for name in manifest["files"]:
            destination = target / safe_relative(name)
            destination.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(name) as incoming, destination.open("wb") as outgoing:
                for chunk in iter(lambda: incoming.read(1 << 20), b""):
                    outgoing.write(chunk)
    print(
        f"Restored {len(manifest['files'])} files to {target}; other files preserved."
    )


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    backup = commands.add_parser("create")
    backup.add_argument("source", type=Path)
    backup.add_argument("--name", required=True)
    backup.add_argument("--note", default="")
    check = commands.add_parser("verify")
    check.add_argument("snapshot", type=Path)
    recover = commands.add_parser("restore")
    recover.add_argument("snapshot", type=Path)
    recover.add_argument("--to", type=Path, required=True)
    recover.add_argument(
        "--yes",
        action="store_true",
        help="Confirm replacement after validation and a pre-restore backup",
    )
    args = parser.parse_args()
    if args.command == "create":
        create(args.source, args.name, args.note)
    elif args.command == "verify":
        result = verify(args.snapshot)
        print(f"Verified {len(result['files'])} files in {args.snapshot}")
    else:
        if not args.yes:
            parser.error("Restore requires --yes; close both games first.")
        restore(args.snapshot, args.to)


if __name__ == "__main__":
    # Redirected Windows consoles may use an encoding that cannot represent a
    # username or save path. Reporting a verified backup must still succeed.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(errors="backslashreplace")
    main()
