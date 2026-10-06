"""Read-only discovery of the Elden Ring installation in Steam libraries."""

import os
from pathlib import Path
import re


def default_roots() -> list[Path]:
    roots = [
        Path(os.environ.get("ProgramFiles(x86)", "C:/Program Files (x86)")) / "Steam"
    ]
    if os.name == "nt":
        import winreg

        try:
            with winreg.OpenKey(
                winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam"
            ) as key:
                roots.insert(0, Path(winreg.QueryValueEx(key, "SteamPath")[0]))
        except OSError:
            pass
    return roots


def find_elden_ring(roots: list[Path] | None = None) -> Path | None:
    libraries = []
    for root in default_roots() if roots is None else roots:
        libraries.append(root)
        try:
            text = (root / "steamapps/libraryfolders.vdf").read_text(encoding="utf-8")
        except OSError:
            continue
        libraries.extend(
            Path(value.replace("\\\\", "\\"))
            for value in re.findall(r'"path"\s+"([^"\r\n]+)"', text)
        )
    for library in dict.fromkeys(libraries):
        try:
            text = (library / "steamapps/appmanifest_1245620.acf").read_text(
                encoding="utf-8"
            )
        except OSError:
            continue
        match = re.search(r'"installdir"\s+"([^"\r\n]+)"', text)
        if not match or Path(match[1]).name != match[1] or match[1] in {".", ".."}:
            continue
        game = library / "steamapps/common" / match[1] / "Game"
        if (game / "eldenring.exe").is_file():
            return game
    return None
