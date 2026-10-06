#pragma once
#include "block_protocol.hpp"
#include "scene_protocol.hpp"
#include <optional>

namespace eldencraft::blocks {
inline bool scene_context(const Header &h,const frames::Scene &s) {
    return h.epoch==s.epoch&&h.map==s.map&&h.anchor==s.anchor
        &&h.host_pid==s.pid&&h.session==s.mesh_session;
}
inline bool scene_mesh(const Header &h,const frames::Scene &s) {
    return s.mesh_revision&&s.atlas_revision&&scene_context(h,s)
        &&h.revision==s.mesh_revision&&h.atlas_revision==s.atlas_revision;
}
inline bool scene_atlas(const Header &h,const frames::Scene &s) {
    return s.mesh_revision&&s.atlas_revision&&scene_context(h,s)&&h.revision==s.atlas_revision;
}
// One acknowledged upload may be ahead of the captured image. Do not replace
// that future resident repeatedly while older images are still in readback.
// A zero-exclusion image can be either startup readback or explicit fallback.
// Pace that case for 500ms (guest capture expires at250ms, uploaded scene100ms),
// then permit fallback recovery. Heartbeats do not restart the timeout.
inline bool waits_for_captured_resident(const Header &candidate,const Header *acknowledged,const frames::Scene &scene,
    std::uint64_t now,std::uint64_t acknowledged_since) {
    if(!acknowledged||!candidate.same_context(*acknowledged)||candidate.same_content(*acknowledged))return false;
    if(!scene.mesh_revision)return now<acknowledged_since||now-acknowledged_since<500;
    return scene_context(*acknowledged,scene)&&!scene_mesh(*acknowledged,scene);
}
struct Residency {bool valid{},pinned{};std::uint64_t completion{},order{};};
inline std::optional<std::size_t> replacement_slot(std::span<const Residency> slots,std::uint64_t completed) {
    std::optional<std::size_t> result;
    for(std::size_t i=0;i<slots.size();++i) {
        const auto &s=slots[i];if(s.pinned||s.completion>completed)continue;
        if(!result||(!s.valid&&slots[*result].valid)
            ||(s.valid==slots[*result].valid&&s.order<slots[*result].order))result=i;
    }
    return result;
}
}
