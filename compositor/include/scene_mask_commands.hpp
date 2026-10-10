#pragma once
#include <reshade.hpp>
#include <array>

namespace eldencraft::frames {
// Use the same command sequence in the add-on and the D3D12 integration test.
template<class Commands>
void dispatch_scene_mask(Commands &cmd, reshade::api::pipeline_layout layout,
    const std::array<reshade::api::pipeline,2> &pipelines,
    const std::array<reshade::api::resource,2> &textures,
    const std::array<reshade::api::resource_view,2> &srvs,
    const std::array<reshade::api::resource_view,2> &uavs,
    reshade::api::resource_view depth, std::uint32_t w, std::uint32_t h) {
    using namespace reshade::api;
    const std::uint32_t size[]={w,h};
    descriptor_table_update input{};input.type=descriptor_type::shader_resource_view;input.count=1;input.descriptors=&depth;
    descriptor_table_update output{};output.type=descriptor_type::unordered_access_view;output.count=1;output.descriptors=&uavs[0];
    cmd.barrier(textures[0],resource_usage::shader_resource,resource_usage::unordered_access);
    cmd.push_constants(shader_stage::compute,layout,0,0,2,size);
    // ReShade D3D12 copies CPU descriptor handles directly. A zero view is not
    // a null descriptor: bind only the registers used by each compiled shader.
    cmd.push_descriptors(shader_stage::compute,layout,1,input);cmd.push_descriptors(shader_stage::compute,layout,2,output);
    cmd.bind_pipeline(pipeline_stage::compute_shader,pipelines[0]);cmd.dispatch((w+15)/16,(h+15)/16,1);
    cmd.barrier(textures[0],resource_usage::unordered_access,resource_usage::shader_resource);
    cmd.barrier(textures[1],resource_usage::shader_resource,resource_usage::unordered_access);
    input.binding=1;input.descriptors=&srvs[0];output.binding=1;output.descriptors=&uavs[1];
    cmd.push_descriptors(shader_stage::compute,layout,1,input);cmd.push_descriptors(shader_stage::compute,layout,2,output);
    cmd.bind_pipeline(pipeline_stage::compute_shader,pipelines[1]);cmd.dispatch(1,1,1);
    cmd.barrier(textures[1],resource_usage::unordered_access,resource_usage::shader_resource);
}
}
