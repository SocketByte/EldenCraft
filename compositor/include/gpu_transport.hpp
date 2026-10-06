#pragma once
// ECGT v1: zero-copy Minecraft frame transport.
//
// The host (this add-on, inside Elden Ring) owns three sets of five shared D3D12
// textures and one shared fence. Minecraft imports them through
// GL_EXT_memory_object_win32 / GL_EXT_semaphore_win32, renders its planes into a
// set, signals the fence with its frame number and publishes an ordinary MCPT
// slot descriptor with flag 64 (gpu_shared_flag). The host copies the set into its
// private textures once the fence reached that frame and then acknowledges it.
//
// Control mapping `Local\EldenCraftGpuFrame`, 4096 bytes, opened-or-created by
// either side, little-endian:
//   host block     0 u32 magic ECGT (written last)   4 u32 version   8 u32 host pid
//                 12 u32 generation (never 0)       16 u32 width    20 u32 height
//                 24 u32 sets (3)                   28 u32 planes (5)
//                 32 + 8*plane u64 allocation bytes of each plane texture
//                 96 u32 preferred width   100 u32 preferred height  (the host back buffer,
//                    refreshed every frame even before textures exist; 0 = unknown)
//   guest request 128 u32 magic ECGQ   132 u32 width   136 u32 height   140 u32 guest pid
//                 144 u32 status (1 imported, 2 failed)   148 u32 generation the status refers to
//   host acks     256 + 8*set u64: newest Minecraft frame the host finished copying from that set
// Texture names: Local\EldenCraftGpu_<hostpid>_<generation>_<set>_<plane>
// Fence name:    Local\EldenCraftGpu_<hostpid>_<generation>_ready
// Plane formats match MCPT: 0 world RGBA8, 1 world depth R32F, 2 overlay RGBA8,
// 3 avatar RGBA8, 4 avatar depth R32F. Rows keep Minecraft's bottom-up order.
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <span>
#include <string>

namespace eldencraft::gpu {
inline constexpr wchar_t mapping_name[] = L"Local\\EldenCraftGpuFrame";
inline constexpr std::size_t mapping_bytes = 4096;
inline constexpr std::uint32_t magic = 0x54474345, request_magic = 0x51474345, version = 1;
inline constexpr std::uint32_t sets = 3, planes = 5;
// Shared textures carry no CPU copy, so they may exceed the 1920x1080 MCPT pixel planes.
inline constexpr std::uint32_t max_width = 3840, max_height = 2160;
inline constexpr std::size_t preferred_offset = 96;
inline constexpr std::size_t host_offset = 0, plane_bytes_offset = 32, request_offset = 128, ack_offset = 256;
inline constexpr std::uint32_t status_imported = 1, status_failed = 2;

inline bool depth_plane(std::uint32_t plane) { return plane == 1 || plane == 4; }

inline std::wstring texture_name(std::uint32_t pid, std::uint32_t generation, std::uint32_t set, std::uint32_t plane) {
    wchar_t name[96]{};
    std::swprintf(name, 96, L"Local\\EldenCraftGpu_%u_%u_%u_%u", pid, generation, set, plane);
    return name;
}
inline std::wstring fence_name(std::uint32_t pid, std::uint32_t generation) {
    wchar_t name[96]{};
    std::swprintf(name, 96, L"Local\\EldenCraftGpu_%u_%u_ready", pid, generation);
    return name;
}

template<class T> T get(std::span<const std::uint8_t> bytes, std::size_t offset) {
    T value{};
    if (offset <= bytes.size() && sizeof(T) <= bytes.size() - offset) std::memcpy(&value, bytes.data() + offset, sizeof(T));
    return value;
}

struct Request { std::uint32_t width{}, height{}, pid{}, status{}, status_generation{}; };
// A guest asks for textures matching its render target. Odd sizes are allowed;
// anything the CPU MCPT path could not carry is refused here as well.
inline bool decode_request(std::span<const std::uint8_t> bytes, Request &out) {
    if (bytes.size() < mapping_bytes || get<std::uint32_t>(bytes, request_offset) != request_magic) return false;
    out = {get<std::uint32_t>(bytes, request_offset + 4), get<std::uint32_t>(bytes, request_offset + 8),
           get<std::uint32_t>(bytes, request_offset + 12), get<std::uint32_t>(bytes, request_offset + 16),
           get<std::uint32_t>(bytes, request_offset + 20)};
    return out.pid != 0 && out.width >= 1 && out.height >= 1 && out.width <= max_width && out.height <= max_height;
}

// The guest may reuse a set once the host acknowledged that set's frame or any
// newer one: the host only ever copies the newest publication, in frame order.
inline bool set_reusable(std::uint64_t written_frame, std::uint64_t newest_ack) {
    return written_frame == 0 || newest_ack >= written_frame;
}
} // namespace eldencraft::gpu
