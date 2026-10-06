"""Validate project source without a Git checkout or external toolkit."""

import ast
import json
import os
from pathlib import Path
import re
import tomllib

IGNORED = {
    ".cache",
    ".tools",
    ".local",
    ".venv",
    ".git",
    ".gradle",
    ".pytest_cache",
    "__pycache__",
    "build",
    "dist",
    "target",
    "run",
    "logs",
}
SECRET = re.compile(
    r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|AKIA[0-9A-Z]{16}|sk-(?:proj-|ant-)?[A-Za-z0-9_-]{32,})"
    r"|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"
)


def source_files(root: Path):
    for directory, children, files in os.walk(root, followlinks=False):
        children[:] = sorted(name for name in children if name not in IGNORED)
        for name in sorted(files):
            path = Path(directory) / name
            if path.relative_to(root).as_posix() == "config/local.json":
                continue
            if not path.is_symlink():
                yield path


def check(root: Path) -> int:
    failures = []
    files = list(source_files(root))
    for path in files:
        relative = path.relative_to(root).as_posix()
        if path.name == ".env" or path.name.endswith(".env"):
            failures.append(f"Private environment file: {relative}")
        if path.suffix in {".dll", ".exe", ".pdb", ".sl2", ".bak"}:
            failures.append(f"Runtime artifact in source: {relative}")
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        if SECRET.search(text):
            failures.append(f"Possible credential in {relative}")
        try:
            if path.suffix == ".py":
                ast.parse(text, filename=relative)
            elif path.suffix == ".json":
                # Fabric expands the string-valued version placeholder at build time.
                json.loads(text)
            elif path.suffix == ".toml":
                tomllib.loads(text)
        except (SyntaxError, ValueError) as error:
            failures.append(f"Invalid source {relative}: {error}")
    for failure in failures:
        print(f"FAIL: {failure}")
    print(
        f'{"FAIL" if failures else "PASS"}: {len(files)} source files, {len(failures)} failures'
    )
    return int(bool(failures))
