"""Checked MCPT frame reader with legacy and EldenCraft avatar-plane compatibility.

From the project root: python -m passthrough.frames --output overlay.png
The capture proves frame publication, not gameplay or host composition by itself.
"""

import argparse
import ctypes
from dataclasses import dataclass, asdict
import json
import math
import os
from pathlib import Path
import struct
import time
import zlib

NAME = "Local\\EldenCraftFrame"
MAGIC = 0x5450434D  # MCPT little-endian.
VERSION = 1
HEADER_BYTES = 4096
SLOTS = 3
SLOT_DESC = 256
SLOT_DESC_BYTES = 128
MAX_WIDTH = 1920
MAX_HEIGHT = 1080
STRIDE = MAX_WIDTH * MAX_HEIGHT * 4 * 3
MAPPING_BYTES = HEADER_BYTES + SLOTS * STRIDE
AVATAR_STRIDE = MAX_WIDTH * MAX_HEIGHT * 4 * 5
AVATAR_MAPPING_BYTES = HEADER_BYTES + SLOTS * AVATAR_STRIDE
BOTTOM_UP = 2
OVERLAY_ONLY = 8
SCENE_VALID = 16
AVATAR_VALID = 32
# Planes live in host-owned shared D3D12 textures (ECGT); the mapping has no pixels.
GPU_SHARED = 64
SCENE_OFFSET, SCENE_BYTES = 1024, 512
FRESHNESS_SECONDS = 2.0
_HEADER = struct.Struct("<IIIIQIIQiI")
_DESC = struct.Struct("<QQQIIfffIdddfffIQQ")


class FrameError(ValueError):
    """The publisher's layout or frame metadata is invalid."""


@dataclass(frozen=True)
class Header:
    published: int
    latest_slot: int
    publisher_pid: int
    stride: int

    @property
    def mapping_bytes(self):
        return HEADER_BYTES + SLOTS * self.stride


@dataclass(frozen=True)
class Scene:
    epoch: int
    anchor: int
    map: int
    host_pid: int
    camera: tuple
    world_to_clip: tuple
    clip_to_world: tuple
    mesh_revision: int
    atlas_revision: int
    mesh_session: int
    avatar_inverse: tuple
    avatar_mode: int


def parse_scene(raw):
    if len(raw) != SCENE_BYTES or struct.unpack_from("<II", raw) != (0x53464345, 1):
        raise FrameError("invalid scene extension")
    epoch, anchor, map_id, pid = struct.unpack_from("<QQII", raw, 8)
    camera = struct.unpack_from("<3d", raw, 32)
    projection, inverse = struct.unpack_from("<16f", raw, 64), struct.unpack_from(
        "<16f", raw, 128
    )
    mesh_revision, atlas_revision, mesh_session = struct.unpack_from("<3Q", raw, 192)
    avatar_inverse = struct.unpack_from("<16f", raw, 216)
    avatar_mode = struct.unpack_from("<I", raw, 280)[0]
    if not epoch or not anchor or not pid or any(raw[56:64]) or any(raw[284:]):
        raise FrameError("invalid scene identity/reserved bytes")
    mesh_identity = (mesh_revision, atlas_revision, mesh_session)
    if any(mesh_identity) and not all(mesh_identity):
        raise FrameError("incomplete excluded mesh identity")
    if not all(math.isfinite(v) for v in camera + projection + inverse) or any(
        abs(v) > 3e7 for v in camera
    ):
        raise FrameError("invalid scene camera")
    for row in range(4):
        for column in range(4):
            value = sum(
                projection[row * 4 + k] * inverse[k * 4 + column] for k in range(4)
            )
            if abs(value - (1 if row == column else 0)) > 0.025:
                raise FrameError("incoherent scene inverse")
    if avatar_mode not in (0, 1, 2) or not all(
        math.isfinite(v) and abs(v) <= 1e6 for v in avatar_inverse
    ):
        raise FrameError("invalid avatar projection")
    if not avatar_mode and any(avatar_inverse):
        raise FrameError("avatar projection without active mode")
    if avatar_mode:
        if any(
            abs(value) > 1e-5
            for index, value in enumerate(avatar_inverse)
            if index not in (0, 5, 10, 11, 14, 15)
        ):
            raise FrameError("avatar inverse contains world rotation or translation")
        determinant = (
            avatar_inverse[10] * avatar_inverse[15]
            - avatar_inverse[11] * avatar_inverse[14]
        )
        if (
            any(abs(avatar_inverse[index]) < 1e-8 for index in (0, 5, 14))
            or abs(determinant) < 1e-8
        ):
            raise FrameError("degenerate avatar perspective projection")
        for z in (0.1, 0.5, 0.9):
            view_z = avatar_inverse[10] * z + avatar_inverse[11]
            w = avatar_inverse[14] * z + avatar_inverse[15]
            if not math.isfinite(w) or abs(w) < 1e-8 or -view_z / w <= 0:
                raise FrameError("invalid avatar depth reconstruction")
    return Scene(
        epoch,
        anchor,
        map_id,
        pid,
        camera,
        projection,
        inverse,
        mesh_revision,
        atlas_revision,
        mesh_session,
        avatar_inverse,
        avatar_mode,
    )


@dataclass(frozen=True)
class Frame:
    published: int
    publisher_pid: int
    slot: int
    sequence: int
    minecraft_frame: int
    host_frame: int
    width: int
    height: int
    near: float
    far: float
    fov: float
    flags: int
    camera: tuple
    rotation: tuple
    first_person: bool
    capture_ns: int
    publish_ns: int
    overlay_rgba: bytes  # Immutable, top-down premultiplied RGBA8.
    world_rgba: bytes | None = None
    world_depth: bytes | None = None  # Immutable, top-down little-endian float32.
    scene: Scene | None = None
    avatar_rgba: bytes | None = (
        None  # Same immutable top-down premultiplied convention.
    )
    avatar_depth: bytes | None = None  # Raw window-depth float32, not linear metres.

    @property
    def identity(self):
        return self.publisher_pid, self.minecraft_frame, self.sequence, self.published

    def metadata(self):
        result = {
            name: getattr(self, name)
            for name in self.__dataclass_fields__
            if name
            not in (
                "overlay_rgba",
                "world_rgba",
                "world_depth",
                "avatar_rgba",
                "avatar_depth",
            )
        }
        result["world_available"] = not bool(self.flags & OVERLAY_ONLY)
        result["avatar_available"] = bool(self.flags & AVATAR_VALID)
        result["scene"] = asdict(self.scene) if self.scene is not None else None
        return result

    def depth_metres(self, x, y):
        """View-axis metres for one depth sample; infinity means empty/far plane."""
        if self.world_depth is None:
            raise FrameError("depth was not requested")
        if not 0 <= x < self.width or not 0 <= y < self.height:
            raise FrameError("pixel coordinate out of bounds")
        value = struct.unpack_from("<f", self.world_depth, (y * self.width + x) * 4)[0]
        if not math.isfinite(value) or not 0.0 <= value <= 1.0:
            raise FrameError("invalid depth value")
        # Both upstream formulas reduce to these when sampling window depth [0,1].
        # Flag 1 describes the projection's clip-space range, not the stored range.
        if self.flags & 4:
            return (
                math.inf
                if value == 0.0
                else self.near * self.far / (self.near + value * (self.far - self.near))
            )
        return (
            math.inf
            if value == 1.0
            else self.near * self.far / (self.far - value * (self.far - self.near))
        )


def parse_header(raw):
    if len(raw) < _HEADER.size:
        raise FrameError("truncated shared header")
    magic, version, header, slots, stride, width, height, published, latest, pid = (
        _HEADER.unpack_from(raw)
    )
    if (magic, version, header, slots, width, height) != (
        MAGIC,
        VERSION,
        HEADER_BYTES,
        SLOTS,
        MAX_WIDTH,
        MAX_HEIGHT,
    ) or stride not in (STRIDE, AVATAR_STRIDE):
        raise FrameError("unsupported shared-memory layout")
    if latest < -1 or latest >= SLOTS or pid == 0:
        raise FrameError("invalid publisher or slot")
    return Header(published, latest, pid, stride)


def _rows_top_down(raw, width, height, flags):
    if not flags & BOTTOM_UP:
        return raw
    row = width * 4
    return b"".join(raw[y * row : (y + 1) * row] for y in range(height - 1, -1, -1))


def read_latest(read, mapped_bytes=None, include_world=False, retries=3):
    """Read one immutable frame using read(offset, count)->bytes.

    No source pixels reach the caller before the sequence is rechecked. Readers
    never spin waiting for an odd/in-flight slot; at most three attempts occur.
    The callable must enforce bounds for its real mapping, including short reads.
    include_world also copies the avatar planes when flag32 makes them valid.
    """
    if mapped_bytes is not None and mapped_bytes not in (
        MAPPING_BYTES,
        AVATAR_MAPPING_BYTES,
    ):
        raise FrameError("incorrect mapped byte count")
    capacity = mapped_bytes if mapped_bytes is not None else AVATAR_MAPPING_BYTES

    def checked(offset, count):
        if offset < 0 or count < 0 or offset + count > capacity:
            raise FrameError("read outside mapping")
        data = read(offset, count)
        if len(data) != count:
            raise FrameError("truncated mapping")
        return bytes(data)

    for _ in range(min(max(retries, 1), 3)):
        header = parse_header(checked(0, _HEADER.size))
        if mapped_bytes is not None and mapped_bytes != header.mapping_bytes:
            raise FrameError("mapping size does not match declared stride")
        if header.published == 0 or header.latest_slot == -1:
            return None
        descriptor_offset = SLOT_DESC + header.latest_slot * SLOT_DESC_BYTES
        sequence = struct.unpack("<Q", checked(descriptor_offset, 8))[0]
        if sequence == 0 or sequence & 1:
            continue
        values = _DESC.unpack(checked(descriptor_offset, _DESC.size))
        (
            copied_seq,
            minecraft_frame,
            host_frame,
            width,
            height,
            near,
            far,
            fov,
            flags,
            cx,
            cy,
            cz,
            yaw,
            pitch,
            roll,
            first_person,
            capture_ns,
            publish_ns,
        ) = values
        if copied_seq != sequence:
            continue
        gpu_frame = bool(flags & GPU_SHARED)
        if not (
            1 <= width <= (3840 if gpu_frame else MAX_WIDTH)
            and 1 <= height <= (2160 if gpu_frame else MAX_HEIGHT)
        ):
            raise FrameError("frame dimensions outside supported bounds")
        if (
            flags & ~127
            or flags & 24 == 24
            or first_person not in (0, 1)
            or minecraft_frame == 0
        ):
            raise FrameError("invalid frame flags or counter")
        if flags & GPU_SHARED:
            raise FrameError(
                "pixels are in GPU-shared textures; set ELDENCRAFT_GPU_TRANSPORT=0 to capture them"
            )
        if flags & AVATAR_VALID and (
            not flags & SCENE_VALID or header.stride != AVATAR_STRIDE
        ):
            raise FrameError("avatar planes require scene and five-plane stride")
        if not all(
            math.isfinite(value)
            for value in (near, far, fov, cx, cy, cz, yaw, pitch, roll)
        ):
            raise FrameError("non-finite camera metadata")
        if not (0 < near < far and 0 < fov < 180):
            raise FrameError("invalid camera planes or field of view")
        if include_world and flags & OVERLAY_ONLY:
            raise FrameError("world/depth unavailable in overlay-only capture")
        scene = (
            parse_scene(
                checked(SCENE_OFFSET + SCENE_BYTES * header.latest_slot, SCENE_BYTES)
            )
            if flags & SCENE_VALID
            else None
        )
        if bool(flags & AVATAR_VALID) != bool(scene is not None and scene.avatar_mode):
            raise FrameError("avatar flag and projection mode disagree")
        layer_bytes = width * height * 4
        if layer_bytes * (5 if flags & AVATAR_VALID else 3) > header.stride:
            raise FrameError("frame planes exceed slot stride")
        base = HEADER_BYTES + header.latest_slot * header.stride
        overlay = checked(base + 2 * layer_bytes, layer_bytes)
        world = checked(base, layer_bytes) if include_world else None
        depth = checked(base + layer_bytes, layer_bytes) if include_world else None
        avatar = (
            checked(base + 3 * layer_bytes, layer_bytes)
            if include_world and flags & AVATAR_VALID
            else None
        )
        avatar_depth = (
            checked(base + 4 * layer_bytes, layer_bytes)
            if include_world and flags & AVATAR_VALID
            else None
        )
        final_seq = struct.unpack("<Q", checked(descriptor_offset, 8))[0]
        final_header = parse_header(checked(0, _HEADER.size))
        if final_seq != sequence or final_seq & 1 or final_header != header:
            continue
        return Frame(
            header.published,
            header.publisher_pid,
            header.latest_slot,
            sequence,
            minecraft_frame,
            host_frame,
            width,
            height,
            near,
            far,
            fov,
            flags,
            (cx, cy, cz),
            (yaw, pitch, roll),
            bool(first_person),
            capture_ns,
            publish_ns,
            _rows_top_down(overlay, width, height, flags),
            _rows_top_down(world, width, height, flags) if world is not None else None,
            _rows_top_down(depth, width, height, flags) if depth is not None else None,
            scene,
            (
                _rows_top_down(avatar, width, height, flags)
                if avatar is not None
                else None
            ),
            (
                _rows_top_down(avatar_depth, width, height, flags)
                if avatar_depth is not None
                else None
            ),
        )
    return None


class Freshness:
    """Local observation time, never cross-process comparison of Java nanoTime."""

    def __init__(self, clock=time.monotonic):
        self.clock = clock
        self.identity = None
        self.changed = 0.0

    def accept(self, frame):
        if frame is None:
            return None
        now = self.clock()
        if frame.identity != self.identity:
            self.identity = frame.identity
            self.changed = now
        return frame if now - self.changed < FRESHNESS_SECONDS else None


class SharedFrames:
    """Open an EXISTING Windows mapping read-only; never create a fake publisher."""

    def __init__(self, name=NAME):
        if os.name != "nt":
            raise OSError("named frame mappings require Windows")
        from ctypes import wintypes

        self._kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self._kernel.OpenFileMappingW.argtypes = [
            wintypes.DWORD,
            wintypes.BOOL,
            wintypes.LPCWSTR,
        ]
        self._kernel.OpenFileMappingW.restype = wintypes.HANDLE
        self._kernel.MapViewOfFile.argtypes = [
            wintypes.HANDLE,
            wintypes.DWORD,
            wintypes.DWORD,
            wintypes.DWORD,
            ctypes.c_size_t,
        ]
        self._kernel.MapViewOfFile.restype = ctypes.c_void_p
        self._kernel.UnmapViewOfFile.argtypes = [ctypes.c_void_p]
        self._kernel.UnmapViewOfFile.restype = wintypes.BOOL
        self._kernel.CloseHandle.argtypes = [wintypes.HANDLE]
        self._kernel.CloseHandle.restype = wintypes.BOOL
        self._view = None
        self._handle = self._kernel.OpenFileMappingW(4, False, name)
        self._size = 0
        self._freshness = Freshness()
        if not self._handle:
            raise FileNotFoundError("Minecraft frame mapping is not available")
        try:
            self._view = self._kernel.MapViewOfFile(self._handle, 4, 0, 0, HEADER_BYTES)
            self._size = HEADER_BYTES
            if not self._view:
                raise OSError("cannot map Minecraft frame header")
            header = parse_header(self._read(0, _HEADER.size))
            self._kernel.UnmapViewOfFile(self._view)
            self._view = None
            self._view = self._kernel.MapViewOfFile(
                self._handle, 4, 0, 0, header.mapping_bytes
            )
            self._size = header.mapping_bytes
            if not self._view:
                raise OSError("cannot map Minecraft frame data")
        except BaseException:
            self.close()
            raise

    def _read(self, offset, count):
        if not self._view or offset < 0 or count < 0 or offset + count > self._size:
            raise FrameError("read outside mapping")
        return ctypes.string_at(self._view + offset, count)

    def latest(self, include_world=False):
        return self._freshness.accept(
            read_latest(self._read, self._size, include_world)
        )

    def close(self):
        if self._view:
            self._kernel.UnmapViewOfFile(self._view)
            self._view = None
        if self._handle:
            self._kernel.CloseHandle(self._handle)
            self._handle = None

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


def write_overlay_png(frame, path):
    """Export source pixels with alpha converted to PNG's straight-alpha convention."""
    pixels = bytearray(frame.overlay_rgba)
    if len(pixels) != frame.width * frame.height * 4:
        raise FrameError("incorrect overlay byte count")
    for index in range(0, len(pixels), 4):
        alpha = pixels[index + 3]
        for channel in range(3):
            pixels[index + channel] = (
                min(255, (pixels[index + channel] * 255 + alpha // 2) // alpha)
                if alpha
                else 0
            )
    row = frame.width * 4
    scanlines = b"".join(
        b"\0" + pixels[y * row : (y + 1) * row] for y in range(frame.height)
    )

    def chunk(kind, data):
        return (
            struct.pack(">I", len(data))
            + kind
            + data
            + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)
        )

    png = b"\x89PNG\r\n\x1a\n" + chunk(
        b"IHDR", struct.pack(">IIBBBBB", frame.width, frame.height, 8, 6, 0, 0, 0)
    )
    png += chunk(b"IDAT", zlib.compress(scanlines)) + chunk(b"IEND", b"")
    Path(path).write_bytes(png)


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Capture two advancing Minecraft shared frames and export the actual overlay pixels."
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--timeout", type=float, default=10.0)
    args = parser.parse_args(argv)
    if not math.isfinite(args.timeout) or not 0 < args.timeout <= 60:
        parser.error("timeout must be within 0..60 seconds")
    deadline = time.monotonic() + args.timeout
    reader = None
    first = None
    try:
        while time.monotonic() < deadline:
            try:
                if reader is None:
                    reader = SharedFrames()
                frame = reader.latest()
                if frame is not None:
                    if first is None or first[0] != frame.publisher_pid:
                        first = frame.identity
                    elif frame.identity != first:
                        args.output.parent.mkdir(parents=True, exist_ok=True)
                        write_overlay_png(frame, args.output)
                        metadata = frame.metadata()
                        metadata.update(
                            {
                                "capture_kind": "published_shared_frame",
                                "observed_advancing_frames": 2,
                                "mapping": NAME,
                                "output_alpha": "straight",
                                "source_alpha": "premultiplied",
                                "authentic_game_render_requires_source_and_visual_verification": True,
                            }
                        )
                        args.output.with_suffix(".json").write_text(
                            json.dumps(metadata, indent=2) + "\n", encoding="utf-8"
                        )
                        print(
                            f"Captured advancing publisher {frame.publisher_pid}, frame {frame.minecraft_frame}: {args.output}"
                        )
                        return 0
            except (OSError, FrameError):
                if reader is not None:
                    reader.close()
                reader = None
                first = None
            time.sleep(0.025)
    finally:
        if reader is not None:
            reader.close()
    parser.error("no valid advancing frame exporter observed before the deadline")


if __name__ == "__main__":
    raise SystemExit(main())
