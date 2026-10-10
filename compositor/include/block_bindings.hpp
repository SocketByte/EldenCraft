#pragma once
#include <reshade.hpp>

namespace eldencraft::blocks {
template<class Commands>
void bind_block_textures(Commands &cmd, reshade::api::pipeline_layout layout,
    reshade::api::resource_view atlas, reshade::api::resource_view depth,
    reshade::api::resource_view light, bool raw_lighting) {
    using namespace reshade::api;
    const resource_view views[]={atlas,depth,light};
    // Legacy/detail vertices already contain lighting; their shaders do not use
    // t2. Never ask D3D12 to copy an absent lightmap's zero descriptor handle.
    descriptor_table_update update{};update.count=raw_lighting?3:2;
    update.type=descriptor_type::shader_resource_view;update.descriptors=views;
    cmd.push_descriptors(shader_stage::all_graphics,layout,1,update);
}
}
