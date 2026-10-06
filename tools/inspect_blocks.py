"""Read block geometry/atlas/ACK metadata only; no pixels, game memory, or input."""

from __future__ import annotations
import argparse
import ctypes
from ctypes import wintypes
import json
from pathlib import Path
import struct
import time


def sample(suffix: str, magic: int) -> dict:
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
    handle = api.OpenFileMappingW(4, False, "Local\\EldenCraftBlock" + suffix)
    if not handle:
        return {"status": "mapping absent"}
    view = None
    try:
        view = api.MapViewOfFile(handle, 4, 0, 0, 128)
        if not view:
            raise ctypes.WinError(ctypes.get_last_error())
        for _ in range(3):
            before = ctypes.c_ulonglong.from_address(view + 8).value
            raw = ctypes.string_at(view, 128)
            after = ctypes.c_ulonglong.from_address(view + 8).value
            seq = struct.unpack_from("<Q", raw, 8)[0]
            if before and not before & 1 and before == seq == after:
                break
        else:
            return {"status": "publication changing"}
        if struct.unpack_from("<II", raw) != (magic, 1) or any(raw[104:]):
            return {"status": "invalid header"}
        pid, host_pid, epoch, region, flags = struct.unpack_from("<IIQII", raw, 16)
        session, stamp, revision, atlas_revision = struct.unpack_from("<QQQQ", raw, 40)
        count, stride, size, solid, cutout, translucent = struct.unpack_from(
            "<IIIIII", raw, 72
        )
        anchor = struct.unpack_from("<Q", raw, 96)[0]
        return {
            "status": "sampled",
            "sequence": seq,
            "pid": pid,
            "host_pid": host_pid,
            "epoch": epoch,
            "map": region,
            "active": flags == 1,
            "session": session,
            "age_ms": int(api.GetTickCount64()) - stamp,
            "revision": revision,
            "atlas_revision": atlas_revision,
            "anchor": anchor,
            "payload_bytes": size,
            **(
                {"width": count, "height": stride}
                if suffix == "Atlas"
                else {
                    "vertices": count,
                    "stride": stride,
                    "solid": solid,
                    "cutout": cutout,
                    "translucent": translucent,
                }
            ),
        }
    finally:
        if view:
            api.UnmapViewOfFile(view)
        api.CloseHandle(handle)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--samples", type=int, default=1)
    parser.add_argument("--interval", type=float, default=0.25)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    rows = []
    count = max(1, min(6000, args.samples))
    for index in range(count):
        row = {
            "mesh": sample("Mesh", 0x424D4345),
            "atlas": sample("Atlas", 0x41424345),
            "ack": sample("MeshAck", 0x414D4345),
        }
        rows.append(row)
        print(json.dumps(row), flush=True)
        if index + 1 < count:
            time.sleep(max(0.02, min(5, args.interval)))
    if args.output:
        args.output.write_text(json.dumps(rows, indent=2), encoding="utf-8")
