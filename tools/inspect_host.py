"""Read ECHS host or ECCB combat publications; never access game memory."""

from __future__ import annotations

import argparse
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import struct
import time


def sample(combat: bool = False) -> dict:
    if os.name != "nt":
        raise RuntimeError("The local host mapping is available on Windows only")
    api = ctypes.WinDLL("kernel32", use_last_error=True)
    api.OpenFileMappingW.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.LPCWSTR]
    api.OpenFileMappingW.restype = wintypes.HANDLE
    api.MapViewOfFile.argtypes = [
        wintypes.HANDLE,
        wintypes.DWORD,
        wintypes.DWORD,
        wintypes.DWORD,
        ctypes.c_size_t,
    ]
    api.MapViewOfFile.restype = ctypes.c_void_p
    api.UnmapViewOfFile.argtypes = [ctypes.c_void_p]
    api.CloseHandle.argtypes = [wintypes.HANDLE]
    api.GetTickCount64.restype = ctypes.c_ulonglong
    handle = api.OpenFileMappingW(
        4, False, "Local\\EldenCraftCombat" if combat else "Local\\EldenCraftHost"
    )
    if not handle:
        return {"status": "combat mapping absent" if combat else "host mapping absent"}
    view = None
    try:
        view = api.MapViewOfFile(handle, 4, 0, 0, 256)
        if not view:
            raise ctypes.WinError(ctypes.get_last_error())
        for _ in range(5):
            before = ctypes.c_ulonglong.from_address(view + 8).value
            data = ctypes.string_at(view, 256)
            after = ctypes.c_ulonglong.from_address(view + 8).value
            if (
                before
                and before == after == struct.unpack_from("<Q", data, 8)[0]
                and not before & 1
            ):
                break
        else:
            return {"status": "publication in progress"}
        magic, version = struct.unpack_from("<II", data)
        if combat:
            if magic != 0x42434345 or version != 1:
                return {"status": "unsupported combat publication", "version": version}
            length = struct.unpack_from("<I", data, 48)[0]
            if not 1 <= length <= 128:
                return {"status": "invalid combat item"}
            return {
                "status": "sampled",
                "version": version,
                "frame": struct.unpack_from("<Q", data, 16)[0],
                "age_ms": api.GetTickCount64() - struct.unpack_from("<Q", data, 24)[0],
                "pid": struct.unpack_from("<I", data, 32)[0],
                "flags": struct.unpack_from("<I", data, 36)[0],
                "accepted_swing": struct.unpack_from("<Q", data, 40)[0],
                "item": data[64 : 64 + length].decode("utf-8", errors="replace"),
                "damage": struct.unpack_from("<i", data, 52)[0],
                "max_damage": struct.unpack_from("<i", data, 56)[0],
                "accepted_charge": struct.unpack_from("<f", data, 60)[0],
                "session": struct.unpack_from("<Q", data, 192)[0],
            }
        if magic != 0x53484345 or version not in (1, 2):
            return {"status": "unsupported publication", "version": version}
        result = {
            "status": "sampled",
            "version": version,
            "frame": struct.unpack_from("<Q", data, 16)[0],
            "age_ms": api.GetTickCount64() - struct.unpack_from("<Q", data, 24)[0],
            "pid": struct.unpack_from("<I", data, 32)[0],
            "flags": struct.unpack_from("<I", data, 36)[0],
            "camera": struct.unpack_from("<3d", data, 40),
            "forward": struct.unpack_from("<3f", data, 64),
            "vertical_fov_degrees": struct.unpack_from("<f", data, 76)[0],
            "feet": struct.unpack_from("<3d", data, 80),
            "hp": struct.unpack_from("<2i", data, 104),
            "buttons": struct.unpack_from("<I", data, 112)[0],
        }
        if version == 2:
            mode, seconds, yaw, speed, grounded, region = struct.unpack_from(
                "<IfffII", data, 136
            )
            result.update(
                view_mode=mode,
                time_seconds=seconds,
                avatar_yaw=yaw,
                movement_speed=speed,
                grounded=bool(grounded),
                map_id=region,
                sprinting=bool(result["flags"] & 16),
            )
            extension_flags, runes = struct.unpack_from("<II", data, 160)
            result["runes"] = runes if extension_flags & 1 else None
            if result["flags"] & 8:
                result["minecraft_day_ticks"] = int(((seconds - 21600) % 86400) / 3.6)
        return result
    finally:
        if view:
            api.UnmapViewOfFile(view)
        api.CloseHandle(handle)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=int, default=1)
    parser.add_argument(
        "--interval",
        type=float,
        default=1,
        help="Seconds between samples (0.01-5); 0.02 captures jump takeoff",
    )
    parser.add_argument("--output", type=Path)
    parser.add_argument("--combat", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.samples <= 6000:
        parser.error("--samples must be between 1 and 6000")
    if not 0.01 <= args.interval <= 5:
        parser.error("--interval must be between 0.01 and 5 seconds")
    rows = []
    started = time.monotonic()
    for index in range(args.samples):
        if index:
            time.sleep(max(0, started + index * args.interval - time.monotonic()))
        row = sample(args.combat)
        row["elapsed_seconds"] = time.monotonic() - started
        rows.append(row)
        print(json.dumps(row, allow_nan=False), flush=True)
    if args.output:
        args.output.write_text(
            json.dumps(rows, indent=2, allow_nan=False) + "\n", encoding="utf-8"
        )
