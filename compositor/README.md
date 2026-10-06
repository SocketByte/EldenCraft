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
