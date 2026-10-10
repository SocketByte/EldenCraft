# D3D12 compositor

The ReShade 6.8.0 add-on combines Minecraft world, hand/HUD and avatar rendering
with Elden Ring. Shared GPU textures are the default; validated shared-memory
frames provide a fallback. Host depth controls occlusion, and baked Minecraft
block meshes can be drawn directly in the host renderer.

## Build

From the repository root:

```powershell
.\scripts\eldencraft.ps1 setup-passthrough
```

The script verifies the official ReShade runtime, pinned SDK and ImGui headers,
builds the add-on with the static C runtime, and runs CTest. It downloads ReShade's
installer as an archive and extracts the runtime without executing the installer.

Outputs are `.local/compositor-build/EldenCraftCompositor.addon64`,
`EldenCraftPassthrough.fx` and `frame_inspect.exe`. The shader is self-contained.

## Runtime

me3 loads ReShade as `ReShade64.asi` from EldenCraft's runtime directory.
`RESHADE_BASE_PATH_OVERRIDE` locates its settings, add-on and shader.
Rendering requires the native loader's active-publication gate. Invalid,
inactive or stale publications hide the corresponding content.

Shared textures require compatible OpenGL extensions and the same GPU adapter for
both games. `EldenCraft.cmd -CpuFrames` selects CPU frame transport. Initial color
handling supports SDR RGBA8; HDR conversion is not implemented.

A busy shared-texture ring skips a capture until a set is available, avoiding a
CPU readback during GPU congestion. First-person capture allocates three full-size
planes per set; F5 adds the two avatar planes and retains them until a resize.
This reduces shared texture memory without changing capture resolution or effects.
While composition is paused by a menu, focus change or F11, the host consumes and
discards completed publications without copying pixels. Acknowledgements wait for
outstanding host copies and never include unpublished producer writes.

## Native blocks

Placed blocks, fluids and animated sprites are drawn from the baked mesh with
the host camera, so they stay locked to Elden Ring instead of following the
lagging RGB-D capture. The atlas carries its full mip chain and is sampled like
vanilla (nearest texels, linear between levels). Animated sprite frames arrive
each game tick and are copied into the resident atlas. Translucent faces are
re-sorted only when the camera turns or the mesh changes. Translation preserves
their depth order. Indices stay resident between sorts, and a busy upload ring
keeps drawing with the last order.

Cached geometry resides in GPU-local buffers. Its raw color and packed light UVs
use a 28-byte vertex format; the legacy prelit 24-byte format remains supported.
The 16x16 lightmap arrives independently, so lighting changes update a small
texture instead of rebuilding and retransmitting geometry. Vertex lighting keeps
the original bilinear sampling and byte rounding before interpolation.

When native blocks leave a sparse RGB-D capture, GPU-computed occupied depth
bounds reject empty tiles and rays before reprojection. Surviving rays retain the
original probes and refinement, including camera-plane crossings and edge pixels.

Each Present uses the host camera of the image being presented. Elden Ring
submits the next frame's camera before Present, so blocks are drawn with the
previous submission (**Block camera latency**, default 1 frame) plus any extra
lead the add-on measures from the native submission history. **Match blocks to
the presented frame** disables the measured part. The `EldenCraft scene` log line
every five seconds reports the offsets it chose (`cameraLead0/1/2/3+`, newest =
0) and the submission rate.

## Inspecting frames

`frame_inspect.exe --metadata 10` observes frame timing for ten seconds without
copying pixels. `frame_inspect.exe 10` additionally checks frame planes. Publication
timing is distinct from display FPS and input-to-display latency.

CTest covers frame/scene/block metadata, residency, GPU handoffs, shared resource
aliases, gated frame recycling, D3D12 descriptor copies and scene-mask dispatch,
transparency sorting, lighting equivalence, sparse depth bounds, shaders,
projection, Nether layout and shared-memory interop. The composition math and
ownership rules are implemented in [scene_protocol.hpp](include/scene_protocol.hpp)
and the [native camera publisher](../native/src/scene_camera.rs).

Upstream APIs: [ReShade reference](https://github.com/crosire/reshade/blob/v6.8.0/REFERENCE.md)
and [ReShade loader](https://github.com/crosire/reshade/blob/v6.8.0/source/dll_main.cpp).
