# Third-party notices

EldenCraft's license is in [LICENSE](LICENSE). Binary distributions include a
`licenses/` directory with the notices supplied by their Rust dependencies,
the Rust standard library, ReShade SDK and ImGui.

| Component | Use | Upstream |
| --- | --- | --- |
| Fabric Loader / Fabric API | Minecraft mod loading and events | [Fabric](https://fabricmc.net/) |
| fromsoftware-rs | Pinned Elden Ring SDK | [vswarte/fromsoftware-rs](https://github.com/vswarte/fromsoftware-rs) |
| ReShade SDK | Graphics add-on interface | [crosire/reshade](https://github.com/crosire/reshade) |
| ImGui headers | ReShade UI ABI definitions | [ocornut/imgui](https://github.com/ocornut/imgui) |
| Rust dependencies | Serialization, hashing, hooks and Windows interop | Versions and sources in `native/Cargo.lock` |

The Windows launcher downloads these unmodified components from their official
distribution endpoints. They retain their own licenses and notices:

- [Prism Launcher](https://prismlauncher.org/): Minecraft installation and Microsoft sign-in.
- [Eclipse Temurin](https://adoptium.net/): portable Java runtime.
- [me3](https://github.com/garyttierney/me3): offline game launcher.
- [ReShade](https://reshade.me/): graphics runtime.
- [Fabric API](https://fabricmc.net/): Minecraft event API.

Minecraft and Elden Ring are separately owned products. EldenCraft does not
distribute their executables, assets, worlds, account data or decompiled source.
