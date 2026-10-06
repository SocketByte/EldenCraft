"""Read a coherent bridge render packet for geometry diagnostics; no game input/memory writes."""

from __future__ import annotations
import argparse
from dataclasses import replace
import json
import math
from pathlib import Path
import sys
import time
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from passthrough.frames import SharedFrames, FrameError, write_overlay_png


def capture(output: Path, timeout: float = 10) -> dict:
    if not math.isfinite(timeout) or not 0 < timeout <= 60:
        raise ValueError("timeout must be within 0..60 seconds")
    with SharedFrames() as reader:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            # Validate the declared three/five-plane mapping and the sequence
            # around ALL requested planes through the shared checked parser.
            try:
                frame = reader.latest(include_world=True)
            except FrameError:
                time.sleep(0.01)
                continue
            if frame is None or frame.scene is None:
                time.sleep(0.01)
                continue
            scene = frame.scene
            color = np.frombuffer(frame.world_rgba, dtype=np.uint8).reshape(
                frame.height, frame.width, 4
            )
            depth = np.frombuffer(frame.world_depth, dtype="<f4").reshape(
                frame.height, frame.width
            )
            matrix = np.array(scene.world_to_clip).reshape(4, 4)
            inverse = np.array(scene.clip_to_world).reshape(4, 4)
            arrays = {
                "color": color,
                "depth": depth,
                "projection": matrix,
                "inverse": inverse,
            }
            metadata = frame.metadata()
            empty_depth = depth <= 0 if frame.flags & 4 else depth >= 1
            metadata.update(
                {
                    "frame": frame.minecraft_frame,
                    "projection": matrix.tolist(),
                    "inverse": inverse.tolist(),
                    "visible_pixels": int((color[:, :, 3] > 0).sum()),
                    "excluded_mesh_revision": scene.mesh_revision,
                    "excluded_atlas_revision": scene.atlas_revision,
                    "mesh_session": scene.mesh_session,
                    "visible_without_depth": int(
                        ((color[:, :, 3] > 0) & empty_depth).sum()
                    ),
                    "source_alpha": "premultiplied",
                    "png_alpha": "straight",
                    "depth_units": "raw window depth",
                    "capture_kind": "published_shared_frame",
                    "proves_host_composition": False,
                }
            )
            output.mkdir(parents=True, exist_ok=True)
            write_overlay_png(
                replace(frame, overlay_rgba=frame.world_rgba), output / "scene.png"
            )
            write_overlay_png(frame, output / "overlay.png")
            if frame.avatar_rgba is not None:
                avatar = np.frombuffer(frame.avatar_rgba, dtype=np.uint8).reshape(
                    frame.height, frame.width, 4
                )
                avatar_depth = np.frombuffer(frame.avatar_depth, dtype="<f4").reshape(
                    frame.height, frame.width
                )
                arrays.update(
                    {
                        "avatar": avatar,
                        "avatar_depth": avatar_depth,
                        "avatar_inverse": np.array(scene.avatar_inverse).reshape(4, 4),
                    }
                )
                metadata["avatar_visible_pixels"] = int((avatar[:, :, 3] > 0).sum())
                write_overlay_png(
                    replace(frame, overlay_rgba=frame.avatar_rgba),
                    output / "avatar.png",
                )
            np.savez_compressed(output / "packet.npz", **arrays)
            (output / "metadata.json").write_text(
                json.dumps(metadata, indent=2), encoding="utf-8"
            )
            return metadata
        raise RuntimeError("no coherent shared scene before deadline")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(json.dumps(capture(args.output)))
