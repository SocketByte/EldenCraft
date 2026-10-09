#pragma once
#include "block_protocol.hpp"
#include "scene_protocol.hpp"
#include <reshade.hpp>
#include <memory>
#include <span>

namespace eldencraft::blocks {
// D3D12 only. All methods run on the runtime's render thread. render() requires
// the queue's immediate command list, outside any render pass; it submits and
// fences its commands. The following ReShade effect must restore its bindings.
class Renderer {
public:
    explicit Renderer(bool details=false);
    ~Renderer(); // Call destroy(runtime) before device/runtime teardown.
    Renderer(const Renderer &) = delete;
    Renderer &operator=(const Renderer &) = delete;
    bool prepare(reshade::api::effect_runtime *, const MeshHeader &,
        std::span<const std::uint8_t>, const AtlasHeader &, std::span<const std::uint8_t>,
        const frames::Scene &displayed_scene);
    // `animation`: optional fresh EldenCraftBlockAnim publication; its sprite frames are
    // copied into the rendered atlas when they name that atlas revision.
    bool render(reshade::api::effect_runtime *, reshade::api::command_list *,
        const frames::HostCamera &, const frames::Scene &, const Header &current_producer,
        std::uint32_t width, std::uint32_t height,
        reshade::api::resource_view host_depth, int depth_mode, Renderer *destination=nullptr,
        const Header *animation=nullptr, std::span<const std::uint8_t> animation_payload={});
    void destroy(reshade::api::effect_runtime *);
    // Allocated in prepare(), allowing the guest-exclusion ACK to bootstrap.
    // Display these only after render() returned true for the current frame.
    reshade::api::resource_view color_view() const;
    reshade::api::resource_view depth_view() const;
    // Advances whenever the views above are destroyed. A recreated view can
    // reuse the same descriptor handle, so bindings compare both values.
    std::uint64_t targets_generation() const;
    // Upload submitted successfully on the same queue used by render().
    const Header *latest_header() const;
    // Static diagnostic text, or nullptr after a successful operation. Fatal
    // initialization failures remain available until destroy(runtime).
    const char *failure_reason() const;
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
}
