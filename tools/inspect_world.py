"""Read the two bounded shared-world mailboxes without touching game memory or input."""

from __future__ import annotations
import argparse
import ctypes
from ctypes import wintypes
import json
from pathlib import Path
import struct
import time

CAPACITY = 2 * 1024 * 1024


def sample(guest: bool = False, full: bool = False) -> dict:
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
    name = "Local\\EldenCraftWorldGuest" if guest else "Local\\EldenCraftWorldHost"
    handle = api.OpenFileMappingW(4, False, name)
    if not handle:
        return {"status": "mapping absent", "side": "guest" if guest else "host"}
    view = None
    try:
        view = api.MapViewOfFile(handle, 4, 0, 0, CAPACITY)
        if not view:
            raise ctypes.WinError(ctypes.get_last_error())
        for _ in range(3):
            before = ctypes.c_ulonglong.from_address(view + 8).value
            header = ctypes.string_at(view, 64)
            magic, version, sequence, frame, stamp, pid, flags, length = (
                struct.unpack_from("<IIQQQIII", header)
            )
            if magic != (0x47574345 if guest else 0x48574345) or version != 1:
                return {"status": "unsupported mapping"}
            if length == 0 or length > CAPACITY - 64 or flags & ~1 or any(header[44:]):
                return {"status": "invalid bounded header"}
            data = ctypes.string_at(view + 64, length)
            after = ctypes.c_ulonglong.from_address(view + 8).value
            if before and not before & 1 and before == after == sequence:
                break
        else:
            return {"status": "publication changing"}
        payload = json.loads(data)
        result = {
            "status": "sampled",
            "side": "guest" if guest else "host",
            "frame": frame,
            "age_ms": api.GetTickCount64() - stamp,
            "pid": pid,
            "active": bool(flags & 1),
            "bytes": length,
        }
        if full:
            result["payload"] = payload
        else:
            for key in (
                "epoch",
                "map",
                "source_map",
                "coordinate_mode",
                "offset",
                "source_to_region",
                "feet",
                "camera",
                "hp",
                "max_hp",
                "terrain_revision",
                "terrain_ready",
                "terrain_degraded",
                "terrain_bounds",
                "terrain_scanned_cells",
                "terrain_total_cells",
                "terrain_truncated",
                "terrain_rays",
                "features",
                "player_id",
                "player_uuid",
                "guest_session",
                "session",
                "host_pid",
                "observed_frame",
                "blocks_revision",
                "ack",
                "ack_incoming",
                "native_collider_status",
                "native_collider_error",
                "native_mob_status",
            ):
                if key in payload:
                    result[key] = payload[key]
            for key in (
                "terrain_boxes",
                "targets",
                "blocks",
                "mobs",
                "events",
                "incoming",
                "projectiles",
                "projectile_impacts",
            ):
                if key in payload:
                    result[key + "_count"] = len(payload[key])
            for key in (
                "acks",
                "events",
                "incoming",
                "projectiles",
                "projectile_impacts",
            ):
                if payload.get(key):
                    # Keep the normal live trace readable while preserving full paths
                    # under --full for a specific trajectory investigation.
                    entries = []
                    for entry in payload[key][-8:]:
                        compact = dict(entry)
                        path = compact.pop("trajectory", None)
                        if path is not None:
                            compact["trajectory_points"] = len(path)
                            if path:
                                compact["launch_position"] = path[0]
                                compact["path_end"] = path[-1]
                        entries.append(compact)
                    result[key] = entries
        return result
    except (ValueError, UnicodeError) as error:
        return {"status": "invalid payload", "reason": str(error)}
    finally:
        if view:
            api.UnmapViewOfFile(view)
        api.CloseHandle(handle)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--guest", action="store_true")
    parser.add_argument("--both", action="store_true")
    parser.add_argument("--full", action="store_true")
    parser.add_argument("--samples", type=int, default=1)
    parser.add_argument("--interval", type=float, default=0.1)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    rows = []
    count = max(1, min(6000, args.samples))
    for index in range(count):
        row = (
            {"host": sample(False, args.full), "guest": sample(True, args.full)}
            if args.both
            else sample(args.guest, args.full)
        )
        rows.append(row)
        print(json.dumps(row), flush=True)
        if index + 1 < count:
            time.sleep(max(0.02, min(5, args.interval)))
    if args.output:
        args.output.write_text(json.dumps(rows, indent=2), encoding="utf-8")
