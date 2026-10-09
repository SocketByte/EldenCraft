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

## Native blocks

Placed blocks, fluids and animated sprites are drawn from the baked mesh with
the host camera, so they stay locked to Elden Ring instead of following the
lagging RGB-D capture. The atlas carries its full mip chain and is sampled like
vanilla (nearest texels, linear between levels). Animated sprite frames arrive
each game tick and are copied into the resident atlas. Translucent faces are
re-sorted only when the camera moves or the mesh changes.

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

CTest covers frame/scene/block metadata, residency, GPU handoffs, shaders,
projection, Nether layout and shared-memory interop. The composition math and
ownership rules are implemented in [scene_protocol.hpp](include/scene_protocol.hpp)
and the [native camera publisher](../native/src/scene_camera.rs).

Upstream APIs: [ReShade reference](https://github.com/crosire/reshade/blob/v6.8.0/REFERENCE.md)
and [ReShade loader](https://github.com/crosire/reshade/blob/v6.8.0/source/dll_main.cpp).
