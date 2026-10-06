#pragma once
#include "frame_protocol.hpp"
#include <cmath>
#include <cstdint>
#include <span>
#include <vector>

namespace eldencraft::blocks {
inline constexpr std::uint32_t mesh_magic=0x424d4345,atlas_magic=0x41424345,ack_magic=0x414d4345;
inline constexpr std::size_t header_bytes=128,mesh_capacity=8*1024*1024,atlas_capacity=64*1024*1024+header_bytes;
inline constexpr std::uint32_t max_vertices=262144,vertex_stride=24,max_atlas_dimension=4096;
inline constexpr wchar_t mesh_name[]=L"Local\\EldenCraftBlockMesh",atlas_name[]=L"Local\\EldenCraftBlockAtlas";
inline constexpr wchar_t ack_name[]=L"Local\\EldenCraftBlockMeshAck";
struct Header {
    std::uint32_t magic{},pid{},host_pid{},map{},flags{},count{},stride{},bytes{},solid{},cutout{},translucent{};
    std::uint64_t sequence{},epoch{},session{},stamp{},revision{},atlas_revision{},anchor{};
    bool same_context(const Header &b) const {
        return pid==b.pid&&host_pid==b.host_pid&&map==b.map&&epoch==b.epoch&&session==b.session&&anchor==b.anchor;
    }
    bool same_content(const Header &b) const {
        return same_context(b)&&revision==b.revision&&atlas_revision==b.atlas_revision
            &&count==b.count&&stride==b.stride&&bytes==b.bytes
            &&solid==b.solid&&cutout==b.cutout&&translucent==b.translucent;
    }
    bool fresh(std::uint64_t now,std::uint32_t host) const {
        return flags==1&&host_pid==host&&now>=stamp&&now-stamp<=500;
    }
};
using MeshHeader=Header;
using AtlasHeader=Header;
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
    if(!out.sequence||(out.sequence&1)||!out.pid||!out.host_pid||!out.epoch||!out.session||!out.anchor
        ||!out.revision||!out.atlas_revision||out.flags>1)return false;
    for(std::size_t i=104;i<header_bytes;++i)if(data[i])return false;
    if(magic==mesh_magic){
        if(out.count>max_vertices||out.count%3||out.stride!=vertex_stride
            ||out.bytes!=std::uint64_t(out.count)*vertex_stride||out.bytes>mesh_capacity-header_bytes
            ||out.solid%3||out.cutout%3||out.translucent%3
            ||std::uint64_t(out.solid)+out.cutout+out.translucent!=out.count)return false;
    }else if(magic==atlas_magic){
        if(!out.count||!out.stride||out.count>max_atlas_dimension||out.stride>max_atlas_dimension
            ||out.bytes!=std::uint64_t(out.count)*out.stride*4
            ||out.bytes>atlas_capacity-header_bytes||out.solid||out.cutout||out.translucent)return false;
    }else if(magic==ack_magic){
        if(out.count||out.stride||out.bytes||out.solid||out.cutout||out.translucent)return false;
    }else return false;
    return true;
}
inline bool validate_vertices(std::span<const std::uint8_t> bytes,const Header &h) {
    if(bytes.size()!=h.bytes)return false;
    for(std::size_t at=0;at<bytes.size();at+=vertex_stride){
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
}
