"""Read the bounded ECTG/ECDM bridge publications, never game process memory."""

from __future__ import annotations
import argparse
import ctypes
from ctypes import wintypes
import json
import struct
import time


def sample(damage: bool = False) -> dict:
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
    name = "Local\\EldenCraftDamage" if damage else "Local\\EldenCraftTargets"
    handle = api.OpenFileMappingW(4, False, name)
    if not handle:
        return {"status": "mapping absent", "name": name}
    view = None
    try:
        view = api.MapViewOfFile(handle, 4, 0, 0, 4096)
        if not view:
            raise ctypes.WinError(ctypes.get_last_error())
        for _ in range(5):
            before = ctypes.c_ulonglong.from_address(view + 8).value
            b = ctypes.string_at(view, 4096)
            after = ctypes.c_ulonglong.from_address(view + 8).value
            if (
                before
                and not before & 1
                and before == after == struct.unpack_from("<Q", b, 8)[0]
            ):
                break
        else:
            return {"status": "busy"}
        u32 = lambda at: struct.unpack_from("<I", b, at)[0]
        u64 = lambda at: struct.unpack_from("<Q", b, at)[0]
        f32 = lambda at: struct.unpack_from("<f", b, at)[0]
        v3 = lambda at: struct.unpack_from("<3f", b, at)
        if u32(0) != (0x4D444345 if damage else 0x47544345) or u32(4) != 1:
            return {"status": "unsupported"}
        result = {
            "status": "sampled",
            "frame": u64(16),
            "age_ms": api.GetTickCount64() - u64(24),
            "pid": u32(32),
            "flags": u32(36),
        }
        if damage:
            count = u32(64)
            if count > 32:
                return {"status": "invalid count"}
            result.update(
                session=u64(40),
                host_pid=u32(48),
                map=u32(52),
                epoch=u64(56),
                receipts=[],
            )
            for i in range(count):
                at = 128 + i * 112
                length = u32(at + 64)
                if length > 40:
                    return {"status": "invalid item"}
                result["receipts"].append(
                    {
                        "sequence": u64(at),
                        "target": u64(at + 8),
                        "generation": u64(at + 16),
                        "host_frame": u64(at + 24),
                        "attack": u64(at + 40),
                        "damage": f32(at + 48),
                        "durability": struct.unpack_from("<2i", b, at + 52),
                        "item": b[at + 72 : at + 72 + length].decode(
                            "utf-8", errors="replace"
                        ),
                    }
                )
        else:
            count = u32(52)
            if count > 16:
                return {"status": "invalid count"}
            result.update(
                epoch=u64(40),
                map=u32(48),
                camera=v3(56),
                forward=v3(68),
                obstruction=f32(88),
                scale=f32(92),
                ack_session=u64(96),
                ack_receipt=u64(104),
                ack_result=u32(112),
                targets=[],
            )
            for i in range(count):
                at = 128 + i * 80
                result["targets"].append(
                    {
                        "handle": u64(at),
                        "generation": u64(at + 8),
                        "min": v3(at + 16),
                        "max": v3(at + 28),
                        "hp": f32(at + 40),
                        "max_hp": f32(at + 44),
                        "flags": u32(at + 48),
                        "team": u32(at + 52),
                    }
                )
        return result
    finally:
        if view:
            api.UnmapViewOfFile(view)
        api.CloseHandle(handle)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--damage", action="store_true")
    parser.add_argument("--samples", type=int, default=1)
    args = parser.parse_args()
    if not 1 <= args.samples <= 60:
        parser.error("--samples must be 1..60")
    for i in range(args.samples):
        if i:
            time.sleep(1)
        print(json.dumps(sample(args.damage), allow_nan=False), flush=True)
