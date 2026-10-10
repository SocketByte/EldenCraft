#include "block_renderer.hpp"
#include "block_bindings.hpp"
#include "block_shader.hpp"
#include "block_residency.hpp"
#include "block_mapping.hpp"
#include "block_details.hpp"
#include "block_sort.hpp"
#include <d3dcompiler.h>
#include <wrl/client.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <numeric>
#include <vector>

namespace eldencraft::blocks {
using namespace reshade::api;
using Microsoft::WRL::ComPtr;
namespace {
constexpr std::uint32_t max_target_dimension=8192;
bool context(const Header &a,const Header &b) {
    return a.epoch==b.epoch && a.session==b.session && a.map==b.map
        && a.pid==b.pid && a.host_pid==b.host_pid && a.anchor==b.anchor;
}
bool revision(const Header &a,const Header &b) {
    return a.same_content(b);
}
void release(device *d,resource &r){if(r.handle)d->destroy_resource(r);r={};}
void release(device *d,resource_view &r){if(r.handle)d->destroy_resource_view(r);r={};}
bool upload_bytes(device *d,resource r,std::span<const std::uint8_t> bytes) {
    if(bytes.empty())return true;
    void *mapped=nullptr;
    if(!d->map_buffer_region(r,0,bytes.size(),map_access::write_only,&mapped))return false;
    std::memcpy(mapped,bytes.data(),bytes.size());d->unmap_buffer_region(r);return true;
}
bool valid_mesh(const Header &h,std::span<const std::uint8_t> data) {
    if(h.magic!=mesh_magic||h.flags!=1||!h.epoch||!h.session||!h.revision||!h.atlas_revision||!h.pid||h.host_pid!=GetCurrentProcessId()
        ||h.count>max_vertices||(h.stride!=vertex_stride&&h.stride!=lit_vertex_stride)
        ||h.bytes!=std::uint64_t(h.count)*h.stride||data.size()!=h.bytes
        ||std::uint64_t(h.solid)+h.cutout+h.translucent!=h.count||h.solid%3||h.cutout%3||h.translucent%3)return false;
    return true;
}
}
struct Renderer::Impl {
    bool details{};
    Mailbox detail_mesh,detail_atlas;
    std::unique_ptr<Renderer> detail_renderer;
    std::uint64_t detail_report{};
    std::uint32_t detail_peak_cracks{},detail_peak_outlines{},detail_drawn_frames{};
    struct Mesh {Header header{};resource vertices{},upload{},indices{};
        std::vector<TriangleCenter> centers;std::vector<std::pair<double,std::uint32_t>> work;std::vector<std::uint32_t> sorted;
        std::array<float,3> forward{};std::uint64_t capacity{},upload_capacity{},index_capacity{},upload_completion{},completion{},order{};bool valid{};};
    // anim_tick: the animation tick whose sprite frames this texture currently holds.
    struct Atlas {Header header{};resource texture{},upload{};resource_view view{};std::uint64_t upload_completion{},completion{},order{},anim_tick{};bool valid{};};
    struct Sort {resource upload{};std::uint64_t capacity{},completion{};};
    struct AnimUpload {resource buffer{};std::uint64_t capacity{},completion{};};
    Mesh *pending_mesh{};Atlas *pending_atlas{};Sort *pending_sort{};AnimUpload *pending_anim{};
    std::array<Mesh,3> meshes{};std::array<Atlas,3> atlases{};std::array<Sort,3> sorts{};std::array<AnimUpload,3> anim_uploads{};
    std::vector<Region> regions;
    pipeline_layout layout{};std::array<pipeline,6> pipelines{};sampler point{};fence fence_{};
    resource lightmap{};resource_view light_view{};Header light_header{};
    struct LightUpload {resource buffer{};std::uint64_t completion{};};
    std::array<LightUpload,3> light_uploads{};
    std::array<resource,2> targets{};std::array<resource_view,2> srvs{},rtvs{};resource zbuffer{};resource_view dsv{};
    std::uint32_t width{},height{};std::uint64_t serial{},order{},target_generation{};bool initialized{},failed{},rendered{};
    Header newest{};bool have_newest{};std::uint64_t newest_since{};
    const char *failure{};
    bool reject(const char *reason){failure=reason;return false;}
    bool fatal(const char *reason){
        failure=reason;
        if(!failed){
            char message[512]{};
            std::snprintf(message,sizeof(message),"EldenCraft blocks: fatal renderer failure: %s. D3D12; root constants=32, SRVs=3, samplers=1; RTVs=RGBA8_UNORM/R32_FLOAT, DSV=D32_FLOAT.",reason);
            reshade::log::message(reshade::log::level::error,message);
        }
        failed=true;return false;
    }
    bool initialize(effect_runtime *runtime) {
        if(failed)return false;if(initialized)return true;
        auto *d=runtime->get_device();
        if(d->get_api()!=device_api::d3d12)return fatal("initialization requires D3D12");
        std::array<ComPtr<ID3DBlob>,2> vs;ComPtr<ID3DBlob> ps,error;
        const UINT flags=D3DCOMPILE_ENABLE_STRICTNESS|D3DCOMPILE_OPTIMIZATION_LEVEL3;
        for(unsigned raw=0;raw<2;++raw){const D3D_SHADER_MACRO macros[]={{"RAW_LIGHT","1"},{nullptr,nullptr}};
        if(FAILED(D3DCompile(block_shader,sizeof(block_shader)-1,"EldenCraftBlocks",raw?macros:nullptr,nullptr,"VS","vs_5_0",flags,0,&vs[raw],&error))){
            if(error)reshade::log::message(reshade::log::level::error,static_cast<const char *>(error->GetBufferPointer()));
            return fatal("compile VS/vs_5_0");
        }
        }
        if(FAILED(D3DCompile(block_shader,sizeof(block_shader)-1,"EldenCraftBlocks",nullptr,nullptr,"PS","ps_5_0",flags,0,&ps,&error))){
            if(error)reshade::log::message(reshade::log::level::error,static_cast<const char *>(error->GetBufferPointer()));
            return fatal("compile PS/ps_5_0");
        }
        constant_range constants{};constants.count=32;constants.visibility=shader_stage::all_graphics;
        descriptor_range texture{};texture.count=3;texture.type=descriptor_type::shader_resource_view;texture.visibility=shader_stage::all_graphics;
        descriptor_range samplers{};samplers.count=1;samplers.type=descriptor_type::sampler;samplers.visibility=shader_stage::pixel;
        pipeline_layout_param params[]={pipeline_layout_param(constants),pipeline_layout_param(texture),pipeline_layout_param(samplers)};
        // Vanilla block rendering: nearest texels, linear between mip levels, so distant
        // blocks do not shimmer. Decorations use a single-level strip and stay point sampled.
        sampler_desc sd{};
        if(details){sd.filter=filter_mode::min_mag_mip_point;sd.min_lod=sd.max_lod=0;}
        else{sd.filter=filter_mode::min_mag_point_mip_linear;sd.min_lod=0;}
        if(!d->create_pipeline_layout(3,params,&layout))return fatal("create pipeline layout/root signature");
        if(!d->create_sampler(sd,&point))return fatal("create point sampler");
        if(!d->create_fence(0,fence_flags::none,&fence_))return fatal("create submission fence");
        input_element elements[4]{};
        const char *names[] = {"POSITION","TEXCOORD","COLOR","TEXCOORD"};
        const format fmts[] = {format::r32g32b32_float,format::r32g32_float,format::r8g8b8a8_unorm,format::r32_uint};
        const std::uint32_t offsets[]={0,12,20,24};
        for(unsigned i=0;i<4;++i){elements[i].location=i;elements[i].semantic=names[i];elements[i].semantic_index=i==3?1:0;elements[i].format=fmts[i];elements[i].offset=offsets[i];}
        shader_desc pixel{};pixel.code=ps->GetBufferPointer();pixel.code_size=ps->GetBufferSize();
        rasterizer_desc raster{};raster.cull_mode=cull_mode::none;raster.scissor_enable=true;
        if(details){raster.depth_bias=-10;raster.slope_scaled_depth_bias=-1;}
        primitive_topology topology=primitive_topology::triangle_list;
        format depth_format=format::d32_float;format color_formats[]={format::r8g8b8a8_unorm,format::r32_float};
        std::uint32_t samples=1;
        for(unsigned raw=0;raw<2;++raw)for(unsigned i=0;i<3;++i){
            shader_desc vertex{};vertex.code=vs[raw]->GetBufferPointer();vertex.code_size=vs[raw]->GetBufferSize();
            for(auto &element:elements)element.stride=raw?lit_vertex_stride:vertex_stride;
            depth_stencil_desc depth{};depth.depth_func=compare_op::less_equal;depth.depth_write_mask=!details&&i!=2;
            blend_desc blend{};blend.render_target_write_mask[1]=1;
            if(details&&i==0){
                // Vanilla CRUMBLING: destination-color/source-color. Preserve
                // the already-rendered surface alpha and linear depth.
                blend.blend_enable[0]=true;blend.source_color_blend_factor[0]=blend_factor::dest_color;
                blend.dest_color_blend_factor[0]=blend_factor::source_color;
                blend.source_alpha_blend_factor[0]=blend_factor::zero;blend.dest_alpha_blend_factor[0]=blend_factor::one;
                blend.render_target_write_mask[1]=0;
            }
            if(i==2){blend.blend_enable[0]=true;blend.dest_color_blend_factor[0]=blend_factor::one_minus_source_alpha;
                blend.dest_alpha_blend_factor[0]=blend_factor::one_minus_source_alpha;}
            pipeline_subobject parts[]={
                {pipeline_subobject_type::vertex_shader,1,&vertex},{pipeline_subobject_type::pixel_shader,1,&pixel},
                {pipeline_subobject_type::input_layout,raw?4u:3u,elements},{pipeline_subobject_type::blend_state,1,&blend},
                {pipeline_subobject_type::rasterizer_state,1,&raster},{pipeline_subobject_type::depth_stencil_state,1,&depth},
                {pipeline_subobject_type::primitive_topology,1,&topology},{pipeline_subobject_type::depth_stencil_format,1,&depth_format},
                {pipeline_subobject_type::render_target_formats,2,color_formats},{pipeline_subobject_type::sample_count,1,&samples}};
            if(!d->create_pipeline(layout,static_cast<std::uint32_t>(std::size(parts)),parts,&pipelines[raw*3+i]))
                return fatal(i==0?"create solid graphics pipeline":i==1?"create cutout graphics pipeline":"create translucent graphics pipeline");
        }
        initialized=true;failure=nullptr;return true;
    }
    bool signal(effect_runtime *runtime,std::uint64_t &value,bool flush=true){
        auto *q=runtime->get_command_queue();if(flush)q->flush_immediate_command_list();value=++serial;
        if(!q->signal(fence_,value))return fatal("signal submission fence");return true;
    }
    void destroy_targets(device *d){
        for(auto &v:srvs)release(d,v);for(auto &v:rtvs)release(d,v);for(auto &r:targets)release(d,r);
        release(d,dsv);release(d,zbuffer);width=height=0;rendered=false;++target_generation;
    }
    bool allocate_targets(effect_runtime *runtime,std::uint32_t w,std::uint32_t h){
        if(w==width&&h==height)return true;
        auto *d=runtime->get_device();
        // Only resize/shutdown waits. No mesh/atlas revision waits for the GPU.
        if(width||height)runtime->get_command_queue()->wait_idle();
        destroy_targets(d);
        for(unsigned i=0;i<2;++i){const auto fmt=i?format::r32_float:format::r8g8b8a8_unorm;
            if(!d->create_resource(resource_desc(w,h,1,1,fmt,1,memory_heap::default_,resource_usage::render_target|resource_usage::shader_resource),nullptr,resource_usage::shader_resource,&targets[i])){
                destroy_targets(d);return reject(i?"create linear-depth target texture":"create color target texture");}
            if(!d->create_resource_view(targets[i],resource_usage::render_target,resource_view_desc(fmt),&rtvs[i])){
                destroy_targets(d);return reject(i?"create linear-depth RTV":"create color RTV");}
            if(!d->create_resource_view(targets[i],resource_usage::shader_resource,resource_view_desc(fmt),&srvs[i])){
                destroy_targets(d);return reject(i?"create linear-depth SRV":"create color SRV");}}
        if(!d->create_resource(resource_desc(w,h,1,1,format::d32_float,1,memory_heap::default_,resource_usage::depth_stencil),nullptr,resource_usage::depth_stencil,&zbuffer)){
            destroy_targets(d);return reject("create D32 depth-stencil texture");}
        if(!d->create_resource_view(zbuffer,resource_usage::depth_stencil,resource_view_desc(format::d32_float),&dsv)){
            destroy_targets(d);return reject("create D32 depth-stencil view");}
        width=w;height=h;return true;
    }
    // Copy this tick's animated sprite frames into the atlas about to be sampled. All
    // atlas work is ordered on the same queue; the transition waits for earlier reads.
    // A busy upload ring or a rejected payload keeps the previous frame (retried next frame).
    void animate(effect_runtime *runtime,command_list *cmd,Atlas &a,const Header &anim,std::span<const std::uint8_t> payload){
        if(anim.revision==a.anim_tick||!decode_regions(payload,anim,a.header,regions)||regions.empty())return;
        auto *d=runtime->get_device();const auto completed=d->get_completed_fence_value(fence_);
        const auto slot=std::find_if(anim_uploads.begin(),anim_uploads.end(),[&](const AnimUpload &u){return u.completion<=completed;});
        if(slot==anim_uploads.end())return;
        std::vector<std::uint64_t> at(regions.size());std::uint64_t total=0;
        for(std::size_t i=0;i<regions.size();++i){
            at[i]=total;const std::uint64_t pitch=(regions[i].width*4u+255u)&~255u;
            total=(total+pitch*regions[i].height+511u)&~std::uint64_t(511);
        }
        if(!slot->buffer.handle||slot->capacity<total){
            release(d,slot->buffer);slot->capacity=0;
            const auto capacity=std::max<std::uint64_t>(total,256*1024);
            if(!d->create_resource(resource_desc(capacity,memory_heap::upload,resource_usage::copy_source),nullptr,resource_usage::cpu_access,&slot->buffer))return;
            slot->capacity=capacity;
        }
        void *mapped=nullptr;
        if(!d->map_buffer_region(slot->buffer,0,total,map_access::write_only,&mapped))return;
        for(std::size_t i=0;i<regions.size();++i){const auto &r=regions[i];const std::uint64_t pitch=(r.width*4u+255u)&~255u;
            for(std::uint32_t y=0;y<r.height;++y)
                std::memcpy(static_cast<std::uint8_t*>(mapped)+at[i]+pitch*y,payload.data()+r.offset+std::size_t(y)*r.width*4,std::size_t(r.width)*4);}
        d->unmap_buffer_region(slot->buffer);
        cmd->barrier(a.texture,resource_usage::shader_resource,resource_usage::copy_dest);
        for(std::size_t i=0;i<regions.size();++i){const auto &r=regions[i];
            const subresource_box box{r.x,r.y,0,r.x+r.width,r.y+r.height,1};
            cmd->copy_buffer_to_texture(slot->buffer,at[i],((r.width*4u+255u)&~255u)/4,r.height,a.texture,r.mip,&box);}
        cmd->barrier(a.texture,resource_usage::copy_dest,resource_usage::shader_resource);
        a.anim_tick=anim.revision;pending_anim=&*slot;
    }
};
Renderer::Renderer(bool details):impl_(std::make_unique<Impl>()){impl_->details=details;}
Renderer::~Renderer()=default;
bool Renderer::lighting(effect_runtime *runtime,const Header &header,std::span<const std::uint8_t> pixels){
    auto &s=*impl_;if(!runtime||s.details||header.magic!=light_magic||header.flags!=1
        ||pixels.size()!=1024||header.count!=16||header.stride!=16)return false;
    if(!s.initialize(runtime))return false;
    if(s.light_view.handle&&s.light_header.same_content(header))return true;
    auto *d=runtime->get_device();const auto completed=d->get_completed_fence_value(s.fence_);
    auto it=std::find_if(s.light_uploads.begin(),s.light_uploads.end(),[&](const Impl::LightUpload &u){return u.completion<=completed;});
    if(it==s.light_uploads.end())return false;
    if(!s.lightmap.handle){
        if(!d->create_resource(resource_desc(16,16,1,1,format::r8g8b8a8_unorm,1,memory_heap::default_,resource_usage::copy_dest|resource_usage::shader_resource),nullptr,resource_usage::shader_resource,&s.lightmap))return false;
        if(!d->create_resource_view(s.lightmap,resource_usage::shader_resource,resource_view_desc(format::r8g8b8a8_unorm),&s.light_view)){release(d,s.lightmap);return false;}
    }
    if(!it->buffer.handle&&!d->create_resource(resource_desc(4096,memory_heap::upload,resource_usage::copy_source),nullptr,resource_usage::cpu_access,&it->buffer))return false;
    void *mapped=nullptr;if(!d->map_buffer_region(it->buffer,0,4096,map_access::write_only,&mapped))return false;
    for(unsigned y=0;y<16;++y)std::memcpy(static_cast<std::uint8_t*>(mapped)+y*256,pixels.data()+y*64,64);
    d->unmap_buffer_region(it->buffer);auto *cmd=runtime->get_command_queue()->get_immediate_command_list();if(!cmd)return false;
    cmd->barrier(s.lightmap,resource_usage::shader_resource,resource_usage::copy_dest);
    cmd->copy_buffer_to_texture(it->buffer,0,64,16,s.lightmap,0);
    cmd->barrier(s.lightmap,resource_usage::copy_dest,resource_usage::shader_resource);
    if(!s.signal(runtime,it->completion))return false;s.light_header=header;return true;
}
bool Renderer::prepare(effect_runtime *runtime,const MeshHeader &mesh,std::span<const std::uint8_t> vertices,
    const AtlasHeader &atlas,std::span<const std::uint8_t> pixels,const frames::Scene &displayed_scene,const frames::HostCamera &camera){
    auto &s=*impl_;if(s.failed)return false;s.failure=nullptr;
    if(!runtime)return s.reject("prepare runtime unavailable");
    if(!s.initialize(runtime))return false;
    if(atlas.magic!=atlas_magic||atlas.flags!=1||!compatible(mesh,atlas)||!valid_mesh(mesh,vertices)
        ||!atlas.count||!atlas.stride||atlas.count>max_atlas_dimension||atlas.stride>max_atlas_dimension
        ||atlas.mips<1||atlas.mips>max_mips(atlas.count,atlas.stride)
        ||mip_offset(atlas.count,atlas.stride,atlas.mips)!=pixels.size()||atlas.bytes!=pixels.size())return s.reject("mesh/atlas context or payload validation");
    const Header *acknowledged=latest_header();
    if(!s.details&&waits_for_captured_resident(mesh,acknowledged,displayed_scene,GetTickCount64(),s.newest_since))
        return s.reject("awaiting captured resident before next mesh revision");
    auto mi=std::find_if(s.meshes.begin(),s.meshes.end(),[&](const Impl::Mesh &m){return m.valid&&revision(m.header,mesh);});
    // Mailbox already validated changed immutable vertices. Do not scan a large
    // payload twice or retain opaque geometry in CPU memory just for sorting.
    if(mesh.stride==lit_vertex_stride&&(!s.light_view.handle||!mesh.same_context(s.light_header)
        ||mesh.atlas_revision!=s.light_header.atlas_revision))return s.reject("matching lightmap unavailable");
    std::uint32_t width{},height{};runtime->get_screenshot_width_and_height(&width,&height);
    if(!width||!height||width>max_target_dimension||height>max_target_dimension)return s.reject("prepare target dimensions");
    if(!s.details&&!s.allocate_targets(runtime,width,height))return false;
    auto *d=runtime->get_device();const auto completed=d->get_completed_fence_value(s.fence_);
    for(auto &m:s.meshes)if(m.upload.handle&&m.upload_completion<=completed){release(d,m.upload);m.upload_capacity=0;}
    for(auto &a:s.atlases)if(a.upload.handle&&a.upload_completion<=completed)release(d,a.upload);
    auto ai=std::find_if(s.atlases.begin(),s.atlases.end(),[&](const Impl::Atlas &a){return a.valid&&context(a.header,atlas)&&a.header.revision==atlas.revision;});
    if(ai!=s.atlases.end()&&!ai->header.same_content(atlas))return s.reject("atlas revision changed layout");
    // Payloads are immutable for a revision. Heartbeat sequences never cause
    // redundant uploads or overwrite data that the mapping reader did not copy.
    if(ai==s.atlases.end()){
        {
            std::array<Residency,3> candidates{};
            for(std::size_t i=0;i<candidates.size();++i){const auto &a=s.atlases[i];
                const bool pinned=a.valid&&(scene_atlas(a.header,displayed_scene)
                    ||(acknowledged&&compatible(*acknowledged,a.header)));
                candidates[i]={a.valid,pinned,a.completion,a.order};}
            const auto slot=replacement_slot(candidates,completed);
            if(!slot)return s.reject("atlas upload ring busy or resident pinned");
            ai=s.atlases.begin()+*slot;
        }
        auto &a=*ai;
        // Every mip level: 256-byte row pitch, 512-byte aligned placement per level.
        const auto levels=atlas.mips;
        std::array<std::uint64_t,max_atlas_mips> placed{};std::array<std::uint32_t,max_atlas_mips> pitches{};std::uint64_t total=0;
        for(std::uint32_t m=0;m<levels;++m){
            placed[m]=total;pitches[m]=(mip_extent(atlas.count,m)*4u+255u)&~255u;
            total=(total+std::uint64_t(pitches[m])*mip_extent(atlas.stride,m)+511u)&~std::uint64_t(511);
        }
        if(!a.texture.handle||a.header.count!=atlas.count||a.header.stride!=atlas.stride||a.header.mips!=levels){
            release(d,a.view);release(d,a.texture);release(d,a.upload);a.valid=false;
            if(!d->create_resource(resource_desc(atlas.count,atlas.stride,1,static_cast<std::uint16_t>(levels),format::r8g8b8a8_unorm,1,memory_heap::default_,resource_usage::copy_dest|resource_usage::shader_resource),nullptr,resource_usage::shader_resource,&a.texture))return s.reject("create atlas texture");
            if(!d->create_resource_view(a.texture,resource_usage::shader_resource,resource_view_desc(format::r8g8b8a8_unorm,0,levels,0,1),&a.view))return s.reject("create atlas SRV");
        }
        if(!a.upload.handle&&!d->create_resource(resource_desc(total,memory_heap::upload,resource_usage::copy_source),nullptr,resource_usage::cpu_access,&a.upload))return s.reject("create atlas upload buffer");
        void *mapped=nullptr;if(!d->map_buffer_region(a.upload,0,total,map_access::write_only,&mapped))return s.reject("map atlas upload buffer");
        for(std::uint32_t m=0;m<levels;++m){
            const auto w=mip_extent(atlas.count,m),h=mip_extent(atlas.stride,m);const auto source=mip_offset(atlas.count,atlas.stride,m);
            for(std::uint32_t y=0;y<h;++y)std::memcpy(static_cast<std::uint8_t*>(mapped)+placed[m]+std::size_t(y)*pitches[m],pixels.data()+source+std::size_t(y)*w*4,std::size_t(w)*4);
        }
        d->unmap_buffer_region(a.upload);auto *cmd=runtime->get_command_queue()->get_immediate_command_list();if(!cmd)return s.reject("atlas upload command list unavailable");
        cmd->barrier(a.texture,resource_usage::shader_resource,resource_usage::copy_dest);
        for(std::uint32_t m=0;m<levels;++m)cmd->copy_buffer_to_texture(a.upload,placed[m],pitches[m]/4,mip_extent(atlas.stride,m),a.texture,m);
        cmd->barrier(a.texture,resource_usage::copy_dest,resource_usage::shader_resource);
        if(!s.signal(runtime,a.completion))return false;a.upload_completion=a.completion;
        a.header=atlas;a.valid=true;a.order=++s.order;a.anim_tick=0;
    }
    if(mi==s.meshes.end()){
        std::array<Residency,3> candidates{};
        for(std::size_t i=0;i<candidates.size();++i){const auto &m=s.meshes[i];
            const bool pinned=m.valid&&(scene_mesh(m.header,displayed_scene)
                ||(acknowledged&&m.header.same_content(*acknowledged)));
            candidates[i]={m.valid,pinned,m.completion,m.order};}
        const auto slot=replacement_slot(candidates,completed);
        if(!slot)return s.reject("mesh upload ring busy or resident pinned");
        mi=s.meshes.begin()+*slot;
        auto &m=*mi;m.valid=false;
        const auto first=mesh.solid+mesh.cutout;
        m.centers.resize(mesh.translucent/3);
        for(std::size_t t=0;t<m.centers.size();++t)for(unsigned c=0;c<3;++c){double sum=0;
            for(unsigned v=0;v<3;++v)sum+=frames::read<float>(vertices,std::size_t(first+t*3+v)*mesh.stride+c*4);
            m.centers[t][c]=sum;}
        translucent_order(m.centers,camera.forward,first,m.work,m.sorted);m.forward=camera.forward;
        // Static geometry is staged once into GPU-local memory. Small, changing
        // details remain in the completed upload ring, as before.
        if(mesh.count){
            if(!m.vertices.handle||m.capacity<vertices.size()){
                release(d,m.vertices);m.capacity=0;
                // Dynamic outlines change camera-scale width; reuse a bounded
                // completed upload slot rather than allocating every frame.
                const auto capacity=s.details?std::uint64_t(16380)*vertex_stride:std::uint64_t(vertices.size());
                if(!d->create_resource(resource_desc(capacity,s.details?memory_heap::upload:memory_heap::default_,
                    s.details?resource_usage::vertex_buffer:resource_usage::vertex_buffer|resource_usage::copy_dest),nullptr,
                    s.details?resource_usage::cpu_access:resource_usage::vertex_buffer,&m.vertices))return s.reject("create vertex buffer");
                m.capacity=capacity;
            }
            const auto index_bytes=std::uint64_t(m.sorted.size())*4;
            if(index_bytes&&(!m.indices.handle||m.index_capacity<index_bytes)){
                release(d,m.indices);m.index_capacity=0;
                if(!d->create_resource(resource_desc(index_bytes,memory_heap::default_,resource_usage::index_buffer|resource_usage::copy_dest),nullptr,resource_usage::index_buffer,&m.indices))return s.reject("create translucent index buffer");
                m.index_capacity=index_bytes;
            }
            if(s.details&&!upload_bytes(d,m.vertices,vertices))return s.reject("map detail vertex buffer");
            const auto vertex_bytes=s.details?0:vertices.size(),total=vertex_bytes+index_bytes;
            if(total){
                if(!m.upload.handle||m.upload_capacity<total){release(d,m.upload);m.upload_capacity=0;
                    if(!d->create_resource(resource_desc(total,memory_heap::upload,resource_usage::copy_source),nullptr,resource_usage::cpu_access,&m.upload))return s.reject("create mesh staging buffer");
                    m.upload_capacity=total;}
                void *mapped=nullptr;if(!d->map_buffer_region(m.upload,0,total,map_access::write_only,&mapped))return s.reject("map mesh staging buffer");
                if(vertex_bytes)std::memcpy(mapped,vertices.data(),vertex_bytes);
                if(index_bytes)std::memcpy(static_cast<std::uint8_t*>(mapped)+vertex_bytes,m.sorted.data(),index_bytes);
                d->unmap_buffer_region(m.upload);auto *cmd=runtime->get_command_queue()->get_immediate_command_list();if(!cmd)return s.reject("mesh upload command list unavailable");
                if(vertex_bytes){cmd->barrier(m.vertices,resource_usage::vertex_buffer,resource_usage::copy_dest);
                    cmd->copy_buffer_region(m.upload,0,m.vertices,0,vertex_bytes);cmd->barrier(m.vertices,resource_usage::copy_dest,resource_usage::vertex_buffer);}
                if(index_bytes){cmd->barrier(m.indices,resource_usage::index_buffer,resource_usage::copy_dest);
                    cmd->copy_buffer_region(m.upload,vertex_bytes,m.indices,0,index_bytes);cmd->barrier(m.indices,resource_usage::copy_dest,resource_usage::index_buffer);}
                if(!s.signal(runtime,m.completion))return false;m.upload_completion=m.completion;
            }
        }
        m.header=mesh;m.valid=true;m.order=++s.order;
    }
    if(!s.have_newest||!s.newest.same_content(mesh))s.newest_since=GetTickCount64();
    s.newest=mesh;s.have_newest=true;return true;
}
bool Renderer::render(effect_runtime *runtime,command_list *cmd,const frames::HostCamera &camera,const frames::Scene &scene,const Header &current_producer,std::uint32_t w,std::uint32_t h,
    resource_view host_depth,int depth_mode,Renderer *destination,const Header *animation,std::span<const std::uint8_t> animation_payload){
    auto &s=*impl_;s.rendered=false;if(s.failed)return false;s.failure=nullptr;
    if(!runtime||!cmd||!s.initialized||!host_depth.handle||(depth_mode!=1&&depth_mode!=2)
        ||!w||!h||w>max_target_dimension||h>max_target_dimension
        ||cmd!=runtime->get_command_queue()->get_immediate_command_list()||!scene.mesh_revision||!scene.atlas_revision
        ||camera.epoch!=scene.epoch||camera.map!=scene.map)return s.reject("render prerequisites or captured exclusion unavailable");
    auto mi=std::find_if(s.meshes.begin(),s.meshes.end(),[&](const Impl::Mesh &m){const auto &a=m.header;
        return m.valid&&a.same_context(current_producer)&&a.epoch==scene.epoch&&a.map==scene.map&&a.anchor==scene.anchor&&a.host_pid==scene.pid&&a.host_pid==GetCurrentProcessId()
            &&a.session==scene.mesh_session&&a.revision==scene.mesh_revision&&a.atlas_revision==scene.atlas_revision;});
    if(mi==s.meshes.end())return s.reject("captured mesh revision not resident");
    auto ai=std::find_if(s.atlases.begin(),s.atlases.end(),[&](const Impl::Atlas&a){return a.valid&&context(a.header,mi->header)&&a.header.revision==scene.atlas_revision;});
    if(ai==s.atlases.end())return s.reject("captured atlas revision not resident");
    auto *d=runtime->get_device();const auto completed=d->get_completed_fence_value(s.fence_);Impl::Sort *sort=nullptr;
    if(mi->header.translucent&&sort_view_changed(mi->forward,camera.forward)){
        const auto it=std::find_if(s.sorts.begin(),s.sorts.end(),[&](const Impl::Sort &a){return a.completion<=completed;});
        // A busy staging ring keeps the resident index buffer and draws the whole
        // mesh. Reading unchanged GPU data is safe even while previous draws run.
        if(it!=s.sorts.end()){
            sort=&*it;const auto bytes=std::uint64_t(mi->header.translucent)*4;
            if(!sort->upload.handle||sort->capacity<bytes){release(d,sort->upload);sort->capacity=0;
                if(!d->create_resource(resource_desc(bytes,memory_heap::upload,resource_usage::copy_source),nullptr,resource_usage::cpu_access,&sort->upload))return s.reject("create translucent staging buffer");sort->capacity=bytes;}
            translucent_order(mi->centers,camera.forward,mi->header.solid+mi->header.cutout,mi->work,mi->sorted);
            if(!upload_bytes(d,sort->upload,{reinterpret_cast<const std::uint8_t*>(mi->sorted.data()),mi->sorted.size()*4}))return s.reject("map translucent staging buffer");
        }
    }
    if(!destination&&!s.allocate_targets(runtime,w,h))return false;
    auto &surface=destination?*destination->impl_:s;
    if(destination&&(!s.details||surface.width!=w||surface.height!=h))return s.reject("detail target mismatch");
    const float tangent=std::tan(camera.fov*.5f),range=camera.far_plane-camera.near_plane;
    if(!std::isfinite(tangent)||tangent<=0||range<=0||camera.aspect<=0)return s.reject("render camera lens validation");
    std::array<float,32> pc{};
    for(unsigned i=0;i<3;++i){pc[i]=static_cast<float>(camera.position[i]);pc[4+i]=camera.right[i];pc[8+i]=camera.up[i];pc[12+i]=camera.forward[i];}
    pc[7]=1/(tangent*camera.aspect);pc[11]=1/tangent;pc[15]=camera.far_plane/range;
    pc[16]=camera.near_plane;pc[17]=camera.far_plane;pc[18]=-camera.near_plane*camera.far_plane/range;
    pc[20]=1.f/w;pc[21]=1.f/h;pc[22]=static_cast<float>(depth_mode);pc[23]=s.details?1.f:0.f;
    // Animated sprites (water, lava, fire, portals) advance in the resident atlas
    // before it is sampled; they never leave the native mesh for the RGB-D scene.
    if(!s.details&&animation)s.animate(runtime,cmd,*ai,*animation,animation_payload);
    if(sort){cmd->barrier(mi->indices,resource_usage::index_buffer,resource_usage::copy_dest);
        cmd->copy_buffer_region(sort->upload,0,mi->indices,0,std::uint64_t(mi->header.translucent)*4);
        cmd->barrier(mi->indices,resource_usage::copy_dest,resource_usage::index_buffer);mi->forward=camera.forward;}
    if(!destination)for(auto r:s.targets)cmd->barrier(r,resource_usage::shader_resource,resource_usage::render_target);
    render_pass_render_target_desc rts[2]{};for(unsigned i=0;i<2;++i){rts[i].view=surface.rtvs[i];rts[i].load_op=destination?render_pass_load_op::load:render_pass_load_op::clear;}
    render_pass_depth_stencil_desc depth{};depth.view=surface.dsv;depth.depth_load_op=destination?render_pass_load_op::load:render_pass_load_op::clear;depth.clear_depth=1;
    cmd->begin_render_pass(2,rts,&depth);
    viewport vp{0,0,static_cast<float>(w),static_cast<float>(h),0,1};rect scissor{0,0,static_cast<std::int32_t>(w),static_cast<std::int32_t>(h)};
    cmd->bind_viewports(0,1,&vp);cmd->bind_scissor_rects(0,1,&scissor);
    // Pinned ReShade6.8's built-in Generic Depth registers before external
    // add-ons. Its begin-effects callback transitions the selected/backup DEPTH
    // texture to shader_resource; finish-effects restores it. The caller passes
    // that validated semantic binding on this SAME immediate command list.
    // Do not invent a barrier from an unknown native depth-stencil state here.
    descriptor_table_update sampler_update{};sampler_update.count=1;sampler_update.type=descriptor_type::sampler;sampler_update.descriptors=&s.point;
    if(mi->header.count)cmd->bind_vertex_buffer(0,mi->vertices,0,mi->header.stride);
    const std::uint32_t counts[]={mi->header.solid,mi->header.cutout,mi->header.translucent};std::uint32_t first=0;
    for(unsigned pass=0;pass<3;++pass){if(counts[pass]){
        cmd->bind_pipeline(pipeline_stage::all_graphics,s.pipelines[pass+(mi->header.stride==lit_vertex_stride?3:0)]);
        pc[19]=static_cast<float>(pass);cmd->push_constants(shader_stage::all_graphics,s.layout,0,0,32,pc.data());
        bind_block_textures(*cmd,s.layout,ai->view,host_depth,s.light_view,mi->header.stride==lit_vertex_stride);
        cmd->push_descriptors(shader_stage::pixel,s.layout,2,sampler_update);
        if(pass==2){cmd->bind_index_buffer(mi->indices,0,4);cmd->draw_indexed(counts[pass],1,0,0,0);}
        else cmd->draw(counts[pass],1,first,0);
    }first+=counts[pass];}
    cmd->end_render_pass();
    if(!s.details){
        const auto now=GetTickCount64();
        const bool mesh_ok=s.detail_mesh.poll(detail_mesh_name,detail_mesh_capacity,mesh_magic,now,GetCurrentProcessId());
        const bool atlas_ok=s.detail_atlas.poll(detail_atlas_name,detail_atlas_capacity,atlas_magic,now,GetCurrentProcessId());
        if((mesh_ok||s.detail_mesh.retained(now,GetCurrentProcessId()))
            &&(atlas_ok||s.detail_atlas.retained(now,GetCurrentProcessId()))
            &&detail_ready(s.detail_mesh.header,s.detail_atlas.header,mi->header,GetTickCount64())){
            if(!s.detail_renderer)s.detail_renderer=std::make_unique<Renderer>(true);
            auto detail_scene=scene;detail_scene.mesh_revision=s.detail_mesh.header.revision;
            detail_scene.atlas_revision=s.detail_mesh.header.atlas_revision;detail_scene.mesh_session=s.detail_mesh.header.session;
            const bool uploaded=s.detail_renderer->prepare(runtime,s.detail_mesh.header,s.detail_mesh.payload,s.detail_atlas.header,s.detail_atlas.payload,detail_scene,camera);
            const bool drawn=uploaded&&s.detail_renderer->render(runtime,cmd,camera,detail_scene,s.detail_mesh.header,w,h,host_depth,depth_mode,this);
            s.detail_peak_cracks=std::max(s.detail_peak_cracks,s.detail_mesh.header.solid);
            s.detail_peak_outlines=std::max(s.detail_peak_outlines,s.detail_mesh.header.translucent);
            if(drawn)++s.detail_drawn_frames;
            if(now-s.detail_report>5000){
                s.detail_report=now;char message[384]{};
                std::snprintf(message,sizeof(message),"EldenCraft block details: drawn=%d, crack vertices=%u, outline vertices=%u, age=%llu ms, interval peak cracks=%u/outlines=%u, drawn frames=%u%s%s.",drawn?1:0,
                    s.detail_mesh.header.solid,s.detail_mesh.header.translucent,static_cast<unsigned long long>(GetTickCount64()-s.detail_mesh.header.stamp),
                    s.detail_peak_cracks,s.detail_peak_outlines,s.detail_drawn_frames,
                    drawn?"":", reason=",drawn?"":(s.detail_renderer->failure_reason()?s.detail_renderer->failure_reason():"unavailable"));
                reshade::log::message(drawn?reshade::log::level::info:reshade::log::level::warning,message);
                s.detail_peak_cracks=s.detail_peak_outlines=s.detail_drawn_frames=0;
            }
        }
    }
    if(!destination)for(auto r:s.targets)cmd->barrier(r,resource_usage::render_target,resource_usage::shader_resource);
    if(destination){
        // The parent submits both passes together. Its flush happens before
        // signalling this renderer's reuse fence below; no second per-frame flush.
        s.pending_mesh=&*mi;s.pending_atlas=&*ai;s.pending_sort=sort;s.rendered=true;return true;
    }
    std::uint64_t completion{};if(!s.signal(runtime,completion))return false;
    if(s.detail_renderer){auto &detail=*s.detail_renderer->impl_;if(detail.pending_mesh){
        std::uint64_t detail_completion{};if(!detail.signal(runtime,detail_completion,false))return false;
        detail.pending_mesh->completion=detail.pending_atlas->completion=detail_completion;
        if(detail.pending_sort)detail.pending_sort->completion=detail_completion;
        detail.pending_mesh=nullptr;detail.pending_atlas=nullptr;detail.pending_sort=nullptr;
    }}
    mi->completion=ai->completion=completion;if(sort)sort->completion=completion;
    if(s.pending_anim){s.pending_anim->completion=completion;s.pending_anim=nullptr;}
    s.rendered=true;return true;
}
void Renderer::destroy(effect_runtime *runtime){
    if(!runtime)return;auto &s=*impl_;auto *d=runtime->get_device();
    if(s.detail_renderer){s.detail_renderer->destroy(runtime);s.detail_renderer.reset();}s.detail_mesh.close();s.detail_atlas.close();
    s.pending_mesh=nullptr;s.pending_atlas=nullptr;s.pending_sort=nullptr;s.pending_anim=nullptr;
    if(s.fence_.handle)runtime->get_command_queue()->wait_idle();s.destroy_targets(d);
    for(auto &m:s.meshes){release(d,m.vertices);release(d,m.upload);release(d,m.indices);m={};}
    for(auto &a:s.atlases){release(d,a.view);release(d,a.texture);release(d,a.upload);a={};}
    for(auto &a:s.sorts){release(d,a.upload);a={};}
    for(auto &a:s.anim_uploads){release(d,a.buffer);a={};}
    release(d,s.light_view);release(d,s.lightmap);s.light_header={};
    for(auto &u:s.light_uploads){release(d,u.buffer);u={};}
    for(auto &p:s.pipelines){if(p.handle)d->destroy_pipeline(p);p={};}
    if(s.layout.handle)d->destroy_pipeline_layout(s.layout);if(s.point.handle)d->destroy_sampler(s.point);if(s.fence_.handle)d->destroy_fence(s.fence_);
    s.layout={};s.point={};s.fence_={};s.initialized=s.failed=s.have_newest=false;s.serial=s.order=0;s.failure=nullptr;
}
resource_view Renderer::color_view()const{return impl_->srvs[0];}
resource_view Renderer::depth_view()const{return impl_->srvs[1];}
std::uint64_t Renderer::targets_generation()const{return impl_->target_generation;}
const char *Renderer::failure_reason()const{return impl_->failure;}
const Header *Renderer::latest_header()const{
    const auto &s=*impl_;if(!s.have_newest||s.failed)return nullptr;
    const bool mesh=std::any_of(s.meshes.begin(),s.meshes.end(),[&](const Impl::Mesh &m){return m.valid&&revision(m.header,s.newest);});
    const bool atlas=std::any_of(s.atlases.begin(),s.atlases.end(),[&](const Impl::Atlas&a){return a.valid&&context(a.header,s.newest)&&a.header.revision==s.newest.atlas_revision;});
    return mesh&&atlas?&s.newest:nullptr;
}
}
