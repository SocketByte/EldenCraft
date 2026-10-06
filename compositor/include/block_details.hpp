#pragma once
#include "block_protocol.hpp"
namespace eldencraft::blocks {
inline constexpr wchar_t detail_mesh_name[]=L"Local\\EldenCraftBlockDetailMesh";
inline constexpr wchar_t detail_atlas_name[]=L"Local\\EldenCraftBlockDetailAtlas";
// Dynamic decorations share the strict mesh layout, but have a shorter lease
// and smaller count. They may never cross a producer, anchor or mesh session.
inline bool detail_ready(const Header &mesh,const Header &atlas,const Header &resident,std::uint64_t now){
    return mesh.same_context(resident)&&compatible(mesh,atlas)&&mesh.flags==1&&atlas.flags==1
        &&now>=mesh.stamp&&now-mesh.stamp<=250&&now>=atlas.stamp&&now-atlas.stamp<=250
        &&mesh.count<=16380&&mesh.cutout==0&&atlas.count<=256&&atlas.stride==atlas.count*10+1;
}
}
