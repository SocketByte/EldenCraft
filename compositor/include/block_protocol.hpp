#pragma once
#include "frame_protocol.hpp"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <span>
#include <vector>

namespace eldencraft::blocks {
inline constexpr std::uint32_t mesh_magic=0x424d4345,atlas_magic=0x41424345,ack_magic=0x414d4345,anim_magic=0x4e414345,light_magic=0x4c4d4345;
inline constexpr std::size_t header_bytes=128,mesh_capacity=64*1024*1024,atlas_capacity=90*1024*1024+header_bytes;
// Decorations keep their original, smaller mappings.
inline constexpr std::size_t detail_mesh_capacity=8*1024*1024,detail_atlas_capacity=64*1024*1024+header_bytes;
// Animated sprite frames for the resident atlas, republished every client tick.
inline constexpr std::size_t anim_capacity=4*1024*1024,anim_region_bytes=16;
inline constexpr std::uint32_t max_vertices=2097152,vertex_stride=24,lit_vertex_stride=28,max_atlas_dimension=4096,max_atlas_mips=13,max_anim_regions=4096;
inline constexpr std::size_t light_capacity=header_bytes+1024;
inline constexpr wchar_t light_name[]=L"Local\\EldenCraftBlockLight";
inline constexpr wchar_t mesh_name[]=L"Local\\EldenCraftBlockMesh",atlas_name[]=L"Local\\EldenCraftBlockAtlas";
inline constexpr wchar_t ack_name[]=L"Local\\EldenCraftBlockMeshAck",anim_name[]=L"Local\\EldenCraftBlockAnim";
struct Header {
    std::uint32_t magic{},pid{},host_pid{},map{},flags{},count{},stride{},bytes{},solid{},cutout{},translucent{},mips{1};
    std::uint64_t sequence{},epoch{},session{},stamp{},revision{},atlas_revision{},anchor{};
    bool same_context(const Header &b) const {
        return pid==b.pid&&host_pid==b.host_pid&&map==b.map&&epoch==b.epoch&&session==b.session&&anchor==b.anchor;
    }
    bool same_content(const Header &b) const {
        return same_context(b)&&revision==b.revision&&atlas_revision==b.atlas_revision
            &&count==b.count&&stride==b.stride&&bytes==b.bytes
            &&solid==b.solid&&cutout==b.cutout&&translucent==b.translucent&&mips==b.mips;
    }
    bool fresh(std::uint64_t now,std::uint32_t host) const {
        return flags==1&&host_pid==host&&now>=stamp&&now-stamp<=500;
    }
};
using MeshHeader=Header;
using AtlasHeader=Header;
// Level dimensions follow D3D12/OpenGL: halve and clamp to one texel.
inline std::uint32_t mip_extent(std::uint32_t size,std::uint32_t level){return level>=32?1u:std::max(1u,size>>level);}
inline std::uint32_t max_mips(std::uint32_t width,std::uint32_t height){
    std::uint32_t levels=1;for(auto size=std::max(width,height);size>1;size>>=1)++levels;return std::min(levels,max_atlas_mips);
}
// Tightly packed RGBA8 levels, largest first.
inline std::uint64_t mip_offset(std::uint32_t width,std::uint32_t height,std::uint32_t level){
    std::uint64_t at=0;for(std::uint32_t m=0;m<level;++m)at+=std::uint64_t(mip_extent(width,m))*mip_extent(height,m)*4;return at;
}
inline bool decode_header(std::span<const std::uint8_t> data,std::uint32_t magic,Header &out) {
    using frames::read;
    if(data.size()<header_bytes||read<std::uint32_t>(data,0)!=magic||read<std::uint32_t>(data,4)!=1)return false;
    out.magic=magic;out.sequence=read<std::uint64_t>(data,8);out.pid=read<std::uint32_t>(data,16);
    out.host_pid=read<std::uint32_t>(data,20);out.epoch=read<std::uint64_t>(data,24);
    out.map=read<std::uint32_t>(data,32);out.flags=read<std::uint32_t>(data,36);
    out.session=read<std::uint64_t>(data,40);out.stamp=read<std::uint64_t>(data,48);
    out.revision=read<std::uint64_t>(data,56);out.atlas_revision=read<std::uint64_t>(data,64);
    out.count=read<std::uint32_t>(data,72);out.stride=read<std::uint32_t>(data,76);
    out.bytes=read<std::uint32_t>(data,80);out.solid=read<std::uint32_t>(data,84);
    out.cutout=read<std::uint32_t>(data,88);out.translucent=read<std::uint32_t>(data,92);
    out.anchor=read<std::uint64_t>(data,96);
    const auto mips=read<std::uint32_t>(data,104);out.mips=1;
    if(!out.sequence||(out.sequence&1)||!out.pid||!out.host_pid||!out.epoch||!out.session||!out.anchor
        ||!out.revision||!out.atlas_revision||out.flags>1)return false;
    // Only an atlas carries a level count (0 from older producers means one level).
    for(std::size_t i=magic==atlas_magic?108:104;i<header_bytes;++i)if(data[i])return false;
    if(magic==mesh_magic){
        if(out.count>max_vertices||out.count%3||(out.stride!=vertex_stride&&out.stride!=lit_vertex_stride)
            ||out.bytes!=std::uint64_t(out.count)*out.stride||out.bytes>mesh_capacity-header_bytes
            ||out.solid%3||out.cutout%3||out.translucent%3
            ||std::uint64_t(out.solid)+out.cutout+out.translucent!=out.count)return false;
    }else if(magic==atlas_magic){
        if(!out.count||!out.stride||out.count>max_atlas_dimension||out.stride>max_atlas_dimension)return false;
        out.mips=mips?mips:1;
        if(out.mips>max_mips(out.count,out.stride)
            ||out.bytes!=mip_offset(out.count,out.stride,out.mips)
            ||out.bytes>atlas_capacity-header_bytes||out.solid||out.cutout||out.translucent)return false;
    }else if(magic==light_magic){
        if(out.count!=16||out.stride!=16||out.bytes!=1024||out.solid||out.cutout||out.translucent)return false;
    }else if(magic==ack_magic){
        if(out.count||out.stride||out.bytes||out.solid||out.cutout||out.translucent)return false;
    }else if(magic==anim_magic){
        // revision: animation tick; atlas_revision: the atlas whose sprites it replaces.
        if(out.count>max_anim_regions||out.stride||out.bytes>anim_capacity-header_bytes
            ||std::uint64_t(out.count)*anim_region_bytes>out.bytes
            ||out.solid||out.cutout||out.translucent)return false;
    }else return false;
    return true;
}
inline bool validate_vertices(std::span<const std::uint8_t> bytes,const Header &h) {
    if(bytes.size()!=h.bytes||(h.stride!=vertex_stride&&h.stride!=lit_vertex_stride)||bytes.size()%h.stride)return false;
    for(std::size_t at=0;at<bytes.size();at+=h.stride){
        for(int axis=0;axis<3;++axis){const auto v=frames::read<float>(bytes,at+axis*4);
            if(!std::isfinite(v)||std::abs(v)>3e7f)return false;}
        for(int uv=0;uv<2;++uv){const auto v=frames::read<float>(bytes,at+12+uv*4);
            if(!std::isfinite(v)||v<-.001f||v>1.001f)return false;}
    }
    return true;
}
inline bool compatible(const Header &mesh,const Header &atlas) {
    return mesh.same_context(atlas)&&mesh.atlas_revision==atlas.revision&&atlas.atlas_revision==atlas.revision;
}
// One animated sprite at one mip level: RGBA8 rows at `offset` within the payload.
struct Region { std::uint32_t x{},y{},width{},height{},mip{},offset{}; };
// Every region lies inside its atlas level and its own pixel span inside the payload.
inline bool decode_regions(std::span<const std::uint8_t> payload,const Header &anim,const Header &atlas,std::vector<Region> &out) {
    out.clear();
    if(anim.magic!=anim_magic||atlas.magic!=atlas_magic||payload.size()!=anim.bytes
        ||!anim.same_context(atlas)||anim.atlas_revision!=atlas.revision)return false;
    const std::uint64_t table=std::uint64_t(anim.count)*anim_region_bytes;
    if(table>payload.size())return false;
    out.reserve(anim.count);
    for(std::uint32_t i=0;i<anim.count;++i){
        const auto at=std::size_t(i)*anim_region_bytes;
        Region r{frames::read<std::uint16_t>(payload,at),frames::read<std::uint16_t>(payload,at+2),
            frames::read<std::uint16_t>(payload,at+4),frames::read<std::uint16_t>(payload,at+6),
            frames::read<std::uint8_t>(payload,at+8),frames::read<std::uint32_t>(payload,at+12)};
        if(payload[at+9]||payload[at+10]||payload[at+11]||!r.width||!r.height||r.mip>=atlas.mips
            ||r.offset<table||r.offset%4)return false;
        const auto w=mip_extent(atlas.count,r.mip),h=mip_extent(atlas.stride,r.mip);
        if(std::uint64_t(r.x)+r.width>w||std::uint64_t(r.y)+r.height>h)return false;
        if(std::uint64_t(r.offset)+std::uint64_t(r.width)*r.height*4>payload.size())return false;
        out.push_back(r);
    }
    return true;
}
}
