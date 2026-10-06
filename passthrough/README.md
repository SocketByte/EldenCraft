# Shared frame transport

Minecraft publishes frame metadata and three color/depth layers through the MCPT
mapping. A layer represents the world, hand/HUD or avatar. The compositor reads
stable snapshots and rejects unsupported flags, dimensions, strides, slots,
identities and stale sequences.

The frame header is defined in
[frames.py](frames.py), [frame_protocol.hpp](../compositor/include/frame_protocol.hpp)
and [FrameExporter.java](../minecraft/src/client/java/dev/eldencraft/bridge/client/FrameExporter.java).
Keep these definitions and their conformance suites synchronized.

GPU transport publishes matching metadata through ECGT and uses shared textures
and fences. CPU fallback pixels remain available when GPU sharing is disabled.
Seqlocks protect snapshot consistency; a new sequence is accepted only after
identity and timing validation. No game pointers cross the process boundary.

## Tools

`python -m passthrough.frames --help` describes capture options. The command
captures an advancing live mapping through the strict decoder in `frames.py`.

Run the frame decoder tests from the repository root:

```powershell
.\scripts\eldencraft.ps1 test
```

The tests use synthetic publications and malformed inputs. Native and guest
conformance suites separately check the actual Win32 mapping layout and transport
lifecycle. End-to-end rendering requires an offline gameplay check.

Host pose/input uses ECHS, defined in
[host_pose.rs](../native/src/host_pose.rs) and
[HostState.java](../minecraft/src/client/java/dev/eldencraft/bridge/client/HostState.java).
See [PROTOCOL.md](../PROTOCOL.md) for the other transports and the
[compositor guide](../compositor/README.md) for rendering.
