#include "block_renderer.hpp"
#include "block_shader.hpp"
#include "block_residency.hpp"
#include "block_mapping.hpp"
#include "block_details.hpp"
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
struct Vertex { float position[3],uv[2];std::uint8_t tint[4]; };
static_assert(sizeof(Vertex)==24);
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
        ||h.count>max_vertices||h.stride!=24||h.bytes!=std::uint64_t(h.count)*24||data.size()!=h.bytes
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
    struct Mesh {Header header{};resource vertices{};std::vector<Vertex> cpu;std::uint64_t capacity{},completion{},order{};bool valid{};};
    struct Atlas {Header header{};resource texture{},upload{};resource_view view{};std::uint64_t completion{},order{};bool valid{};};
    struct Sort {resource indices{};std::uint64_t completion{};};
    Mesh *pending_mesh{};Atlas *pending_atlas{};Sort *pending_sort{};
    std::array<Mesh,3> meshes{};std::array<Atlas,3> atlases{};std::array<Sort,3> sorts{};
    pipeline_layout layout{};std::array<pipeline,3> pipelines{};sampler point{};fence fence_{};
    std::array<resource,2> targets{};std::array<resource_view,2> srvs{},rtvs{};resource zbuffer{};resource_view dsv{};
    std::uint32_t width{},height{};std::uint64_t serial{},order{},target_generation{};bool initialized{},failed{},rendered{};
    Header newest{};bool have_newest{};std::uint64_t newest_since{};
    const char *failure{};
    bool reject(const char *reason){failure=reason;return false;}
    bool fatal(const char *reason){
        failure=reason;
        if(!failed){
            char message[512]{};
            std::snprintf(message,sizeof(message),"EldenCraft blocks: fatal renderer failure: %s. D3D12; root constants=32, SRVs=2, samplers=1; RTVs=RGBA8_UNORM/R32_FLOAT, DSV=D32_FLOAT.",reason);
            reshade::log::message(reshade::log::level::error,message);
        }
        failed=true;return false;
    }
    bool initialize(effect_runtime *runtime) {
        if(failed)return false;if(initialized)return true;
        auto *d=runtime->get_device();
        if(d->get_api()!=device_api::d3d12)return fatal("initialization requires D3D12");
        ComPtr<ID3DBlob> vs,ps,error;
        const UINT flags=D3DCOMPILE_ENABLE_STRICTNESS|D3DCOMPILE_OPTIMIZATION_LEVEL3;
        if(FAILED(D3DCompile(block_shader,sizeof(block_shader)-1,"EldenCraftBlocks",nullptr,nullptr,"VS","vs_5_0",flags,0,&vs,&error))){
            if(error)reshade::log::message(reshade::log::level::error,static_cast<const char *>(error->GetBufferPointer()));
            return fatal("compile VS/vs_5_0");
        }
        if(FAILED(D3DCompile(block_shader,sizeof(block_shader)-1,"EldenCraftBlocks",nullptr,nullptr,"PS","ps_5_0",flags,0,&ps,&error))){
            if(error)reshade::log::message(reshade::log::level::error,static_cast<const char *>(error->GetBufferPointer()));
            return fatal("compile PS/ps_5_0");
        }
        constant_range constants{};constants.count=32;constants.visibility=shader_stage::all_graphics;
        descriptor_range texture{};texture.count=2;texture.type=descriptor_type::shader_resource_view;texture.visibility=shader_stage::pixel;
        descriptor_range samplers{};samplers.count=1;samplers.type=descriptor_type::sampler;samplers.visibility=shader_stage::pixel;
        pipeline_layout_param params[]={pipeline_layout_param(constants),pipeline_layout_param(texture),pipeline_layout_param(samplers)};
        sampler_desc sd{};sd.filter=filter_mode::min_mag_mip_point;sd.min_lod=sd.max_lod=0;
        if(!d->create_pipeline_layout(3,params,&layout))return fatal("create pipeline layout/root signature");
        if(!d->create_sampler(sd,&point))return fatal("create point sampler");
        if(!d->create_fence(0,fence_flags::none,&fence_))return fatal("create submission fence");
        input_element elements[3]{};
        const char *names[] = {"POSITION","TEXCOORD","COLOR"};
        const format fmts[] = {format::r32g32b32_float,format::r32g32_float,format::r8g8b8a8_unorm};
        const std::uint32_t offsets[]={0,12,20};
        for(unsigned i=0;i<3;++i){elements[i].location=i;elements[i].semantic=names[i];elements[i].format=fmts[i];elements[i].offset=offsets[i];elements[i].stride=24;}
        shader_desc vertex{};vertex.code=vs->GetBufferPointer();vertex.code_size=vs->GetBufferSize();
        shader_desc pixel{};pixel.code=ps->GetBufferPointer();pixel.code_size=ps->GetBufferSize();
        rasterizer_desc raster{};raster.cull_mode=cull_mode::none;raster.scissor_enable=true;
        if(details){raster.depth_bias=-10;raster.slope_scaled_depth_bias=-1;}
        primitive_topology topology=primitive_topology::triangle_list;
        format depth_format=format::d32_float;format color_formats[]={format::r8g8b8a8_unorm,format::r32_float};
        std::uint32_t samples=1;
        for(unsigned i=0;i<3;++i){
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
                {pipeline_subobject_type::input_layout,3,elements},{pipeline_subobject_type::blend_state,1,&blend},
                {pipeline_subobject_type::rasterizer_state,1,&raster},{pipeline_subobject_type::depth_stencil_state,1,&depth},
                {pipeline_subobject_type::primitive_topology,1,&topology},{pipeline_subobject_type::depth_stencil_format,1,&depth_format},
                {pipeline_subobject_type::render_target_formats,2,color_formats},{pipeline_subobject_type::sample_count,1,&samples}};
            if(!d->create_pipeline(layout,static_cast<std::uint32_t>(std::size(parts)),parts,&pipelines[i]))
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
};
Renderer::Renderer(bool details):impl_(std::make_unique<Impl>()){impl_->details=details;}
Renderer::~Renderer()=default;
bool Renderer::prepare(effect_runtime *runtime,const MeshHeader &mesh,std::span<const std::uint8_t> vertices,
    const AtlasHeader &atlas,std::span<const std::uint8_t> pixels,const frames::Scene &displayed_scene){
    auto &s=*impl_;if(s.failed)return false;s.failure=nullptr;
    if(!runtime)return s.reject("prepare runtime unavailable");
    if(!s.initialize(runtime))return false;
    if(atlas.magic!=atlas_magic||atlas.flags!=1||!compatible(mesh,atlas)||!valid_mesh(mesh,vertices)
        ||!atlas.count||!atlas.stride||atlas.count>max_atlas_dimension||atlas.stride>max_atlas_dimension
        ||std::uint64_t(atlas.count)*atlas.stride*4!=pixels.size()||atlas.bytes!=pixels.size())return s.reject("mesh/atlas context or payload validation");
    const Header *acknowledged=latest_header();
    if(!s.details&&waits_for_captured_resident(mesh,acknowledged,displayed_scene,GetTickCount64(),s.newest_since))
        return s.reject("awaiting captured resident before next mesh revision");
    auto mi=std::find_if(s.meshes.begin(),s.meshes.end(),[&](const Impl::Mesh &m){return m.valid&&revision(m.header,mesh);});
    // The mapping reader validates changed immutable payloads; this independent
    // full check is likewise only for a revision not already resident.
    if(mi==s.meshes.end()&&!validate_vertices(vertices,mesh))return s.reject("mesh vertex validation");
    std::uint32_t width{},height{};runtime->get_screenshot_width_and_height(&width,&height);
    if(!width||!height||width>max_target_dimension||height>max_target_dimension)return s.reject("prepare target dimensions");
    if(!s.details&&!s.allocate_targets(runtime,width,height))return false;
    auto *d=runtime->get_device();const auto completed=d->get_completed_fence_value(s.fence_);
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
        const auto pitch=(atlas.count*4u+255u)&~255u;
        if(!a.texture.handle||a.header.count!=atlas.count||a.header.stride!=atlas.stride){
            release(d,a.view);release(d,a.texture);release(d,a.upload);a.valid=false;
            if(!d->create_resource(resource_desc(atlas.count,atlas.stride,1,1,format::r8g8b8a8_unorm,1,memory_heap::default_,resource_usage::copy_dest|resource_usage::shader_resource),nullptr,resource_usage::shader_resource,&a.texture))return s.reject("create atlas texture");
            if(!d->create_resource_view(a.texture,resource_usage::shader_resource,resource_view_desc(format::r8g8b8a8_unorm),&a.view))return s.reject("create atlas SRV");
            if(!d->create_resource(resource_desc(std::uint64_t(pitch)*atlas.stride,memory_heap::upload,resource_usage::copy_source),nullptr,resource_usage::cpu_access,&a.upload))return s.reject("create atlas upload buffer");
        }
        void *mapped=nullptr;if(!d->map_buffer_region(a.upload,0,std::uint64_t(pitch)*atlas.stride,map_access::write_only,&mapped))return s.reject("map atlas upload buffer");
        for(std::uint32_t y=0;y<atlas.stride;++y)std::memcpy(static_cast<std::uint8_t*>(mapped)+std::size_t(y)*pitch,pixels.data()+std::size_t(y)*atlas.count*4,std::size_t(atlas.count)*4);
        d->unmap_buffer_region(a.upload);auto *cmd=runtime->get_command_queue()->get_immediate_command_list();if(!cmd)return s.reject("atlas upload command list unavailable");
        cmd->barrier(a.texture,resource_usage::shader_resource,resource_usage::copy_dest);
        cmd->copy_buffer_to_texture(a.upload,0,pitch/4,atlas.stride,a.texture,0);
        cmd->barrier(a.texture,resource_usage::copy_dest,resource_usage::shader_resource);
        if(!s.signal(runtime,a.completion))return false;
        a.header=atlas;a.valid=true;a.order=++s.order;
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
        // Upload heaps are GENERIC_READ on D3D12, including vertex/index reads.
        if(mesh.count){
            if(!m.vertices.handle||m.capacity<vertices.size()){
                release(d,m.vertices);m.capacity=0;
                // Dynamic outlines change camera-scale width; reuse a bounded
                // completed upload slot rather than allocating every frame.
                const auto capacity=s.details?std::uint64_t(16380)*24:std::uint64_t(vertices.size());
                if(!d->create_resource(resource_desc(capacity,memory_heap::upload,resource_usage::vertex_buffer),nullptr,resource_usage::cpu_access,&m.vertices))return s.reject("create vertex upload buffer");
                m.capacity=capacity;
            }
            if(!upload_bytes(d,m.vertices,vertices))return s.reject("map vertex upload buffer");
        }
        m.cpu.resize(mesh.count);if(!vertices.empty())std::memcpy(m.cpu.data(),vertices.data(),vertices.size());
        m.header=mesh;m.valid=true;m.order=++s.order;
    }
    if(!s.have_newest||!s.newest.same_content(mesh))s.newest_since=GetTickCount64();
    s.newest=mesh;s.have_newest=true;return true;
}
bool Renderer::render(effect_runtime *runtime,command_list *cmd,const frames::HostCamera &camera,const frames::Scene &scene,const Header &current_producer,std::uint32_t w,std::uint32_t h,
    resource_view host_depth,int depth_mode,Renderer *destination){
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
    if(mi->header.translucent){
        const auto it=std::find_if(s.sorts.begin(),s.sorts.end(),[&](const Impl::Sort &a){return a.completion<=completed;});
        if(it==s.sorts.end())return s.reject("translucent index upload ring busy");sort=&*it;
        if(!sort->indices.handle&&!d->create_resource(resource_desc(std::uint64_t(max_vertices)*4,memory_heap::upload,resource_usage::index_buffer),nullptr,resource_usage::cpu_access,&sort->indices))return s.reject("create translucent index upload buffer");
        const std::uint32_t first=mi->header.solid+mi->header.cutout,n=mi->header.translucent/3;
        std::vector<std::pair<double,std::uint32_t>> triangles;triangles.reserve(n);
        for(std::uint32_t t=0;t<n;++t){double depth=0;for(unsigned v=0;v<3;++v)for(unsigned c=0;c<3;++c)
            depth+=(double(mi->cpu[first+t*3+v].position[c])-camera.position[c])*camera.forward[c];triangles.emplace_back(depth,t);}
        std::stable_sort(triangles.begin(),triangles.end(),[](const auto&a,const auto&b){return a.first>b.first;});
        std::vector<std::uint32_t> indices;indices.reserve(n*3);for(const auto &[depth,t]:triangles)for(unsigned v=0;v<3;++v)indices.push_back(first+t*3+v);
        if(!upload_bytes(d,sort->indices,{reinterpret_cast<const std::uint8_t*>(indices.data()),indices.size()*4}))return s.reject("map translucent index upload buffer");
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
    const resource_view shader_views[]={ai->view,host_depth};
    descriptor_table_update texture{};texture.count=2;texture.type=descriptor_type::shader_resource_view;texture.descriptors=shader_views;
    descriptor_table_update sampler_update{};sampler_update.count=1;sampler_update.type=descriptor_type::sampler;sampler_update.descriptors=&s.point;
    if(mi->header.count)cmd->bind_vertex_buffer(0,mi->vertices,0,24);
    const std::uint32_t counts[]={mi->header.solid,mi->header.cutout,mi->header.translucent};std::uint32_t first=0;
    for(unsigned pass=0;pass<3;++pass){if(counts[pass]){
        cmd->bind_pipeline(pipeline_stage::all_graphics,s.pipelines[pass]);
        pc[19]=static_cast<float>(pass);cmd->push_constants(shader_stage::all_graphics,s.layout,0,0,32,pc.data());
        cmd->push_descriptors(shader_stage::pixel,s.layout,1,texture);cmd->push_descriptors(shader_stage::pixel,s.layout,2,sampler_update);
        if(pass==2){cmd->bind_index_buffer(sort->indices,0,4);cmd->draw_indexed(counts[pass],1,0,0,0);}
        else cmd->draw(counts[pass],1,first,0);
    }first+=counts[pass];}
    cmd->end_render_pass();
    if(!s.details){
        const auto now=GetTickCount64();
        const bool mesh_ok=s.detail_mesh.poll(detail_mesh_name,mesh_capacity,mesh_magic,now,GetCurrentProcessId());
        const bool atlas_ok=s.detail_atlas.poll(detail_atlas_name,atlas_capacity,atlas_magic,now,GetCurrentProcessId());
        if((mesh_ok||s.detail_mesh.retained(now,GetCurrentProcessId()))
            &&(atlas_ok||s.detail_atlas.retained(now,GetCurrentProcessId()))
            &&detail_ready(s.detail_mesh.header,s.detail_atlas.header,mi->header,GetTickCount64())){
            if(!s.detail_renderer)s.detail_renderer=std::make_unique<Renderer>(true);
            auto detail_scene=scene;detail_scene.mesh_revision=s.detail_mesh.header.revision;
            detail_scene.atlas_revision=s.detail_mesh.header.atlas_revision;detail_scene.mesh_session=s.detail_mesh.header.session;
            const bool uploaded=s.detail_renderer->prepare(runtime,s.detail_mesh.header,s.detail_mesh.payload,s.detail_atlas.header,s.detail_atlas.payload,detail_scene);
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
    mi->completion=ai->completion=completion;if(sort)sort->completion=completion;s.rendered=true;return true;
}
void Renderer::destroy(effect_runtime *runtime){
    if(!runtime)return;auto &s=*impl_;auto *d=runtime->get_device();
    if(s.detail_renderer){s.detail_renderer->destroy(runtime);s.detail_renderer.reset();}s.detail_mesh.close();s.detail_atlas.close();
    s.pending_mesh=nullptr;s.pending_atlas=nullptr;s.pending_sort=nullptr;
    if(s.fence_.handle)runtime->get_command_queue()->wait_idle();s.destroy_targets(d);
    for(auto &m:s.meshes){release(d,m.vertices);m={};}
    for(auto &a:s.atlases){release(d,a.view);release(d,a.texture);release(d,a.upload);a={};}
    for(auto &a:s.sorts){release(d,a.indices);a={};}
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
