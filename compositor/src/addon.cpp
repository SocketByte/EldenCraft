// Real Minecraft overlay consumer. Uses only the public ReShade v6.8.0 API.
#include <imgui.h> // Must precede EVERY indirect reshade.hpp include (public ImGui table).
#include <reshade.hpp>
#include "mapping_reader.hpp"
#include "block_mapping.hpp"
#include "block_renderer.hpp"
#include "gpu_host.hpp"
#include "nether_mapping.hpp"
#include <algorithm>
#include <array>
#include <string>
#include <string_view>
#include <unordered_map>
#include <chrono>
#include <cstdio>

using namespace reshade::api;
namespace {
constexpr const char *effect_name = "EldenCraftPassthrough.fx";
constexpr const char *semantic = "ELDENCRAFT_OVERLAY";
// ReShade 6.8 update_texture_bindings() waits for the whole graphics queue to
// go idle before writing descriptors. Only rebind when a view actually changes.
enum Binding : std::size_t {overlay_binding,scene_binding,scene_depth_binding,avatar_binding,avatar_depth_binding,
    block_color_binding,block_depth_binding,binding_count};
constexpr std::array<const char *,binding_count> binding_semantics={semantic,"ELDENCRAFT_SCENE","ELDENCRAFT_SCENE_DEPTH",
    "ELDENCRAFT_AVATAR","ELDENCRAFT_AVATAR_DEPTH","ELDENCRAFT_BLOCK_COLOR","ELDENCRAFT_BLOCK_DEPTH"};
constexpr std::array<const char *,4> scene_projection_names={"EcSceneProjection0","EcSceneProjection1","EcSceneProjection2","EcSceneProjection3"};
constexpr std::array<const char *,4> scene_inverse_names={"EcSceneInverse0","EcSceneInverse1","EcSceneInverse2","EcSceneInverse3"};
constexpr std::array<const char *,4> avatar_inverse_names={"EcAvatarInverse0","EcAvatarInverse1","EcAvatarInverse2","EcAvatarInverse3"};
// RGB-D reprojection is bounded by guest frame age. Native blocks are drawn
// with the current host camera, so they only need the latest coherent identity
// and may outlive short guest render stalls without flickering.
constexpr std::uint64_t reprojection_age_ms=100,block_scene_age_ms=1000;
struct NameHash {
    using is_transparent=void;
    std::size_t operator()(std::string_view value) const noexcept { return std::hash<std::string_view>{}(value); }
};
template<class Handle> using HandleCache=std::unordered_map<std::string,Handle,NameHash,std::equal_to<>>;
struct Upload { resource buffer{}; std::uint64_t completion{}; };
struct __declspec(uuid("3d398506-a6ea-4f25-88ea-d14f721efb7c")) State {
    eldencraft::frames::MappingReader reader;
    eldencraft::frames::Frame frame;
    eldencraft::blocks::Reader blocks_reader;
    eldencraft::blocks::Renderer blocks_renderer;
    eldencraft::blocks::AckWriter blocks_ack;
    eldencraft::gpu::Host gpu;
    eldencraft::nether::Reader nether;
    float hell_amount{},hell_warp{};std::uint64_t hell_frame{};bool hell_reported{};
    bool blocks_reported{},gpu_reported{};
    std::uint64_t gpu_frames{};
    std::array<Upload,3> uploads{};
    resource texture{}; resource_view view{}; fence copy_fence{};
    std::array<resource,4> scene_textures{};std::array<resource_view,4> scene_views{};
    eldencraft::frames::Scene uploaded_scene;
    bool scene_allocated{},avatar_allocated{},scene_uploaded{},scene_reported{};
    std::uint32_t uploaded_flags{};
    std::uint64_t scene_diagnostic{};
    std::uint32_t width{},height{},row_pitch{};
    std::uint64_t next_fence{},last_upload{},uploaded_publication{},allocation_generation{};
    std::uint64_t stats_started{},effects{},received{},uploaded{},dropped{};
    std::uint64_t scene_missing{},block_missing{},rgbd_fallback{},block_only{};
    double read_ms{},upload_ms{};
    bool failed{},reported{},bottom_up{},enabled{true},previous_key{};
    bool reshade_ui{};
    std::uint64_t input_deadline{};
    // Effect variable handles point into ReShade's effect storage; they are
    // dropped on reshade_reloaded_effects, as the ReShade API requires.
    HandleCache<effect_uniform_variable> uniforms;
    HandleCache<effect_texture_variable> textures;
    std::array<std::pair<std::uint64_t,std::uint64_t>,binding_count> bound{};
};
void log_message(reshade::log::level level,const char *text) { reshade::log::message(level,text); }
using StatsClock=std::chrono::steady_clock;
double elapsed_ms(StatsClock::time_point started) {
    return std::chrono::duration<double,std::milli>(StatsClock::now()-started).count();
}
effect_uniform_variable uniform_variable(effect_runtime *runtime,State &s,std::string_view name) {
    if(const auto it=s.uniforms.find(name);it!=s.uniforms.end())return it->second;
    std::string key(name);
    const auto variable=runtime->find_uniform_variable(effect_name,key.c_str());
    if(variable.handle)s.uniforms.emplace(std::move(key),variable);
    return variable;
}
effect_texture_variable texture_variable(effect_runtime *runtime,State &s,std::string_view name) {
    if(const auto it=s.textures.find(name);it!=s.textures.end())return it->second;
    std::string key(name);
    const auto variable=runtime->find_texture_variable(effect_name,key.c_str());
    if(variable.handle)s.textures.emplace(std::move(key),variable);
    return variable;
}
void forget_effect_handles(State &s) {
    s.uniforms.clear();s.textures.clear();
    // Recompiled effects re-read ReShade's semantic table, but rebind once anyway.
    s.bound.fill({~0ull,~0ull});
}
void bind(effect_runtime *runtime,State &s,Binding binding,resource_view view,std::uint64_t generation) {
    const std::pair<std::uint64_t,std::uint64_t> key{view.handle,generation};
    if(s.bound[binding]==key)return;
    runtime->update_texture_bindings(binding_semantics[binding],view,view);
    s.bound[binding]=key;
}
void report(State &s,std::uint64_t now) {
    if(!s.stats_started) {s.stats_started=now;return;}
    if(now-s.stats_started<5000) return;
    const auto seconds=static_cast<double>(now-s.stats_started)/1000;
    const auto &d=s.frame.descriptor;
    const auto completion=d.publish_ns>=d.capture_ns?static_cast<double>(d.publish_ns-d.capture_ns)/1e6:0;
    char message[768]{};
    std::snprintf(message,sizeof(message),"EldenCraft compositor performance: effectsFps=%.1f receivedFps=%.1f uploadedFps=%.1f dropped=%llu readMs=%.3f uploadMs=%.3f completionMs=%.2f size=%ux%u planes=%u sceneMissingFrames=%llu blockMissingFrames=%llu rgbdFallbackFrames=%llu blockOnlyFrames=%llu gpuSharedFrames=%llu",
        s.effects/seconds,s.received/seconds,s.uploaded/seconds,static_cast<unsigned long long>(s.dropped),
        s.received?s.read_ms/static_cast<double>(s.received):0,s.received?s.upload_ms/static_cast<double>(s.received):0,
        completion,d.width,d.height,(d.flags&32)?5:(d.flags&8)?1:3,
        static_cast<unsigned long long>(s.scene_missing),static_cast<unsigned long long>(s.block_missing),
        static_cast<unsigned long long>(s.rgbd_fallback),static_cast<unsigned long long>(s.block_only),static_cast<unsigned long long>(s.gpu_frames));
    log_message(reshade::log::level::info,message);
    s.stats_started=now;s.effects=s.received=s.uploaded=s.dropped=0;s.read_ms=s.upload_ms=0;
    s.scene_missing=s.block_missing=s.rgbd_fallback=s.block_only=s.gpu_frames=0;
}
void destroy(effect_runtime *runtime, State &state) {
    auto *dev=runtime->get_device();
    for(auto binding:{overlay_binding,scene_binding,scene_depth_binding,avatar_binding,avatar_depth_binding})
        bind(runtime,state,binding,{},0);
    // Only resize/shutdown waits; the ordinary frame path never waits for the GPU.
    if (state.texture.handle || state.copy_fence.handle) runtime->get_command_queue()->wait_idle();
    for(auto &slot:state.uploads) { if(slot.buffer.handle) dev->destroy_resource(slot.buffer); slot={}; }
    if(state.view.handle) dev->destroy_resource_view(state.view);
    if(state.texture.handle) dev->destroy_resource(state.texture);
    if(state.copy_fence.handle) dev->destroy_fence(state.copy_fence);
    for(auto &view:state.scene_views){if(view.handle)dev->destroy_resource_view(view);view={};}
    for(auto &texture:state.scene_textures){if(texture.handle)dev->destroy_resource(texture);texture={};}
    state.view={}; state.texture={}; state.copy_fence={}; state.width=state.height=state.row_pitch=0;
    state.last_upload=state.next_fence=state.uploaded_publication=0;
    state.scene_allocated=state.avatar_allocated=state.scene_uploaded=false;state.uploaded_scene={};
    ++state.allocation_generation;
}
void bind_frame_textures(effect_runtime *runtime,State &s) {
    const auto generation=s.allocation_generation;
    if(s.view.handle)bind(runtime,s,overlay_binding,s.view,generation);
    if(s.scene_allocated){bind(runtime,s,scene_binding,s.scene_views[0],generation);
        bind(runtime,s,scene_depth_binding,s.scene_views[1],generation);}
    if(s.avatar_allocated){bind(runtime,s,avatar_binding,s.scene_views[2],generation);
        bind(runtime,s,avatar_depth_binding,s.scene_views[3],generation);}
}
bool allocate(effect_runtime *runtime,State &s,std::uint32_t w,std::uint32_t h,bool scene,bool avatar) {
    destroy(runtime,s); auto *dev=runtime->get_device();
    s.width=w; s.height=h; s.row_pitch=(w*4+255u)&~255u;
    if(!dev->create_resource(resource_desc(w,h,1,1,format::r8g8b8a8_unorm,1,memory_heap::default_,
        resource_usage::shader_resource|resource_usage::copy_dest),nullptr,resource_usage::shader_resource,&s.texture)
        || !dev->create_resource_view(s.texture,resource_usage::shader_resource,resource_view_desc(format::r8g8b8a8_unorm),&s.view)
        || !dev->create_fence(0,fence_flags::none,&s.copy_fence)) { destroy(runtime,s); return false; }
    if(scene)for(std::size_t i=0;i<(avatar?4u:2u);++i){const auto fmt=(i%2)==0?format::r8g8b8a8_unorm:format::r32_float;
        if(!dev->create_resource(resource_desc(w,h,1,1,fmt,1,memory_heap::default_,resource_usage::shader_resource|resource_usage::copy_dest),nullptr,resource_usage::shader_resource,&s.scene_textures[i])
            ||!dev->create_resource_view(s.scene_textures[i],resource_usage::shader_resource,resource_view_desc(fmt),&s.scene_views[i])){destroy(runtime,s);return false;}}
    s.scene_allocated=scene;s.avatar_allocated=avatar;
    bind_frame_textures(runtime,s);
    return true;
}
// Upload heaps are only needed by shared-memory frames; GPU-shared frames copy
// texture to texture, so this memory is never committed in that mode.
bool ensure_upload_buffers(effect_runtime *runtime,State &s) {
    if(std::all_of(s.uploads.begin(),s.uploads.end(),[](const Upload &u){return u.buffer.handle!=0;}))return true;
    auto *dev=runtime->get_device();
    for(auto &slot:s.uploads) if(!slot.buffer.handle) {
        if(!dev->create_resource(resource_desc(std::uint64_t(s.row_pitch)*s.height*(s.avatar_allocated?5:s.scene_allocated?3:1),memory_heap::upload,resource_usage::copy_source),
            nullptr,resource_usage::cpu_access,&slot.buffer)) { destroy(runtime,s); return false; }
        slot.completion=0;
    }
    return true;
}
void finish_upload(State &s,const eldencraft::frames::Descriptor &d,bool scene) {
    s.bottom_up=(d.flags&2)!=0; s.last_upload=GetTickCount64(); s.uploaded_publication=s.frame.publication;
    s.scene_uploaded=scene;s.uploaded_scene=s.reader.scene;s.uploaded_flags=d.flags;
    if(!s.reported) { s.reported=true; log_message(reshade::log::level::info,"EldenCraft compositor: first stable real Minecraft overlay uploaded."); }
}
enum class UploadResult { uploaded, dropped, pending };
// Zero-copy route: Minecraft rendered straight into a shared texture set. Copy
// it into the private bound textures (GPU to GPU, no queue idle), then
// acknowledge the set once that copy completed.
UploadResult upload_gpu(effect_runtime *runtime,State &s,std::uint64_t now) {
    auto *queue=runtime->get_command_queue();
    const auto d=s.reader.descriptor();
    if(!s.gpu.ready(d)) return UploadResult::pending; // OpenGL has not finished this frame yet.
    const bool scene=(d.flags&16)!=0, avatar=(d.flags&32)!=0;
    if((s.width!=d.width || s.height!=d.height||(scene&&!s.scene_allocated)||(avatar&&!s.avatar_allocated))
        && !allocate(runtime,s,d.width,d.height,scene,avatar)) return UploadResult::dropped;
    if(!s.reader.commit(s.frame,now)) return UploadResult::dropped;
    auto *cmd=queue->get_immediate_command_list();
    if(!cmd) return UploadResult::dropped;
    auto copy=[&](std::uint32_t plane,resource target){
        // The shared source is a simultaneous-access texture in COMMON and is
        // promoted implicitly; only the private destination needs barriers.
        cmd->barrier(target,resource_usage::shader_resource,resource_usage::copy_dest);
        cmd->copy_texture_region(s.gpu.plane(d.gpu_set,plane),0,nullptr,target,0,nullptr);
        cmd->barrier(target,resource_usage::copy_dest,resource_usage::shader_resource);
    };
    copy(2,s.texture);
    if(scene){copy(0,s.scene_textures[0]);copy(1,s.scene_textures[1]);}
    if(avatar){copy(3,s.scene_textures[2]);copy(4,s.scene_textures[3]);}
    queue->flush_immediate_command_list();
    const auto fence=++s.next_fence;
    if(!queue->signal(s.copy_fence,fence)) {
        s.failed=true; log_message(reshade::log::level::error,"EldenCraft compositor: GPU fence signal failed; disabled."); return UploadResult::dropped;
    }
    s.gpu.note_copy(d.gpu_set,d.minecraft_frame,fence);
    finish_upload(s,d,scene);
    ++s.gpu_frames;
    if(!s.gpu_reported){s.gpu_reported=true;log_message(reshade::log::level::info,"EldenCraft compositor: first GPU-shared Minecraft frame copied without CPU readback.");}
    return UploadResult::uploaded;
}
// Copies the acquired shared-memory planes straight into a free upload slot.
// The seqlock is re-validated afterwards; a torn copy is never submitted.
UploadResult upload(effect_runtime *runtime,State &s,std::uint64_t now) {
    if(s.reader.descriptor().flags&eldencraft::frames::gpu_shared_flag) return upload_gpu(runtime,s,now);
    auto *dev=runtime->get_device(); auto *queue=runtime->get_command_queue();
    const auto d=s.reader.descriptor();
    const bool scene=(d.flags&16)!=0;
    const bool avatar=(d.flags&32)!=0;
    if((s.width!=d.width || s.height!=d.height||(scene&&!s.scene_allocated)||(avatar&&!s.avatar_allocated))
        && !allocate(runtime,s,d.width,d.height,scene,avatar)) return UploadResult::dropped;
    if(!ensure_upload_buffers(runtime,s)) return UploadResult::dropped;
    const auto completed=dev->get_completed_fence_value(s.copy_fence);
    auto slot=std::find_if(s.uploads.begin(),s.uploads.end(),[&](const Upload &u){return u.completion<=completed;});
    if(slot==s.uploads.end()) return UploadResult::dropped; // Drop this frame rather than wait or overwrite in-flight data.
    void *mapped=nullptr;
    const auto plane_bytes=std::uint64_t(s.row_pitch)*s.height;
    if(!dev->map_buffer_region(slot->buffer,0,plane_bytes*(avatar?5:scene?3:1),map_access::write_only,&mapped)) return UploadResult::dropped;
    // Destination order: overlay, world, world depth, avatar, avatar depth. Source
    // order follows the producer: world, world depth, overlay, avatar, avatar depth.
    constexpr std::array<std::size_t,5> sources={2,0,1,3,4};
    const std::size_t planes=avatar?5:scene?3:1;
    const std::size_t row=std::size_t(s.width)*4;
    for(std::size_t p=0;p<planes;++p){
        const auto *source=s.reader.plane(sources[p]);
        auto *target=static_cast<std::uint8_t *>(mapped)+plane_bytes*p;
        if(!source){dev->unmap_buffer_region(slot->buffer);return UploadResult::dropped;}
        if(row==s.row_pitch)std::memcpy(target,source,row*s.height);
        else for(std::uint32_t y=0;y<s.height;++y)std::memcpy(target+std::size_t(y)*s.row_pitch,source+std::size_t(y)*row,row);
    }
    dev->unmap_buffer_region(slot->buffer);
    if(!s.reader.commit(s.frame,now)) return UploadResult::dropped;
    auto *cmd=queue->get_immediate_command_list();
    if(!cmd) return UploadResult::dropped;
    cmd->barrier(s.texture,resource_usage::shader_resource,resource_usage::copy_dest);
    cmd->copy_buffer_to_texture(slot->buffer,0,s.row_pitch/4,s.height,s.texture,0);
    cmd->barrier(s.texture,resource_usage::copy_dest,resource_usage::shader_resource);
    if(scene)for(std::size_t i=0;i<(avatar?4u:2u);++i){cmd->barrier(s.scene_textures[i],resource_usage::shader_resource,resource_usage::copy_dest);
        cmd->copy_buffer_to_texture(slot->buffer,plane_bytes*(i+1),s.row_pitch/4,s.height,s.scene_textures[i],0);
        cmd->barrier(s.scene_textures[i],resource_usage::copy_dest,resource_usage::shader_resource);}
    // Submission precedes the signal: an upload slot is reusable only when this
    // exact copy completed. Subsequent effects and future copies use this queue.
    queue->flush_immediate_command_list();
    slot->completion=++s.next_fence;
    if(!queue->signal(s.copy_fence,slot->completion)) {
        s.failed=true; log_message(reshade::log::level::error,"EldenCraft compositor: GPU fence signal failed; disabled."); return UploadResult::dropped;
    }
    finish_upload(s,d,scene);
    return UploadResult::uploaded;
}
bool foreground() { DWORD pid=0; GetWindowThreadProcessId(GetForegroundWindow(),&pid); return pid==GetCurrentProcessId(); }
// Native exports are resolved once per loaded module instance, not every frame.
struct NativeExports {
    HMODULE module{};
    std::uint32_t(*passthrough_active)(){};
    std::uint32_t(*gui_open)(){};
    void(*overlay_input)(std::uint32_t,float,float,std::int32_t){};
    void(*menu_input)(std::uint32_t,float,float,std::int32_t){};
    std::uint32_t(*chat_active)(){};
    std::uint32_t(*chat_event)(std::uint32_t,std::uint32_t,std::uint32_t){};
    std::uint32_t(*scene_camera)(void *,std::uint32_t){};
};
const NativeExports &native() {
    static NativeExports exports;
    // Retry every frame: loader ordering must not decide whether the gate exists.
    const auto module=GetModuleHandleW(L"eldencraft_native.dll");
    if(module!=exports.module){
        exports={};exports.module=module;
        if(module){
            exports.passthrough_active=reinterpret_cast<std::uint32_t(*)()>(GetProcAddress(module,"eldencraft_passthrough_active"));
            exports.gui_open=reinterpret_cast<std::uint32_t(*)()>(GetProcAddress(module,"eldencraft_gui_open"));
            exports.overlay_input=reinterpret_cast<void(*)(std::uint32_t,float,float,std::int32_t)>(GetProcAddress(module,"eldencraft_overlay_input"));
            exports.menu_input=reinterpret_cast<void(*)(std::uint32_t,float,float,std::int32_t)>(GetProcAddress(module,"eldencraft_menu_input"));
            exports.chat_active=reinterpret_cast<std::uint32_t(*)()>(GetProcAddress(module,"eldencraft_chat_active"));
            exports.chat_event=reinterpret_cast<std::uint32_t(*)(std::uint32_t,std::uint32_t,std::uint32_t)>(GetProcAddress(module,"eldencraft_chat_event"));
            exports.scene_camera=reinterpret_cast<std::uint32_t(*)(void *,std::uint32_t)>(GetProcAddress(module,"eldencraft_scene_camera"));
        }
    }
    return exports;
}
bool host_active() {
    // Native host exports an atomic deadline check only, never SDK reads here.
    const auto &exports=native();
    return exports.passthrough_active && exports.passthrough_active()!=0;
}
// Cursor position normalized to the client area, plus this frame's wheel notches.
bool cursor_position(effect_runtime *runtime,float &x,float &y,std::int16_t &wheel) {
    RECT client{};
    if(!GetClientRect(static_cast<HWND>(runtime->get_hwnd()),&client)
        || client.right<=client.left || client.bottom<=client.top) return false;
    std::uint32_t mouse_x=0,mouse_y=0; wheel=0;
    runtime->get_mouse_cursor_position(&mouse_x,&mouse_y,&wheel);
    // Pinned v6.8 input_windows.cpp stores coordinates after ScreenToClient;
    // the API header's "screen coordinates" description is inaccurate here.
    // Negative off-window positions are held in uint32_t by that implementation.
    x=std::clamp(static_cast<float>(static_cast<std::int32_t>(mouse_x)-client.left)
        /static_cast<float>(client.right-client.left),0.0f,1.0f);
    y=std::clamp(static_cast<float>(static_cast<std::int32_t>(mouse_y)-client.top)
        /static_cast<float>(client.bottom-client.top),0.0f,1.0f);
    return true;
}
void capture_gui_input(effect_runtime *runtime,bool active) {
    if(!active) return;
    const auto &exports=native();
    // Missing communication must never leave the game blocked with no way for
    // the guest to receive the close key. These exports only access atomics.
    if(!exports.gui_open || !exports.overlay_input) return;
    float x=0.5f,y=0.5f; std::int16_t wheel=0;
    if(!cursor_position(runtime,x,y,wheel)) return;
    // Exact ECHS order; Y (bit 22) whistles for Torrent. is_key_down accepts virtual
    // keys, including mouse buttons, and avoids v6.8's inconsistent mouse-button
    // index documentation.
    constexpr std::array<std::uint32_t,23> keys={VK_LBUTTON,VK_RBUTTON,'E',VK_ESCAPE,
        '1','2','3','4','5','6','7','8','9',VK_SPACE,VK_SHIFT,VK_CONTROL,'W','S','A','D','Q','F','Y'};
    std::uint32_t buttons=0;
    for(std::size_t i=0;i<keys.size();++i) if(runtime->is_key_down(keys[i])) buttons|=1u<<i;
    exports.overlay_input(buttons,x,y,std::clamp<std::int32_t>(wheel,-16,16)); // Already wheel notches, not Win32 120-unit deltas.
    // Separate optional menu ABI; ECHS's gameplay bits stay compatible with
    // older cores/readers. Include M even during gameplay so the Minecraft map
    // can open before it owns the cursor. Render input still works while the
    // host's OS input APIs are captured by ReShade.
    if(exports.menu_input){
        const auto held=[&](std::uint32_t key){return runtime->is_key_down(key);};
        std::uint32_t menu=0;
        if(held(VK_RETURN)||held('R'))menu|=1u;
        if(held(VK_ESCAPE))menu|=1u<<1;
        if(held(VK_UP)||held('W'))menu|=1u<<2;
        if(held(VK_DOWN)||held('S'))menu|=1u<<3;
        if(held(VK_LEFT)||held('A'))menu|=1u<<4;
        if(held(VK_RIGHT)||held('D'))menu|=1u<<5;
        if(held('M'))menu|=1u<<6;
        if(held(VK_TAB))menu|=1u<<7;
        if(held(VK_OEM_PLUS)||held(VK_ADD)||held(VK_PRIOR))menu|=1u<<8;
        if(held(VK_OEM_MINUS)||held(VK_SUBTRACT)||held(VK_NEXT))menu|=1u<<9;
        if(held(VK_SHIFT))menu|=1u<<10;
        if(held(VK_HOME))menu|=1u<<11;
        exports.menu_input(menu,x,y,std::clamp<std::int32_t>(wheel,-16,16));
    }
    // Public ReShade API owns reversible DirectInput/raw input/Win32 capture,
    // including SetCursorPos suppression. Omit the request to release next frame.
    if(exports.gui_open()!=0) runtime->block_input_next_frame();
}
bool reshade_ui_changed(effect_runtime *runtime,bool open,input_source) {
    if(auto *s=runtime->get_private_data<State>())s->reshade_ui=open;
    return false; // Observe only; never prevent ReShade's own UI.
}
void reloaded_effects(effect_runtime *runtime) {
    if(auto *s=runtime->get_private_data<State>())forget_effect_handles(*s);
}
void chat_input(effect_runtime *runtime) {
    auto *s=runtime->get_private_data<State>();if(!s)return;
    const auto &exports=native();
    const auto active=exports.chat_active;const auto gui=exports.gui_open;const auto emit=exports.chat_event;
    if(!active||!gui||!emit)return;
    if(s->reshade_ui||!foreground()||!host_active()||GetTickCount64()>=s->input_deadline){if(active())emit(0,0,0);return;}
    const auto &io=ImGui::GetIO();
    const std::uint32_t mods=(io.KeyShift?1u:0u)|(io.KeyCtrl?2u:0u)|(io.KeyAlt?4u:0u)|(io.KeySuper?8u:0u);
    bool owned=active()!=0,opened=false;ImWchar opener=0;
    if(!owned&&!gui()&&!io.KeyCtrl&&!io.KeyAlt&&!io.KeySuper){
        for(const auto character:io.InputQueueCharacters)if(character=='/'){opener='/';break;}
        if(opener=='/' || ImGui::IsKeyPressed(ImGuiKey_T,false)){
            opened=emit(opener=='/'?2u:1u,0,0)!=0;owned=opened;if(!opener)opener='t';
        }
    }
    if(!owned)return;
    runtime->block_input_next_frame();
    // This is ReShade's OS-generated Unicode queue, not a US-keyboard lookup.
    // ImGui's pinned build stores BMP characters; vanilla Ctrl+V also preserves
    // full Unicode clipboard strings, including supplementary code points.
    for(const auto character:io.InputQueueCharacters){
        if(opened&&(character==opener||(opener=='t'&&character=='T'))){opened=false;continue;}
        if(character>=32&&character!=127)if(!emit(3,character,mods)){emit(0,0,0);return;}
    }
    struct Key{ImGuiKey key;std::uint32_t glfw;bool repeat;};
    constexpr Key keys[]={{ImGuiKey_Escape,256,false},{ImGuiKey_Enter,257,false},{ImGuiKey_KeypadEnter,257,false},
        {ImGuiKey_Tab,258,true},{ImGuiKey_Backspace,259,true},{ImGuiKey_Insert,260,false},{ImGuiKey_Delete,261,true},
        {ImGuiKey_RightArrow,262,true},{ImGuiKey_LeftArrow,263,true},{ImGuiKey_DownArrow,264,true},{ImGuiKey_UpArrow,265,true},
        {ImGuiKey_PageUp,266,true},{ImGuiKey_PageDown,267,true},{ImGuiKey_Home,268,false},{ImGuiKey_End,269,false}};
    for(const auto &key:keys)if(ImGui::IsKeyPressed(key.key,key.repeat))if(!emit(4,key.glfw,mods)){emit(0,0,0);return;}
    if(io.KeyCtrl&&!io.KeyAlt)for(const auto &key:std::array<Key,4>{{{ImGuiKey_A,65,false},{ImGuiKey_C,67,false},{ImGuiKey_V,86,false},{ImGuiKey_X,88,false}}})
        if(ImGui::IsKeyPressed(key.key,false))if(!emit(4,key.glfw,mods)){emit(0,0,0);return;}
}
// Elden Ring hides the OS cursor, so a guest GUI (inventory, chests, chat) had none.
// Drawn by the host at present time from the same position the guest receives, it
// has no Minecraft-frame latency. ReShade's own UI shows its cursor instead.
void draw_cursor(effect_runtime *runtime) {
    auto *s=runtime->get_private_data<State>();if(!s)return;
    const auto gui=native().gui_open;
    if(!gui||s->reshade_ui||!foreground()||!host_active()||GetTickCount64()>=s->input_deadline||!gui())return;
    float x=0.5f,y=0.5f; std::int16_t wheel=0;
    if(!cursor_position(runtime,x,y,wheel))return;
    const auto &io=ImGui::GetIO();
    if(io.DisplaySize.x<=0.0f||io.DisplaySize.y<=0.0f)return;
    const float scale=std::max(1.0f,io.DisplaySize.y/1080.0f);
    const ImVec2 tip{x*io.DisplaySize.x,y*io.DisplaySize.y};
    // Classic arrow, tip at the hotspot (units of 1080p pixels).
    constexpr float shape[][2]={{0,0},{0,17},{4,13},{7,19.5f},{9.5f,18.5f},{6.5f,12},{12,12}};
    ImVec2 points[std::size(shape)];
    for(std::size_t i=0;i<std::size(shape);++i)points[i]={tip.x+shape[i][0]*scale,tip.y+shape[i][1]*scale};
    // Between NewFrame and EndFrame the implicit window supplies the viewport.
    auto *draw=ImGui::GetForegroundDrawList(nullptr);
    draw->AddConcavePolyFilled(points,static_cast<int>(std::size(points)),IM_COL32(255,255,255,255));
    draw->AddPolyline(points,static_cast<int>(std::size(points)),IM_COL32(0,0,0,255),ImDrawFlags_Closed,1.5f*scale);
}
void overlay(effect_runtime *runtime) {
    chat_input(runtime);
    draw_cursor(runtime);
}
void uniform(effect_runtime *runtime,State &s,std::string_view name,const float *values,std::size_t count) {
    if(const auto variable=uniform_variable(runtime,s,name);variable.handle)runtime->set_uniform_value_float(variable,values,count);
}
void boolean_uniform(effect_runtime *runtime,State &s,std::string_view name,bool value) {
    if(const auto variable=uniform_variable(runtime,s,name);variable.handle)runtime->set_uniform_value_bool(variable,value);
}
void set_active(effect_runtime *runtime,State &s,bool active,bool bottom_up) {
    boolean_uniform(runtime,s,"EcFrameActive",active);
    boolean_uniform(runtime,s,"EcBottomUp",bottom_up);
}
void update_scene(effect_runtime *runtime,command_list *commands,State &s,bool active,std::uint64_t now) {
    // ready: captured RGB-D may be reprojected. blocks_context: the newest
    // uploaded scene still names the native host camera/identity and a usable
    // depth convention, which is all the current-camera block pass needs.
    bool ready=false,depth_ready=false,blocks_context=false;
    resource_view bound_host_depth{};std::int32_t host_depth_mode=0;
    double translation_metres=0;
    const char *reason="inactive or no recent scene";
    eldencraft::frames::HostCamera camera;
    do {
        if(!active||!s.scene_uploaded||now<s.last_upload||now-s.last_upload>block_scene_age_ms)break;
        reason="native camera unavailable";
        const auto read_camera=native().scene_camera;
        if(!read_camera||!eldencraft::frames::read_camera(
            [&](void *data,std::uint32_t size){return read_camera(data,size)==1;},
            []{return GetTickCount64();},camera))break;
        now=GetTickCount64();
        const auto &scene=s.uploaded_scene;
        reason="scene/native identity mismatch";
        if(scene.pid!=GetCurrentProcessId()||scene.epoch!=camera.epoch||scene.map!=camera.map)break;
        std::array<float,3> translation{};double distance=0;
        for(int i=0;i<3;++i){const double delta=camera.position[i]-scene.camera[i];distance+=delta*delta;translation[i]=static_cast<float>(delta);}
        translation_metres=std::sqrt(distance);
        reason="host main-depth binding unavailable";
        resource_view depth{},depth_srgb{};
        const auto texture=texture_variable(runtime,s,"EcHostDepthTexture");
        if(!texture.handle)break;
        runtime->get_texture_binding(texture,&depth,&depth_srgb);
        if(!depth.handle)break;
        auto *device=runtime->get_device();const auto depth_resource=device->get_resource_from_view(depth);
        if(!depth_resource.handle)break;
        const auto desc=device->get_resource_desc(depth_resource);const auto view=device->get_resource_view_desc(depth);
        reason="depth dimensions/format/aspect rejected";
        if(desc.type!=resource_type::texture_2d||desc.texture.samples!=1||desc.texture.width<64||desc.texture.height<64)break;
        if(view.format!=format::r32_float&&view.format!=format::r24_unorm_x8_uint&&view.format!=format::r32_float_x8_uint&&view.format!=format::r16_unorm)break;
        const double aspect=double(desc.texture.width)/desc.texture.height;
        if(std::abs(aspect-camera.aspect)>.03*camera.aspect)break;
        depth_ready=true;
        std::int32_t mode=0;
        if(const auto variable=uniform_variable(runtime,s,"EcDepthMode");variable.handle)runtime->get_uniform_value_int(variable,&mode,1);
        const float lens[4]={std::tan(camera.fov*.5f),camera.aspect,camera.near_plane,camera.far_plane};
        uniform(runtime,s,"EcHostLens",lens,4);uniform(runtime,s,"EcHostRight",camera.right.data(),3);
        uniform(runtime,s,"EcHostUp",camera.up.data(),3);uniform(runtime,s,"EcHostForward",camera.forward.data(),3);
        uniform(runtime,s,"EcHostTranslation",translation.data(),3);
        for(int i=0;i<4;++i){
            uniform(runtime,s,scene_projection_names[i],scene.projection.data()+i*4,4);
            uniform(runtime,s,scene_inverse_names[i],scene.inverse.data()+i*4,4);}
        const float texel[2]={1.0f/s.width,1.0f/s.height};uniform(runtime,s,"EcSceneTexel",texel,2);
        boolean_uniform(runtime,s,"EcSceneZeroToOne",(s.uploaded_flags&1)!=0);
        reason="depth available; calibration mode unselected";
        if(mode!=1&&mode!=2)break;
        bound_host_depth=depth;host_depth_mode=mode;blocks_context=true;
        reason="guest frame stalled; native blocks retained";
        if(now<s.last_upload||now-s.last_upload>reprojection_age_ms)break;
        reason="camera translation exceeds reprojection bound; native blocks retained";
        if(distance>64)break;
        ready=true;reason="scene and selected depth mode ready";
    } while(false);
    bool blocks_ready=false;
    const char *block_reason="scene or fresh mesh unavailable";
    // Poll even while the scene is gated off so a dead guest's named mapping
    // is released and cannot obstruct the next producer session.
    s.blocks_reader.poll(now,GetCurrentProcessId());
    now=GetTickCount64(); // Observe producer deadlines after both mailbox reads.
    const bool mesh_available=s.blocks_reader.retained(now,GetCurrentProcessId());
    const bool mesh_effect=texture_variable(runtime,s,"EcBlockColorTexture").handle
        &&texture_variable(runtime,s,"EcBlockDepthTexture").handle
        &&uniform_variable(runtime,s,"EcBlocksReady").handle;
    if(blocks_context&&mesh_available&&mesh_effect) {
        block_reason="mesh context mismatch or GPU preparation pending";
        const auto &mesh=s.blocks_reader.mesh.header;
        const auto &scene=s.uploaded_scene;
        if(mesh.epoch==camera.epoch&&mesh.map==camera.map&&mesh.anchor==scene.anchor) {
            const bool prepared_candidate=s.blocks_renderer.prepare(runtime,mesh,s.blocks_reader.mesh.payload,
                s.blocks_reader.atlas.header,s.blocks_reader.atlas.payload,scene);
            const char *preparation_failure=prepared_candidate?nullptr:s.blocks_renderer.failure_reason();
            std::uint32_t w=0,h=0;runtime->get_screenshot_width_and_height(&w,&h);
            // A busy candidate upload must not hide the exact older revision
            // named by the captured scene. Session changes still fail closed.
            if(mesh.session==scene.mesh_session)
                blocks_ready=s.blocks_renderer.render(runtime,commands,camera,scene,mesh,w,h,bound_host_depth,host_depth_mode);
            block_reason=blocks_ready?(prepared_candidate?"rendered":"rendered retained resident; candidate pending")
                :"waiting for captured exclusion or retained GPU revision";
            if(!blocks_ready)if(const auto failure=s.blocks_renderer.failure_reason())block_reason=failure;
            const auto prepared=s.blocks_renderer.latest_header();
            // ACK only an intact, successfully prepared pair in this fresh
            // producer context. During upload backpressure this can remain the
            // prior resident; no ACK ever names an unprepared candidate.
            const bool awaiting_prepared_scene=prepared&&(!scene.mesh_revision||scene.mesh_revision!=prepared->revision
                ||scene.atlas_revision!=prepared->atlas_revision||scene.mesh_session!=prepared->session);
            if(prepared&&prepared->same_context(mesh)&&s.blocks_renderer.color_view().handle
                &&s.blocks_renderer.depth_view().handle&&(blocks_ready||awaiting_prepared_scene))
                s.blocks_ack.publish(*prepared,now);
            else s.blocks_ack.clear();
            if(!blocks_ready&&preparation_failure)block_reason=preparation_failure;
        }else {
            s.blocks_ack.clear();
        }
    }else s.blocks_ack.clear();
    if(s.blocks_renderer.color_view().handle){
        const auto generation=s.blocks_renderer.targets_generation();
        bind(runtime,s,block_color_binding,s.blocks_renderer.color_view(),generation);
        bind(runtime,s,block_depth_binding,s.blocks_renderer.depth_view(),generation);
    }
    boolean_uniform(runtime,s,"EcBlocksReady",blocks_ready);
    if(active){
        if(!blocks_context)++s.scene_missing;
        else if(!ready)++s.block_only;
        else if(!s.uploaded_scene.mesh_revision)++s.rgbd_fallback;
        else if(!blocks_ready)++s.block_missing;
    }
    if(blocks_ready!=s.blocks_reported){s.blocks_reported=blocks_ready;
        log_message(reshade::log::level::info,blocks_ready
            ?"EldenCraft blocks: exact Minecraft mesh revision rendered with the submitted host camera."
            :"EldenCraft blocks: mesh/captured-exclusion handshake unavailable; native block pass hidden.");}
    boolean_uniform(runtime,s,"EcSceneReady",ready);boolean_uniform(runtime,s,"EcDepthReady",depth_ready);
    boolean_uniform(runtime,s,"EcAvatarReady",ready&&(s.uploaded_flags&32)&&s.uploaded_scene.avatar_mode!=0);
    if(ready&&(s.uploaded_flags&32))for(int i=0;i<4;++i)
        uniform(runtime,s,avatar_inverse_names[i],s.uploaded_scene.avatar_inverse.data()+i*4,4);
    if(ready!=s.scene_reported){s.scene_reported=ready;
        if(ready)log_message(reshade::log::level::info,
            "EldenCraft scene: coherent camera, depth binding and selected depth convention ready; live occlusion verification required.");
        else{char message[256]{};std::snprintf(message,sizeof(message),
            "EldenCraft scene hidden: %s; HUD remains independent.",reason);log_message(reshade::log::level::info,message);}}
    if(active&&now-s.scene_diagnostic>=5000){s.scene_diagnostic=now;char message[768]{};
        std::snprintf(message,sizeof(message),"EldenCraft scene: %s; map=%08x/%08x epoch=%llu/%llu frame=%llu flags=%u translationM=%.3f cameraAgeMs=%llu; blocks=%s vertices=%u mesh=%llu capturedMesh=%llu atlas=%llu",reason,
            s.uploaded_scene.map,camera.map,static_cast<unsigned long long>(s.uploaded_scene.epoch),static_cast<unsigned long long>(camera.epoch),
            static_cast<unsigned long long>(camera.frame),s.uploaded_flags,translation_metres,
            static_cast<unsigned long long>(camera.millis&&now>=camera.millis?now-camera.millis:0),block_reason,
            s.blocks_reader.mesh.header.count,static_cast<unsigned long long>(s.blocks_reader.mesh.header.revision),
            static_cast<unsigned long long>(s.uploaded_scene.mesh_revision),
            static_cast<unsigned long long>(s.blocks_reader.atlas.header.revision));log_message(reshade::log::level::info,message);}
}
// The Nether (ECNH): Minecraft's page says how much hell, where its blocks are and when the
// lightning, heartbeat and fireballs happened. Positions become camera-relative here, in
// double precision, so the effect never sees large canonical coordinates.
void update_hell(effect_runtime *runtime,State &s,bool active,std::uint64_t now) {
    if(!uniform_variable(runtime,s,"EcHellReady").handle)return; // An effect without the Nether.
    const bool fresh=s.nether.poll(now,GetCurrentProcessId());
    const auto &n=s.nether.state;
    eldencraft::frames::HostCamera camera;bool camera_ready=false;
    if(fresh&&active){
        const auto read_camera=native().scene_camera;
        camera_ready=read_camera&&eldencraft::frames::read_camera(
            [&](void *data,std::uint32_t size){return read_camera(data,size)==1;},[]{return GetTickCount64();},camera);
    }
    // Painting needs the page and the camera to describe the same map; sky, fog and grade do not.
    const bool grid_ready=camera_ready&&camera.epoch==n.epoch&&camera.map==n.map;
    const float dt=s.hell_frame&&now>s.hell_frame?std::min(.1f,static_cast<float>(now-s.hell_frame)/1000.0f):0;s.hell_frame=now;
    const float follow=std::min(1.0f,dt*12);
    // Menus and focus loss close the passthrough gate: drop at once so no menu is tinted.
    if(!active){s.hell_amount=s.hell_warp=0;}
    else {
        s.hell_amount+=((fresh?n.amount:0)-s.hell_amount)*follow;
        s.hell_warp+=((fresh?n.warp:0)-s.hell_warp)*std::min(1.0f,dt*20);
    }
    const bool ready=active&&fresh&&(s.hell_amount>.001f||s.hell_warp>.001f||(n.age(n.opened,now)>=0&&n.age(n.opened,now)<3));
    boolean_uniform(runtime,s,"EcHellReady",ready);
    boolean_uniform(runtime,s,"EcHellGridReady",ready&&grid_ready);
    boolean_uniform(runtime,s,"EcHellCentred",ready&&grid_ready&&n.centred());
    const float amount=ready?s.hell_amount:0,warp=ready?s.hell_warp:0;
    uniform(runtime,s,"EcHellAmount",&amount,1);uniform(runtime,s,"EcHellWarp",&warp,1);
    const float radius=n.centred()?n.radius:eldencraft::nether::everywhere;uniform(runtime,s,"EcHellRadius",&radius,1);
    if(grid_ready){
        std::array<float,3> grid{},center{};
        for(int i=0;i<3;++i){grid[i]=static_cast<float>(n.grid[i]-camera.position[i]);center[i]=static_cast<float>(n.center[i]-camera.position[i]);}
        uniform(runtime,s,"EcHellGrid",grid.data(),3);uniform(runtime,s,"EcHellCenter",center.data(),3);
    }
    const float events[4]={n.age(n.opened,now),n.age(n.flash,now),n.age(n.beat,now),n.age(n.shake_at,now)};
    uniform(runtime,s,"EcHellEvents",events,4);
    const float levels[2]={n.shake,n.dread};uniform(runtime,s,"EcHellLevels",levels,2);
    if(ready!=s.hell_reported){s.hell_reported=ready;
        log_message(reshade::log::level::info,ready?"EldenCraft Nether: Minecraft opened the Nether; Elden Ring is painted from ECNH."
            :"EldenCraft Nether: inactive (closed, stale page or passthrough gate).");}
}
void init_runtime(effect_runtime *runtime) {
    try {
        auto *s=runtime->create_private_data<State>();
        s->failed=runtime->get_device()->get_api()!=device_api::d3d12;
        forget_effect_handles(*s);
        log_message(s->failed?reshade::log::level::warning:reshade::log::level::info,
            s->failed?"EldenCraft compositor: requires Direct3D 12; inactive.":"EldenCraft compositor: D3D12 ready; waiting for Local\\EldenCraftFrame. F11 toggles overlay.");
    } catch(...) { log_message(reshade::log::level::error,"EldenCraft compositor: initialization failed."); }
}
void destroy_runtime(effect_runtime *runtime) {
    if(auto *s=runtime->get_private_data<State>()) {
        s->blocks_ack.clear();s->blocks_renderer.destroy(runtime);
        destroy(runtime,*s);s->gpu.destroy(runtime);runtime->destroy_private_data<State>();
    }
}
void begin_effects(effect_runtime *runtime,command_list *commands,resource_view,resource_view) {
    auto *s=runtime->get_private_data<State>();
    if(!s) return;
    try {
        const auto now=GetTickCount64();
        ++s->effects;
        // Serve size requests and acknowledgements even while composition is
        // gated, so the guest never waits on a set the host stopped reading.
        s->gpu.poll(runtime,now);
        if(s->copy_fence.handle)s->gpu.retire(runtime->get_device()->get_completed_fence_value(s->copy_fence));
        const bool focused=foreground(); const bool gate=focused && host_active(); const bool key=runtime->is_key_down(VK_F11);
        if(focused && key && !s->previous_key) s->enabled=!s->enabled;
        s->previous_key=key;
        if(!s->failed && gate && s->enabled) {
            // This add-on submits its upload before effects. Refuse a foreign
            // command list whose submission order this callback cannot establish.
            if(commands!=runtime->get_command_queue()->get_immediate_command_list()) {
                s->failed=true; log_message(reshade::log::level::error,"EldenCraft compositor: unexpected effects command list; disabled.");
            } else {
                const auto read_started=StatsClock::now();
                const bool received=s->reader.acquire(now);
                s->read_ms+=elapsed_ms(read_started);
                if(received) {
                    const auto upload_started=StatsClock::now();
                    switch(upload(runtime,*s,now)){
                    case UploadResult::uploaded: ++s->received;++s->uploaded;break;
                    case UploadResult::dropped: ++s->received;++s->dropped;break;
                    case UploadResult::pending: break; // Same publication is retried next frame.
                    }
                    s->upload_ms+=elapsed_ms(upload_started);
                }
            }
        }
        bind_frame_textures(runtime,*s);
        const auto display_now=GetTickCount64();
        const bool active=!s->failed && gate && s->enabled && s->last_upload
            && display_now-s->last_upload<=2000 && s->reader.fresh(display_now);
        s->input_deadline=active?display_now+250:0;
        set_active(runtime,*s,active,s->bottom_up);
        update_scene(runtime,commands,*s,active,display_now);
        update_hell(runtime,*s,active,display_now);
        capture_gui_input(runtime,active);
        report(*s,display_now);
    } catch(...) {
        s->failed=true;s->blocks_ack.clear();set_active(runtime,*s,false,false);boolean_uniform(runtime,*s,"EcBlocksReady",false);
        boolean_uniform(runtime,*s,"EcHellReady",false);
        log_message(reshade::log::level::error,"EldenCraft compositor: exception caught; disabled until restart.");
    }
}
}
extern "C" __declspec(dllexport) const char *NAME="EldenCraft actual Minecraft compositor";
extern "C" __declspec(dllexport) const char *DESCRIPTION="Composites the Minecraft-rendered transparent hand/HUD layer through a bounded shared-frame mapping. Offline only.";
extern "C" __declspec(dllexport) bool AddonInit(HMODULE addon,HMODULE reshade_module) {
    if(!reshade::register_addon(addon,reshade_module)) return false;
    reshade::register_event<reshade::addon_event::init_effect_runtime>(init_runtime);
    reshade::register_event<reshade::addon_event::destroy_effect_runtime>(destroy_runtime);
    reshade::register_event<reshade::addon_event::reshade_begin_effects>(begin_effects);
    reshade::register_event<reshade::addon_event::reshade_reloaded_effects>(reloaded_effects);
    reshade::register_event<reshade::addon_event::reshade_overlay>(overlay);
    reshade::register_event<reshade::addon_event::reshade_open_overlay>(reshade_ui_changed);
    return true;
}
extern "C" __declspec(dllexport) void AddonUninit(HMODULE addon,HMODULE reshade_module) {
    reshade::unregister_addon(addon,reshade_module);
}
BOOL WINAPI DllMain(HMODULE module,DWORD reason,LPVOID) {
    if(reason==DLL_PROCESS_ATTACH) DisableThreadLibraryCalls(module);
    return TRUE;
}
