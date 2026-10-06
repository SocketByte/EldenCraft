"""Observe bridge healing messages read-only, without opening game process memory."""

from __future__ import annotations
import argparse
import ctypes
from ctypes import wintypes
import json
from pathlib import Path
import struct
import time


def sample(receipts: bool = False) -> dict:
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
        4,
        False,
        "Local\\EldenCraftHealing" if receipts else "Local\\EldenCraftHealingHost",
    )
    if not handle:
        return {"status": "mapping absent"}
    view = None
    try:
        view = api.MapViewOfFile(handle, 4, 0, 0, 4096)
        if not view:
            raise ctypes.WinError(ctypes.get_last_error())
        for _ in range(3):
            before = ctypes.c_ulonglong.from_address(view + 8).value
            data = ctypes.string_at(view, 4096)
            after = ctypes.c_ulonglong.from_address(view + 8).value
            if (
                before
                and not before & 1
                and before == after == struct.unpack_from("<Q", data, 8)[0]
            ):
                break
        else:
            return {"status": "publication changing"}
        magic, version = struct.unpack_from("<II", data)
        if magic != (0x52484345 if receipts else 0x4C484345) or version != 1:
            return {"status": "unsupported mapping"}
        frame, timestamp, pid, flags = struct.unpack_from("<QQII", data, 16)
        result = {
            "status": "sampled",
            "frame": frame,
            "age_ms": api.GetTickCount64() - timestamp,
            "pid": pid,
            "flags": flags,
        }
        if not receipts:
            epoch, map_id, hp, max_hp, ack_result, ack_session, ack_sequence = (
                struct.unpack_from("<QIffIQQ", data, 40)
            )
            result.update(
                epoch=epoch,
                map=map_id,
                hp=hp,
                max_hp=max_hp,
                ack_result=ack_result,
                ack_session=ack_session,
                ack_sequence=ack_sequence,
            )
        else:
            session, host_pid, map_id, epoch, host_frame, count = struct.unpack_from(
                "<QIIQQI", data, 40
            )
            result.update(
                session=session,
                host_pid=host_pid,
                map=map_id,
                epoch=epoch,
                host_frame=host_frame,
                receipts=[],
            )
            if count > 8:
                return {"status": "invalid receipt count"}
            for n in range(count):
                values = struct.unpack_from("<QQQQIIffI", data, 128 + n * 64)
                result["receipts"].append(
                    dict(
                        zip(
                            (
                                "sequence",
                                "consumption",
                                "time_ms",
                                "server_tick",
                                "source",
                                "amplifier",
                                "amount",
                                "guest_max_hp",
                                "remaining_ticks",
                            ),
                            values,
                        )
                    )
                )
        return result
    finally:
        if view:
            api.UnmapViewOfFile(view)
        api.CloseHandle(handle)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--receipts", action="store_true")
    parser.add_argument("--samples", type=int, default=1)
    parser.add_argument("--interval", type=float, default=0.1)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    rows = []
    for index in range(max(1, min(1200, args.samples))):
        value = sample(args.receipts)
        rows.append(value)
        print(json.dumps(value), flush=True)
        if index + 1 < args.samples:
            time.sleep(max(0.02, min(5, args.interval)))
    if args.output:
        args.output.write_text(json.dumps(rows, indent=2), encoding="utf-8")
