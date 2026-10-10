#include "scene_mask.hpp"
#include "scene_mask_shader.hpp"
#include "scene_mask_commands.hpp"
#include <d3dcompiler.h>
#include <wrl/client.h>

namespace eldencraft::frames {
using namespace reshade::api;
bool SceneMask::prepare(effect_runtime *runtime){
    if(srvs_[0].handle&&srvs_[1].handle)return true;if(failed_)return false;
    auto *d=runtime->get_device();
    constant_range size{};size.count=2;size.visibility=shader_stage::compute;
    descriptor_range input{};input.count=2;input.type=descriptor_type::shader_resource_view;input.visibility=shader_stage::compute;
    descriptor_range output{};output.count=2;output.type=descriptor_type::unordered_access_view;output.visibility=shader_stage::compute;
    pipeline_layout_param params[]={pipeline_layout_param(size),pipeline_layout_param(input),pipeline_layout_param(output)};
    bool ok=d->create_pipeline_layout(3,params,&layout_);
    const char *entries[]={"Tiles","Bounds"};
    for(unsigned i=0;ok&&i<2;++i){
        Microsoft::WRL::ComPtr<ID3DBlob> code,diagnostics;
        ok=SUCCEEDED(D3DCompile(scene_mask_shader,sizeof(scene_mask_shader)-1,"EldenCraft scene mask",nullptr,nullptr,entries[i],"cs_5_0",
            D3DCOMPILE_ENABLE_STRICTNESS|D3DCOMPILE_OPTIMIZATION_LEVEL3,0,&code,&diagnostics));
        if(diagnostics)reshade::log::message(reshade::log::level::warning,static_cast<const char*>(diagnostics->GetBufferPointer()));
        if(ok){shader_desc shader{};shader.code=code->GetBufferPointer();shader.code_size=code->GetBufferSize();
            const pipeline_subobject part{pipeline_subobject_type::compute_shader,1,&shader};ok=d->create_pipeline(layout_,1,&part,&pipelines_[i]);}
    }
    for(unsigned i=0;ok&&i<2;++i)ok=d->create_resource(resource_desc(i?1:240,i?1:135,1,1,format::r32g32b32a32_float,1,memory_heap::default_,resource_usage::shader_resource|resource_usage::unordered_access),nullptr,resource_usage::shader_resource,&textures_[i])
        &&d->create_resource_view(textures_[i],resource_usage::shader_resource,resource_view_desc(format::r32g32b32a32_float),&srvs_[i])
        &&d->create_resource_view(textures_[i],resource_usage::unordered_access,resource_view_desc(format::r32g32b32a32_float),&uavs_[i]);
    if(!ok){destroy(runtime);failed_=true;reshade::log::message(reshade::log::level::warning,"EldenCraft scene mask unavailable; retaining full RGB-D search.");}
    return ok;
}
bool SceneMask::update(command_list *cmd,resource_view depth,std::uint32_t w,std::uint32_t h){
    if(!cmd||!srvs_[0].handle||!srvs_[1].handle||!depth.handle||!w||!h||w>3840||h>2160)return false;
    dispatch_scene_mask(*cmd,layout_,pipelines_,textures_,srvs_,uavs_,depth,w,h);return true;
}
void SceneMask::destroy(effect_runtime *runtime){
    auto *d=runtime->get_device();
    for(auto &v:srvs_){if(v.handle)d->destroy_resource_view(v);v={};}
    for(auto &v:uavs_){if(v.handle)d->destroy_resource_view(v);v={};}
    for(auto &t:textures_){if(t.handle)d->destroy_resource(t);t={};}
    for(auto &p:pipelines_){if(p.handle)d->destroy_pipeline(p);p={};}
    if(layout_.handle)d->destroy_pipeline_layout(layout_);layout_={};
}
}
