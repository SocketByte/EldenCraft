#pragma once
// Host side of ECGT (see gpu_transport.hpp). Owns the shared textures and fence,
// answers the guest's size request and acknowledges copied sets.
#include "gpu_transport.hpp"
#include "frame_protocol.hpp"
#include <reshade.hpp>
#include <array>
#include <vector>

struct ID3D12Device;
struct ID3D12Resource;
struct ID3D12Fence;

namespace eldencraft::gpu {
class Host {
    void *mapping_{};
    std::uint8_t *view_{};
    ID3D12Device *device_{};
    std::array<std::array<ID3D12Resource *, planes>, sets> textures_{};
    std::array<std::array<void *, planes>, sets> handles_{};
    ID3D12Fence *ready_{};
    void *ready_handle_{};
    std::uint32_t generation_{}, width_{}, height_{}, failed_generation_{};
    std::uint64_t next_attempt_{};
    struct PendingAck { std::uint32_t set; std::uint64_t frame, fence; };
    std::vector<PendingAck> pending_;
    bool reported_{}, disabled_{};
    void release(reshade::api::effect_runtime *runtime);
    bool create(reshade::api::effect_runtime *runtime, std::uint32_t width, std::uint32_t height);
    void publish_ack(std::uint32_t set, std::uint64_t frame);
public:
    Host() = default;
    Host(const Host &) = delete;
    Host &operator=(const Host &) = delete;
    // Opens the control mapping and services a pending guest request. Never
    // waits for the GPU except when replacing textures of a different size.
    void poll(reshade::api::effect_runtime *runtime, std::uint64_t now);
    // The descriptor names this generation and its fence already reached the frame.
    bool ready(const frames::Descriptor &descriptor) const;
    reshade::api::resource plane(std::uint32_t set, std::uint32_t plane) const;
    // A copy of `set` holding `frame` was submitted before signalling `fence`.
    void note_copy(std::uint32_t set, std::uint64_t frame, std::uint64_t fence) { pending_.push_back({set, frame, fence}); }
    // Acknowledge every set whose copy completed on the GPU.
    void retire(std::uint64_t completed_fence);
    void destroy(reshade::api::effect_runtime *runtime);
    bool active() const { return generation_ != 0; }
    std::uint32_t generation() const { return generation_; }
};
} // namespace eldencraft::gpu
