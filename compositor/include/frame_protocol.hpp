#pragma once
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <span>
#include <vector>

namespace eldencraft::frames {
inline constexpr wchar_t mapping_name[] = L"Local\\EldenCraftFrame";
inline constexpr std::uint32_t magic = 0x5450434D;
inline constexpr std::size_t header_bytes = 4096, descriptor_offset = 256, descriptor_bytes = 128;
inline constexpr std::uint32_t version = 1, slots = 3, max_width = 1920, max_height = 1080;
inline constexpr std::uint64_t slot_stride = std::uint64_t(max_width) * max_height * 4 * 3;
inline constexpr std::uint64_t avatar_slot_stride = std::uint64_t(max_width) * max_height * 4 * 5;
inline constexpr std::size_t mapping_bytes = header_bytes + slot_stride * slots;

template<class T> T read(std::span<const std::uint8_t> bytes, std::size_t offset) {
    T value{};
    if (offset <= bytes.size() && sizeof(T) <= bytes.size() - offset)
        std::memcpy(&value, bytes.data() + offset, sizeof(T));
    return value;
}
struct Header { std::uint64_t publication{}; std::int32_t latest{-1}; std::uint32_t pid{}; std::uint64_t stride{}; };
inline bool decode_header(std::span<const std::uint8_t> bytes, Header &out) {
    if (bytes.size() < header_bytes || read<std::uint32_t>(bytes, 0) != magic
        || read<std::uint32_t>(bytes, 4) != version
        || read<std::uint32_t>(bytes, 8) != header_bytes
        || read<std::uint32_t>(bytes, 12) != slots
        || (read<std::uint64_t>(bytes, 16) != slot_stride&&read<std::uint64_t>(bytes,16)!=avatar_slot_stride)
        || read<std::uint32_t>(bytes, 24) != max_width
        || read<std::uint32_t>(bytes, 28) != max_height) return false;
    out = {read<std::uint64_t>(bytes, 32), read<std::int32_t>(bytes, 40), read<std::uint32_t>(bytes, 44),read<std::uint64_t>(bytes,16)};
    return out.pid != 0 && out.latest >= -1 && out.latest < static_cast<std::int32_t>(slots);
}
// Flag 64: planes are in host-owned shared GPU textures (see gpu_transport.hpp);
// the mapping slot carries metadata only. +112 generation, +116 texture set.
inline constexpr std::uint32_t gpu_shared_flag = 64;
// GPU-shared frames may use the full host resolution (gpu_transport.hpp limits).
inline constexpr std::uint32_t gpu_max_width = 3840, gpu_max_height = 2160;
struct Descriptor {
    std::uint64_t sequence{}, minecraft_frame{}, host_frame{}, capture_ns{}, publish_ns{};
    std::uint32_t width{}, height{}, flags{};
    std::uint32_t gpu_generation{}, gpu_set{};
    float near_plane{}, far_plane{}, vertical_fov{};
    std::size_t layer_bytes{};
};
inline bool payload_fits(const Descriptor &descriptor,std::uint64_t available) {
    const std::uint64_t planes=(descriptor.flags&32u)?5:3;
    return descriptor.layer_bytes!=0 && descriptor.layer_bytes<=available/planes;
}
inline bool decode_descriptor(std::span<const std::uint8_t> bytes, Descriptor &out,std::uint64_t stride=slot_stride) {
    if (bytes.size() < descriptor_bytes) return false;
    out.sequence = read<std::uint64_t>(bytes, 0);
    out.minecraft_frame = read<std::uint64_t>(bytes, 8);
    out.host_frame = read<std::uint64_t>(bytes, 16);
    out.width = read<std::uint32_t>(bytes, 24); out.height = read<std::uint32_t>(bytes, 28);
    out.near_plane = read<float>(bytes, 32); out.far_plane = read<float>(bytes, 36);
    out.vertical_fov = read<float>(bytes, 40); out.flags = read<std::uint32_t>(bytes, 44);
    out.capture_ns = read<std::uint64_t>(bytes, 88); out.publish_ns = read<std::uint64_t>(bytes, 96);
    out.gpu_generation = read<std::uint32_t>(bytes, 112); out.gpu_set = read<std::uint32_t>(bytes, 116);
    if (out.flags & gpu_shared_flag) {
        if (out.gpu_generation == 0 || out.gpu_set >= slots || out.minecraft_frame == 0) return false;
    } else if (out.gpu_generation != 0 || out.gpu_set != 0) return false;
    const bool gpu = (out.flags & gpu_shared_flag) != 0;
    if (out.sequence == 0 || (out.sequence & 1) || out.width == 0 || out.height == 0
        || out.width > (gpu ? gpu_max_width : max_width) || out.height > (gpu ? gpu_max_height : max_height)
        || (out.flags & ~127u) || ((out.flags&24u)==24u)
        || (stride!=slot_stride&&stride!=avatar_slot_stride)
        || ((out.flags&32u)&&(!(out.flags&16u)||stride!=avatar_slot_stride))) return false;
    out.layer_bytes = std::size_t(out.width) * out.height * 4;
    return gpu || payload_fits(out,stride); // GPU frames carry no CPU planes.
}
struct Frame { Descriptor descriptor; std::uint64_t publication{}; std::vector<std::uint8_t> overlay; };
// Freshness is measured on this process's monotonic clock. Repeated publications
// cannot renew a frame, and explicit producer invalidation hides it immediately.
class Freshness {
    std::uint64_t publication_{}, received_{};
    bool valid_{};
public:
    void invalidate() { valid_=false; }
    void reset() { publication_=received_=0; valid_=false; }
    bool observe(std::uint64_t publication, std::uint64_t now) {
        if(publication==0 || publication<=publication_) return false;
        publication_=publication; received_=now; valid_=true; return true;
    }
    std::uint64_t publication() const { return publication_; }
    bool fresh(std::uint64_t now) const { return valid_ && now>=received_ && now-received_<2000; }
};
} // namespace eldencraft::frames
