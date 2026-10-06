"""Synthetic ABI tests only; these do not establish real Minecraft rendering."""

from dataclasses import replace
import math
import mmap
import os
from pathlib import Path
import struct
import sys
import tempfile
import unittest
import uuid
import zlib

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from passthrough import frames as f


class Fixture:
    def __init__(self, flags=0, stride=f.STRIDE, slot=0):
        self.stride, self.slot = stride, slot
        self.mapping_bytes = f.HEADER_BYTES + f.SLOTS * stride
        self.header = bytearray(f.HEADER_BYTES)
        f._HEADER.pack_into(
            self.header,
            0,
            f.MAGIC,
            f.VERSION,
            f.HEADER_BYTES,
            f.SLOTS,
            stride,
            f.MAX_WIDTH,
            f.MAX_HEIGHT,
            1,
            slot,
            os.getpid(),
        )
        f._DESC.pack_into(
            self.header,
            f.SLOT_DESC + slot * f.SLOT_DESC_BYTES,
            2,
            1,
            7,
            2,
            2,
            0.05,
            1024.0,
            70.0,
            flags,
            1.0,
            2.0,
            3.0,
            0.0,
            0.0,
            0.0,
            1,
            100,
            110,
        )
        self.overlay = bytes(
            [255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 64, 32, 16, 128]
        )
        self.depth = struct.pack("<ffff", 1.0, 0.5, 0.25, 0.0)
        self.payload = b"\x33" * 16 + self.depth + self.overlay
        self.avatar = bytes([32, 16, 8, 64]) * 2 + bytes([0, 0, 0, 0]) * 2
        self.avatar_depth = struct.pack("<4f", 0.2, 0.4, 0.6, 1.0)
        if flags & f.AVATAR_VALID:
            self.payload += self.avatar + self.avatar_depth
        if flags & f.SCENE_VALID:
            offset = f.SCENE_OFFSET + slot * f.SCENE_BYTES
            struct.pack_into(
                "<IIQQII3d",
                self.header,
                offset,
                0x53464345,
                1,
                5,
                7,
                11,
                os.getpid(),
                1,
                2,
                3,
            )
            identity = tuple(1.0 if i // 4 == i % 4 else 0.0 for i in range(16))
            struct.pack_into("<16f", self.header, offset + 64, *identity)
            struct.pack_into("<16f", self.header, offset + 128, *identity)
            if flags & f.AVATAR_VALID:
                perspective_inverse = (
                    1.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    -1.0,
                    0.0,
                    0.0,
                    -9.99,
                    10.0,
                )
                struct.pack_into(
                    "<16fI", self.header, offset + 216, *perspective_inverse, 1
                )
        self.reads = []

    def read(self, offset, count):
        self.reads.append((offset, count))
        if offset < f.HEADER_BYTES:
            return self.header[offset : offset + count]
        offset -= f.HEADER_BYTES + self.slot * self.stride
        if offset < 0:
            return b""
        return self.payload[offset : offset + count]


class FrameTests(unittest.TestCase):
    def test_five_plane_last_slot_uses_declared_stride_and_copies_all_pixels(self):
        source = Fixture(f.SCENE_VALID | f.AVATAR_VALID, f.AVATAR_STRIDE, slot=2)
        self.assertEqual(f.AVATAR_STRIDE, 41_472_000)
        self.assertEqual(f.AVATAR_MAPPING_BYTES, 124_420_096)
        frame = f.read_latest(source.read, source.mapping_bytes, include_world=True)
        self.assertEqual(frame.slot, 2)
        self.assertEqual(frame.world_rgba, b"\x33" * 16)
        self.assertEqual(frame.overlay_rgba, source.overlay)
        self.assertEqual(frame.avatar_rgba, source.avatar)
        self.assertEqual(frame.avatar_depth, source.avatar_depth)
        self.assertEqual(frame.scene.avatar_mode, 1)
        self.assertTrue(frame.metadata()["avatar_available"])
        self.assertNotIn("avatar_rgba", frame.metadata())
        self.assertNotIn("avatar_depth", frame.metadata())
        source.payload = b"\0" * 80
        self.assertNotEqual(frame.avatar_rgba, source.payload[48:64])

    def test_bottom_up_avatar_and_raw_depth_match_world_row_orientation(self):
        source = Fixture(f.SCENE_VALID | f.AVATAR_VALID | f.BOTTOM_UP, f.AVATAR_STRIDE)
        frame = f.read_latest(source.read, include_world=True)
        self.assertEqual(frame.avatar_rgba, source.avatar[8:] + source.avatar[:8])
        self.assertEqual(
            frame.avatar_depth, source.avatar_depth[8:] + source.avatar_depth[:8]
        )
        overlay_only = f.read_latest(source.read)
        self.assertIsNone(overlay_only.avatar_rgba)
        self.assertIsNone(overlay_only.avatar_depth)
        self.assertTrue(overlay_only.metadata()["avatar_available"])

    def test_avatar_flags_stride_and_projection_must_agree_before_pixels(self):
        for flags, stride in [
            (32, f.AVATAR_STRIDE),
            (48, f.STRIDE),
            (56, f.AVATAR_STRIDE),
            (64, f.AVATAR_STRIDE),
        ]:
            source = Fixture(flags, stride)
            with (
                self.subTest(flags=flags, stride=stride),
                self.assertRaises(f.FrameError),
            ):
                f.read_latest(source.read, include_world=True)
            self.assertTrue(all(start < f.HEADER_BYTES for start, _ in source.reads))
        source = Fixture(48, f.AVATAR_STRIDE)
        struct.pack_into("<I", source.header, f.SLOT_DESC + 44, 16)
        with self.assertRaises(f.FrameError):
            f.read_latest(source.read)
        with self.assertRaises(f.FrameError):
            f.read_latest(Fixture(48, f.AVATAR_STRIDE).read, f.MAPPING_BYTES)
        # Five-plane capacity alone never makes unwritten avatar planes valid.
        source = Fixture(16, f.AVATAR_STRIDE)
        frame = f.read_latest(source.read, include_world=True)
        self.assertIsNone(frame.avatar_rgba)
        self.assertTrue(all(start < f.HEADER_BYTES + 48 for start, _ in source.reads))

    def test_avatar_projection_and_remaining_reserved_space_are_strict(self):
        source = Fixture(48, f.AVATAR_STRIDE)
        offset = f.SCENE_OFFSET
        for position, encoding, value in [
            (280, "<I", 3),
            (280, "<I", 0),
            (284, "<I", 1),
            (216, "<f", math.nan),
            (216, "<f", 1e7),
            (216, "<f", 0.0),
            (220, "<f", 0.5),
            (260, "<f", 1.0),
            (260, "<f", 0.0),
            (272, "<f", 0.0),
            (276, "<f", 4.995),
        ]:
            bad = bytearray(source.header)
            struct.pack_into(encoding, bad, offset + position, value)
            with (
                self.subTest(position=position, value=value),
                self.assertRaises(f.FrameError),
            ):
                f.parse_scene(bad[offset : offset + f.SCENE_BYTES])
        for mode in (1, 2):
            struct.pack_into("<I", source.header, offset + 280, mode)
            self.assertEqual(f.read_latest(source.read).scene.avatar_mode, mode)

    def test_mesh_identity_extension_is_all_zero_or_all_positive(self):
        source = Fixture(16)
        struct.pack_into("<3Q", source.header, f.SCENE_OFFSET + 192, 17, 23, 31)
        scene = f.read_latest(source.read).scene
        self.assertEqual(
            (scene.mesh_revision, scene.atlas_revision, scene.mesh_session),
            (17, 23, 31),
        )
        for missing in range(3):
            bad = bytearray(source.header)
            struct.pack_into("<Q", bad, f.SCENE_OFFSET + 192 + missing * 8, 0)
            with self.assertRaises(f.FrameError):
                f.parse_scene(bad[f.SCENE_OFFSET : f.SCENE_OFFSET + f.SCENE_BYTES])

    def test_missing_fifth_plane_and_sequence_race_never_return_avatar(self):
        source = Fixture(48, f.AVATAR_STRIDE)
        source.payload = source.payload[:-1]
        with self.assertRaises(f.FrameError):
            f.read_latest(source.read, include_world=True)
        source = Fixture(48, f.AVATAR_STRIDE)

        def racing_depth(offset, count):
            data = source.read(offset, count)
            if offset == f.HEADER_BYTES + 64:
                sequence = struct.unpack_from("<Q", source.header, f.SLOT_DESC)[0]
                struct.pack_into("<Q", source.header, f.SLOT_DESC, sequence + 2)
            return data

        self.assertIsNone(f.read_latest(racing_depth, include_world=True))

    def test_publication_change_after_plane_copy_is_rechecked(self):
        source = Fixture(48, f.AVATAR_STRIDE)

        def advancing_publication(offset, count):
            data = source.read(offset, count)
            if offset >= f.HEADER_BYTES:
                publication = struct.unpack_from("<Q", source.header, 32)[0]
                struct.pack_into("<Q", source.header, 32, publication + 1)
            return data

        self.assertIsNone(f.read_latest(advancing_publication, include_world=True))

    def test_exact_layout_and_immutable_layer_copy(self):
        source = Fixture()
        self.assertEqual(f.STRIDE, 24_883_200)
        self.assertEqual(f.MAPPING_BYTES, 74_653_696)
        frame = f.read_latest(source.read, include_world=True)
        self.assertEqual(frame.overlay_rgba, source.overlay)
        self.assertEqual(frame.world_rgba, b"\x33" * 16)
        self.assertEqual(frame.world_depth, source.depth)
        self.assertEqual((frame.host_frame, frame.width, frame.height), (7, 2, 2))
        source.payload = b"\0" * 48
        self.assertNotEqual(frame.overlay_rgba, source.payload[32:])
        self.assertEqual(frame.camera, (1.0, 2.0, 3.0))

    def test_bottom_up_is_normalized_for_all_three_layers(self):
        source = Fixture(flags=7)
        frame = f.read_latest(source.read, include_world=True)
        self.assertEqual(frame.overlay_rgba, source.overlay[8:] + source.overlay[:8])
        self.assertEqual(frame.world_depth, source.depth[8:] + source.depth[:8])
        self.assertAlmostEqual(frame.depth_metres(0, 1), frame.near)
        self.assertEqual(frame.depth_metres(1, 0), math.inf)
        with self.assertRaises(f.FrameError):
            frame.depth_metres(2, 0)
        self.assertEqual(replace(frame, flags=0).depth_metres(0, 1), math.inf)

    def test_header_schema_and_mapping_size_reject_before_payload_read(self):
        for offset, encoding, value in [
            (0, "<I", 0),
            (4, "<I", 2),
            (8, "<I", 128),
            (12, "<I", 4),
            (16, "<Q", 2**63),
            (24, "<I", 3840),
            (28, "<I", 2160),
            (40, "<i", -2),
            (40, "<i", 3),
            (44, "<I", 0),
        ]:
            source = Fixture()
            struct.pack_into(encoding, source.header, offset, value)
            with self.subTest(offset=offset), self.assertRaises(f.FrameError):
                f.read_latest(source.read)
            self.assertTrue(all(start < f.HEADER_BYTES for start, _ in source.reads))
        with self.assertRaises(f.FrameError):
            f.read_latest(Fixture().read, mapped_bytes=f.MAPPING_BYTES - 1)
        with self.assertRaises(f.FrameError):
            f.parse_header(b"\0" * 47)

    def test_dimensions_and_nonfinite_metadata_reject_before_pixels(self):
        for offset, encoding, value in [
            (24, "<I", 0),
            (24, "<I", 1921),
            (28, "<I", 1081),
            (32, "<f", 0),
            (36, "<f", -1),
            (40, "<f", 180),
            (40, "<f", float("nan")),
            (48, "<d", float("inf")),
            (44, "<I", 32),
            (84, "<I", 2),
            (8, "<Q", 0),
        ]:
            source = Fixture()
            struct.pack_into(encoding, source.header, f.SLOT_DESC + offset, value)
            with self.subTest(offset=offset), self.assertRaises(f.FrameError):
                f.read_latest(source.read)
            self.assertTrue(all(start < f.HEADER_BYTES for start, _ in source.reads))

    def test_unpublished_and_inflight_slots_are_never_exposed(self):
        for header_offset, encoding, value in [
            (32, "<Q", 0),
            (40, "<i", -1),
            (f.SLOT_DESC, "<Q", 3),
        ]:
            source = Fixture()
            struct.pack_into(encoding, source.header, header_offset, value)
            self.assertIsNone(f.read_latest(source.read))
            self.assertTrue(all(start < f.HEADER_BYTES for start, _ in source.reads))

    def test_overlay_only_never_exposes_unwritten_world_planes(self):
        source = Fixture(flags=8)
        frame = f.read_latest(source.read)
        self.assertEqual(frame.overlay_rgba, source.overlay)
        self.assertFalse(frame.metadata()["world_available"])
        self.assertIsNone(frame.world_rgba)
        self.assertIsNone(frame.world_depth)
        source.reads.clear()
        with self.assertRaisesRegex(f.FrameError, "unavailable"):
            f.read_latest(source.read, include_world=True)
        self.assertTrue(all(start < f.HEADER_BYTES for start, _ in source.reads))

    def test_scene_extension_requires_coherent_matrices_and_identity(self):
        source = Fixture(flags=16)
        offset = f.SCENE_OFFSET
        struct.pack_into(
            "<IIQQII3d",
            source.header,
            offset,
            0x53464345,
            1,
            5,
            7,
            11,
            os.getpid(),
            1,
            2,
            3,
        )
        identity = tuple(1.0 if i // 4 == i % 4 else 0.0 for i in range(16))
        struct.pack_into("<16f", source.header, offset + 64, *identity)
        struct.pack_into("<16f", source.header, offset + 128, *identity)
        frame = f.read_latest(source.read, include_world=True)
        self.assertEqual(
            (frame.scene.epoch, frame.scene.anchor, frame.scene.map), (5, 7, 11)
        )
        self.assertEqual(frame.metadata()["scene"]["camera"], (1.0, 2.0, 3.0))
        for position, encoding, value in [
            (8, "<Q", 0),
            (56, "<I", 1),
            (128, "<f", 0),
            (64, "<f", math.nan),
            (192, "<I", 1),
        ]:
            bad = bytearray(source.header)
            struct.pack_into(encoding, bad, offset + position, value)
            with self.subTest(position=position), self.assertRaises(f.FrameError):
                f.parse_scene(bad[offset : offset + f.SCENE_BYTES])
        struct.pack_into("<I", source.header, f.SLOT_DESC + 44, 24)
        with self.assertRaises(f.FrameError):
            f.read_latest(source.read)

    def test_mid_copy_overwrite_and_publisher_restart_discard_pixels(self):
        source = Fixture()

        def racing_read(offset, count):
            data = source.read(offset, count)
            if offset >= f.HEADER_BYTES:
                sequence = struct.unpack_from("<Q", source.header, f.SLOT_DESC)[0]
                struct.pack_into("<Q", source.header, f.SLOT_DESC, sequence + 2)
            return data

        self.assertIsNone(f.read_latest(racing_read, retries=100))
        self.assertEqual(sum(start >= f.HEADER_BYTES for start, _ in source.reads), 3)
        source = Fixture()

        def restart_read(offset, count):
            data = source.read(offset, count)
            if offset >= f.HEADER_BYTES:
                pid = struct.unpack_from("<I", source.header, 44)[0]
                struct.pack_into("<I", source.header, 44, pid + 1)
            return data

        self.assertIsNone(f.read_latest(restart_read))

    def test_short_reads_and_absent_depth_are_rejected(self):
        source = Fixture()

        def short_read(offset, count):
            data = source.read(offset, count)
            return data[:-1] if offset >= f.HEADER_BYTES else data

        with self.assertRaises(f.FrameError):
            f.read_latest(short_read)
        frame = f.read_latest(source.read)
        with self.assertRaises(f.FrameError):
            frame.depth_metres(0, 0)

    def test_freshness_uses_local_counter_observation_and_expires_at_two_seconds(self):
        now = [100.0]
        freshness = f.Freshness(clock=lambda: now[0])
        frame = f.read_latest(Fixture().read)
        self.assertIsNotNone(freshness.accept(frame))
        now[0] += 1.999
        self.assertIsNotNone(freshness.accept(frame))
        now[0] += 0.0011
        self.assertIsNone(freshness.accept(frame))
        self.assertIsNotNone(
            freshness.accept(replace(frame, published=2, minecraft_frame=2))
        )
        self.assertIsNone(freshness.accept(None))

    def test_png_preserves_alpha_and_unpremultiplies_for_display(self):
        frame = f.read_latest(Fixture().read)
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "overlay.png"
            f.write_overlay_png(frame, target)
            raw = target.read_bytes()
        self.assertEqual(raw[:8], b"\x89PNG\r\n\x1a\n")
        offset, compressed = 8, b""
        while offset < len(raw):
            count = struct.unpack_from(">I", raw, offset)[0]
            kind = raw[offset + 4 : offset + 8]
            data = raw[offset + 8 : offset + 8 + count]
            crc = struct.unpack_from(">I", raw, offset + 8 + count)[0]
            self.assertEqual(crc, zlib.crc32(kind + data) & 0xFFFFFFFF)
            if kind == b"IDAT":
                compressed += data
            offset += count + 12
        scanlines = zlib.decompress(compressed)
        self.assertEqual(scanlines[0], 0)
        self.assertEqual(scanlines[-4:], bytes([128, 64, 32, 128]))

    @unittest.skipUnless(os.name == "nt", "Windows mapping interoperability")
    def test_windows_mapping_reader_uses_an_existing_mapping_read_only(self):
        for source in (Fixture(), Fixture(48, f.AVATAR_STRIDE, slot=2)):
            name = "Local\\EldenCraftFrameTest_" + uuid.uuid4().hex
            with self.assertRaises(FileNotFoundError):
                f.SharedFrames(name)
            with mmap.mmap(-1, source.mapping_bytes, tagname=name) as mapping:
                mapping[: f.HEADER_BYTES] = source.header
                base = f.HEADER_BYTES + source.slot * source.stride
                mapping[base : base + len(source.payload)] = source.payload
                with f.SharedFrames(name) as reader:
                    frame = reader.latest(include_world=True)
                    self.assertEqual(frame.overlay_rgba, source.overlay)
                    self.assertEqual(frame.publisher_pid, os.getpid())
                    if frame.flags & f.AVATAR_VALID:
                        self.assertEqual(frame.avatar_rgba, source.avatar)
                        self.assertEqual(frame.avatar_depth, source.avatar_depth)
            with self.assertRaises(FileNotFoundError):
                f.SharedFrames(name)


if __name__ == "__main__":
    unittest.main()
