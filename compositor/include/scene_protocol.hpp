#pragma once
#include "frame_protocol.hpp"
#include <array>
#include <cmath>

namespace eldencraft::frames {
inline constexpr std::size_t scene_offset=1024,scene_bytes=512;
struct Scene {
    std::uint64_t epoch{},anchor{};std::uint32_t map{},pid{};
    std::array<double,3> camera{};
    std::array<float,16> projection{},inverse{}; // Row-major camera-relative world <-> clip.
    // Nonzero only when this captured image omitted the exact acknowledged
    // block mesh. Native rendering must retain that revision through upload lag.
    std::uint64_t mesh_revision{},atlas_revision{},mesh_session{};
    std::array<float,16> avatar_inverse{}; // Pure camera projection inverse, no world rotation.
    std::uint32_t avatar_mode{};
};
inline bool decode_scene(std::span<const std::uint8_t> bytes,Scene &out) {
    if(bytes.size()<scene_bytes||read<std::uint32_t>(bytes,0)!=0x53464345||read<std::uint32_t>(bytes,4)!=1)return false;
    out.epoch=read<std::uint64_t>(bytes,8);out.anchor=read<std::uint64_t>(bytes,16);
    out.map=read<std::uint32_t>(bytes,24);out.pid=read<std::uint32_t>(bytes,28);
    if(!out.epoch||!out.anchor||!out.pid)return false;
    for(int i=0;i<3;++i){out.camera[i]=read<double>(bytes,32+i*8);if(!std::isfinite(out.camera[i])||std::abs(out.camera[i])>3e7)return false;}
    for(int i=0;i<16;++i){out.projection[i]=read<float>(bytes,64+i*4);out.inverse[i]=read<float>(bytes,128+i*4);
        if(!std::isfinite(out.projection[i])||!std::isfinite(out.inverse[i]))return false;}
    for(int row=0;row<4;++row)for(int col=0;col<4;++col){double sum=0;
        for(int k=0;k<4;++k)sum+=double(out.projection[row*4+k])*out.inverse[k*4+col];
        if(std::abs(sum-(row==col?1.0:0.0))>.025)return false;}
    for(std::size_t i=56;i<64;++i)if(bytes[i])return false;
    out.mesh_revision=read<std::uint64_t>(bytes,192);out.atlas_revision=read<std::uint64_t>(bytes,200);
    out.mesh_session=read<std::uint64_t>(bytes,208);
    if((out.mesh_revision||out.atlas_revision||out.mesh_session)
        &&(!out.mesh_revision||!out.atlas_revision||!out.mesh_session))return false;
    out.avatar_mode=read<std::uint32_t>(bytes,280);
    if(out.avatar_mode>2)return false;
    for(int i=0;i<16;++i){out.avatar_inverse[i]=read<float>(bytes,216+i*4);
        if(!std::isfinite(out.avatar_inverse[i])||std::abs(out.avatar_inverse[i])>1e6f)return false;
        if(!out.avatar_mode&&out.avatar_inverse[i]!=0)return false;}
    if(out.avatar_mode){
        // A perspective inverse must recover finite positive distance down -Z.
        // Validate representative depths; degenerate/orthographic matrices cannot authorize a layer.
        for(float z:{.1f,.5f,.9f}){
            const auto &m=out.avatar_inverse;const float v=m[10]*z+m[11],w=m[14]*z+m[15];
            if(!std::isfinite(w)||std::abs(w)<1e-8f||-v/w<=0)return false;
        }
        const auto &m=out.avatar_inverse;
        if(std::abs(m[0])<1e-8f||std::abs(m[5])<1e-8f||std::abs(m[14])<1e-8f
            ||std::abs(double(m[10])*m[15]-double(m[11])*m[14])<1e-8)return false;
        for(int i=0;i<16;++i)if(i!=0&&i!=5&&i!=10&&i!=11&&i!=14&&i!=15&&std::abs(m[i])>1e-5f)return false;
    }
    for(std::size_t i=284;i<scene_bytes;++i)if(bytes[i])return false;
    return true;
}
struct HostCamera {
    std::uint64_t millis{},frame{},epoch{};std::uint32_t map{},source_map{};
    std::array<double,3> position{};std::array<float,3> right{},up{},forward{};
    float fov{},aspect{},near_plane{},far_plane{};
};
inline bool decode_camera(std::span<const std::uint8_t> bytes,std::uint64_t now,HostCamera &out) {
    if(bytes.size()<256||read<std::uint32_t>(bytes,0)!=0x4d414345||read<std::uint32_t>(bytes,4)!=1)return false;
    out.millis=read<std::uint64_t>(bytes,8);out.frame=read<std::uint64_t>(bytes,16);out.epoch=read<std::uint64_t>(bytes,24);
    out.map=read<std::uint32_t>(bytes,32);out.source_map=read<std::uint32_t>(bytes,36);
    if(!out.frame||!out.epoch||now<out.millis||now-out.millis>100||read<std::uint32_t>(bytes,124)!=0)return false;
    for(int i=0;i<3;++i){out.position[i]=read<double>(bytes,40+i*8);out.right[i]=read<float>(bytes,64+i*4);
        out.up[i]=read<float>(bytes,80+i*4);out.forward[i]=read<float>(bytes,96+i*4);
        if(!std::isfinite(out.position[i])||std::abs(out.position[i])>3e7||!std::isfinite(out.right[i])||!std::isfinite(out.up[i])||!std::isfinite(out.forward[i]))return false;}
    const std::array<std::array<float,3>,3> basis={out.right,out.up,out.forward};
    for(int a=0;a<3;++a)for(int b=a;b<3;++b){double dot=0;for(int k=0;k<3;++k)dot+=double(basis[a][k])*basis[b][k];
        if(std::abs(dot-(a==b?1.0:0.0))>.025)return false;}
    out.fov=read<float>(bytes,108);out.aspect=read<float>(bytes,112);out.near_plane=read<float>(bytes,116);out.far_plane=read<float>(bytes,120);
    if(!std::isfinite(out.fov)||out.fov<=.01f||out.fov>=3.13f||!std::isfinite(out.aspect)||out.aspect<.2f||out.aspect>6
        ||!std::isfinite(out.near_plane)||!std::isfinite(out.far_plane)||out.near_plane<=0||out.far_plane<=out.near_plane)return false;
    if(read<std::uint32_t>(bytes,76)||read<std::uint32_t>(bytes,92))return false;
    for(int i=128;i<256;++i)if(bytes[i])return false;
    return true;
}
// The producer can publish during the export call. Sampling the clock before
// copying its packet can falsely classify a valid same-frame camera as future.
// Keep the strict future/expiry checks, but order the observation correctly.
template<class Read,class Clock>
inline bool read_camera(Read &&read_camera_packet,Clock &&clock,HostCamera &out) {
    std::array<std::uint8_t,256> bytes{};
    if(!read_camera_packet(bytes.data(),static_cast<std::uint32_t>(bytes.size())))return false;
    return decode_camera(bytes,clock(),out);
}
}
