#include "block_protocol.hpp"
#include "block_details.hpp"
#include <array>
#include <iostream>
#include <limits>
#include <stdexcept>

using namespace eldencraft::blocks;
using Bytes=std::array<std::uint8_t,header_bytes>;
unsigned checks=0;
void check(bool value,const char *message){++checks;if(!value)throw std::runtime_error(message);}
template<class Container,class T>void put(Container &bytes,std::size_t offset,T value){
    std::memcpy(bytes.data()+offset,&value,sizeof(value));
}
Bytes fixture(std::uint32_t magic){
    Bytes b{};
    put(b,0,magic);put(b,4,1u);put(b,8,std::uint64_t(2));
    put(b,16,100u);put(b,20,200u);put(b,24,std::uint64_t(3));
    put(b,32,0x3c272800u);put(b,36,1u);put(b,40,std::uint64_t(4));
    put(b,48,std::uint64_t(1000));put(b,56,std::uint64_t(7));
    put(b,64,std::uint64_t(5));put(b,96,std::uint64_t(11));
    if(magic==mesh_magic){
        put(b,72,9u);put(b,76,24u);put(b,80,216u);
        put(b,84,3u);put(b,88,3u);put(b,92,3u);
    }else if(magic==atlas_magic){
        put(b,56,std::uint64_t(5));put(b,72,2u);put(b,76,3u);put(b,80,24u);
    }else if(magic==anim_magic){
        // Two regions of the 2x3 fixture atlas at revision 5, animation tick 7.
        put(b,64,std::uint64_t(5));put(b,72,2u);put(b,80,32u+4u+8u);
    }
    return b;
}
std::vector<std::uint8_t> anim_payload(){
    std::vector<std::uint8_t> p(44,0x7f);
    auto region=[&](std::size_t at,std::uint16_t x,std::uint16_t y,std::uint16_t w,std::uint16_t h,std::uint8_t mip,std::uint32_t offset){
        put(p,at,x);put(p,at+2,y);put(p,at+4,w);put(p,at+6,h);p[at+8]=mip;p[at+9]=p[at+10]=p[at+11]=0;put(p,at+12,offset);};
    region(0,1,2,1,1,0,32);region(16,0,0,1,2,0,36);
    return p;
}
Header decode(const Bytes &b,std::uint32_t magic){
    Header h;check(decode_header(b,magic,h),"valid fixture must decode");return h;
}
void header_rejections(){
    for(auto magic:{mesh_magic,atlas_magic,ack_magic,anim_magic}){
        const auto good=fixture(magic);Header h;
        for(auto size:{std::size_t(0),std::size_t(64),header_bytes-1})
            check(!decode_header({good.data(),size},magic,h),"truncated header rejected before reads");
        for(auto offset:{0u,4u,16u,20u}){
            auto bad=good;put(bad,offset,0u);check(!decode_header(bad,magic,h),"required version or process identity");
        }
        for(auto offset:{8u,24u,40u,56u,64u,96u}){
            auto bad=good;put(bad,offset,std::uint64_t(0));check(!decode_header(bad,magic,h),"unpublished or missing context identity");
        }
        {auto bad=good;put(bad,8,std::uint64_t(3));check(!decode_header(bad,magic,h),"in-progress seqlock publication rejected");}
        {auto bad=good;put(bad,36,2u);check(!decode_header(bad,magic,h),"unknown active flag rejected");}
        for(std::size_t offset=magic==atlas_magic?108:104;offset<header_bytes;++offset){
            auto bad=good;bad[offset]=1;check(!decode_header(bad,magic,h),"all reserved bytes are checked");
        }
        if(magic!=atlas_magic){auto bad=good;put(bad,104,1u);check(!decode_header(bad,magic,h),"only an atlas carries a mip count");}
        {auto inactive=good;put(inactive,36,0u);check(decode_header(inactive,magic,h),"explicit inactive header is structurally valid");
            check(!h.fresh(1000,200),"inactive header cannot authorize rendering or ack");}
    }
    Header h;auto bad=fixture(mesh_magic);put(bad,0,0xdeadbeefu);
    check(!decode_header(bad,0xdeadbeefu,h),"unknown protocol cannot evade matching-magic check");
    check(!decode_header(fixture(mesh_magic),atlas_magic,h),"mesh cannot be interpreted as atlas");
}
void mesh_bounds(){
    const auto good=fixture(mesh_magic);Header h;
    {auto empty=good;for(auto offset:{72u,80u,84u,88u,92u})put(empty,offset,0u);
        check(decode_header(empty,mesh_magic,h),"empty mesh is an authoritative clear");
        check(validate_vertices({},h),"empty mesh requires no payload");}
    {auto maximum=good;const auto count=max_vertices-max_vertices%3;
        put(maximum,72,count);put(maximum,80,count*vertex_stride);put(maximum,84,count);put(maximum,88,0u);put(maximum,92,0u);
        check(decode_header(maximum,mesh_magic,h),"largest complete triangle payload fits bounded mapping");}
    for(auto count:{1u,8u,max_vertices+2,std::numeric_limits<std::uint32_t>::max()}){
        auto bad=good;put(bad,72,count);put(bad,80,count*vertex_stride);put(bad,84,count);put(bad,88,0u);put(bad,92,0u);
        check(!decode_header(bad,mesh_magic,h),"partial triangle or overflow vertex allocation rejected");
    }
    for(auto bytes:{0u,215u,217u,static_cast<std::uint32_t>(mesh_capacity),std::numeric_limits<std::uint32_t>::max()}){
        auto bad=good;put(bad,80,bytes);check(!decode_header(bad,mesh_magic,h),"payload byte count must agree with complete vertices");
    }
    for(auto stride:{0u,20u,28u,std::numeric_limits<std::uint32_t>::max()}){
        auto bad=good;put(bad,76,stride);check(!decode_header(bad,mesh_magic,h),"foreign vertex layout rejected");
    }
    for(auto offset:{84u,88u,92u}){
        for(auto count:{2u,6u,std::numeric_limits<std::uint32_t>::max()}){
            auto bad=good;put(bad,offset,count);check(!decode_header(bad,mesh_magic,h),"batch boundaries cannot split triangles or exceed payload");
        }
    }
    {auto bad=good;put(bad,84,0xfffffffdu);put(bad,88,9u);put(bad,92,3u);
        check(!decode_header(bad,mesh_magic,h),"batch total cannot be accepted after 32-bit wraparound");}
}
void atlas_and_ack_bounds(){
    const auto good=fixture(atlas_magic);Header h;
    {auto maximum=good;put(maximum,72,max_atlas_dimension);put(maximum,76,max_atlas_dimension);
        put(maximum,80,max_atlas_dimension*max_atlas_dimension*4u);
        check(decode_header(maximum,atlas_magic,h),"maximum RGBA atlas fits mapping exactly");}
    for(auto offset:{72u,76u}){
        for(auto dimension:{0u,max_atlas_dimension+1,std::numeric_limits<std::uint32_t>::max()}){
            auto bad=good;put(bad,offset,dimension);check(!decode_header(bad,atlas_magic,h),"atlas dimensions cannot request unbounded allocation");
        }
    }
    {auto bad=good;put(bad,72,65536u);put(bad,76,65536u);put(bad,80,0u);
        check(!decode_header(bad,atlas_magic,h),"atlas product cannot wrap to a zero-length payload");}
    for(auto bytes:{0u,23u,25u,std::numeric_limits<std::uint32_t>::max()}){
        auto bad=good;put(bad,80,bytes);check(!decode_header(bad,atlas_magic,h),"atlas must have exactly four bytes per pixel");
    }
    for(auto offset:{84u,88u,92u}){
        auto bad=good;put(bad,offset,3u);check(!decode_header(bad,atlas_magic,h),"atlas cannot carry geometry batches");
    }
    for(auto offset:{72u,76u,80u,84u,88u,92u}){
        auto bad=fixture(ack_magic);put(bad,offset,1u);check(!decode_header(bad,ack_magic,h),"ack cannot masquerade as a payload");
    }
    check(decode_header(fixture(ack_magic),ack_magic,h),"identity-only ack accepted");
}
void atlas_mips(){
    Header h;
    check(max_mips(2048,2048)==12&&max_mips(2,3)==2&&max_mips(1,1)==1&&max_mips(4096,16)==13,"full level count, clamped");
    check(mip_extent(3,1)==1&&mip_extent(16,4)==1&&mip_extent(16,9)==1&&mip_extent(2048,4)==128,"levels clamp to one texel");
    check(mip_offset(2,3,2)==28&&mip_offset(2048,2048,1)==2048u*2048*4,"tightly packed level offsets");
    {auto two=fixture(atlas_magic);put(two,104,2u);put(two,80,28u);
        check(decode_header(two,atlas_magic,h)&&h.mips==2,"two-level atlas accepted with exact chain size");}
    {auto legacy=fixture(atlas_magic);check(decode_header(legacy,atlas_magic,h)&&h.mips==1,"zero level count means one level");}
    {auto bad=fixture(atlas_magic);put(bad,104,2u);check(!decode_header(bad,atlas_magic,h),"chain size must include every level");}
    {auto bad=fixture(atlas_magic);put(bad,104,3u);put(bad,80,32u);check(!decode_header(bad,atlas_magic,h),"more levels than the size allows rejected");}
    {auto real=fixture(atlas_magic);put(real,72,2048u);put(real,76,2048u);put(real,104,5u);
        put(real,80,static_cast<std::uint32_t>(mip_offset(2048,2048,5)));
        check(decode_header(real,atlas_magic,h)&&h.mips==5,"vanilla 2048x2048 atlas with four mip levels fits");}
    {auto full=fixture(atlas_magic);put(full,72,max_atlas_dimension);put(full,76,max_atlas_dimension);put(full,104,max_atlas_mips);
        put(full,80,static_cast<std::uint32_t>(mip_offset(max_atlas_dimension,max_atlas_dimension,max_atlas_mips)));
        check(decode_header(full,atlas_magic,h),"largest atlas with its complete chain fits the mapping");}
    auto a=decode(fixture(atlas_magic),atlas_magic),b=a;b.mips=2;
    check(!a.same_content(b),"a new level count requires a fresh texture");
}
void animation(){
    Header h;const auto atlas=decode(fixture(atlas_magic),atlas_magic);
    const auto anim=decode(fixture(anim_magic),anim_magic);
    check(anim.count==2&&anim.revision==7&&anim.atlas_revision==5,"animation names its tick and atlas");
    auto payload=anim_payload();std::vector<Region> regions;
    check(decode_regions(payload,anim,atlas,regions)&&regions.size()==2,"in-bounds regions accepted");
    check(regions[0].x==1&&regions[0].y==2&&regions[1].height==2&&regions[1].offset==36,"region table decoded");
    {auto bad=fixture(anim_magic);put(bad,76,1u);check(!decode_header(bad,anim_magic,h),"animation has no stride");}
    {auto bad=fixture(anim_magic);put(bad,80,31u);check(!decode_header(bad,anim_magic,h),"region table must fit the payload");}
    {auto bad=fixture(anim_magic);put(bad,72,max_anim_regions+1);put(bad,80,(max_anim_regions+1)*16u);
        check(!decode_header(bad,anim_magic,h),"region count bounded");}
    {auto bad=fixture(anim_magic);put(bad,80,static_cast<std::uint32_t>(anim_capacity));check(!decode_header(bad,anim_magic,h),"payload bounded by mapping");}
    auto reject=[&](auto mutate,const char *message){auto p=anim_payload();mutate(p);check(!decode_regions(p,anim,atlas,regions),message);};
    reject([](auto &p){put(p,0,std::uint16_t(2));},"region beyond the atlas width");
    reject([](auto &p){put(p,18,std::uint16_t(2));},"region beyond the atlas height");
    reject([](auto &p){p[8]=1;put(p,0,std::uint16_t(0));put(p,2,std::uint16_t(0));},"mip beyond the atlas chain");
    reject([](auto &p){put(p,12,std::uint32_t(16));},"pixels cannot alias the region table");
    reject([](auto &p){put(p,12,std::uint32_t(34));},"pixel offsets are aligned");
    reject([](auto &p){put(p,28,std::uint32_t(40));},"pixels cannot run past the payload");
    reject([](auto &p){p[9]=1;},"padding is checked");
    reject([](auto &p){put(p,4,std::uint16_t(0));},"empty region rejected");
    {auto p=anim_payload();p.pop_back();check(!decode_regions(p,anim,atlas,regions),"truncated payload rejected");}
    {auto other=atlas;++other.revision;check(!decode_regions(payload,anim,other,regions),"frames for another atlas revision rejected");}
    {auto other=atlas;++other.session;check(!decode_regions(payload,anim,other,regions),"frames from another session rejected");}
    {auto two=atlas;two.mips=2;auto p=anim_payload();p[8]=1;put(p,0,std::uint16_t(0));put(p,2,std::uint16_t(0));
        check(decode_regions(p,anim,two,regions),"lower mip level of a chained atlas accepted");}
}
void vertex_validation(){
    const auto h=decode(fixture(mesh_magic),mesh_magic);
    std::vector<std::uint8_t> payload(h.bytes);
    for(std::size_t at=0;at<payload.size();at+=vertex_stride){
        put(payload,at,12.0f);put(payload,at+4,-3.0f);put(payload,at+8,5.0f);
        put(payload,at+12,0.25f);put(payload,at+16,0.75f);put(payload,at+20,0xffffffffu);
    }
    check(validate_vertices(payload,h),"complete finite triangle batches accepted");
    check(!validate_vertices({payload.data(),payload.size()-1},h),"truncated final color rejected");
    check(!validate_vertices({payload.data(),payload.size()-vertex_stride},h),"missing last vertex rejected");
    {auto extra=payload;extra.push_back(0);check(!validate_vertices(extra,h),"trailing payload cannot evade header bounds");}
    for(auto at:{std::size_t(0),payload.size()-vertex_stride}){
        for(std::size_t axis=0;axis<3;++axis){
            for(float value:{std::numeric_limits<float>::quiet_NaN(),std::numeric_limits<float>::infinity(),-std::numeric_limits<float>::infinity(),30000004.0f,-30000004.0f}){
                auto bad=payload;put(bad,at+axis*4,value);check(!validate_vertices(bad,h),"every position component is finite and bounded");
            }
        }
        for(std::size_t uv=0;uv<2;++uv){
            for(float value:{std::numeric_limits<float>::quiet_NaN(),std::numeric_limits<float>::infinity(),-0.01f,1.01f}){
                auto bad=payload;put(bad,at+12+uv*4,value);check(!validate_vertices(bad,h),"every UV is finite and inside atlas tolerance");
            }
        }
    }
    {auto edge=payload;put(edge,0,30000000.0f);put(edge,4,-30000000.0f);put(edge,12,-.001f);put(edge,16,1.001f);put(edge,20,0u);
        check(validate_vertices(edge,h),"coordinate and UV endpoints plus transparent color are valid");}
}
void context_and_freshness(){
    const auto mesh=decode(fixture(mesh_magic),mesh_magic);
    const auto atlas=decode(fixture(atlas_magic),atlas_magic);
    check(compatible(mesh,atlas),"mesh references the acknowledged atlas revision in same world");
    for(int field=0;field<6;++field){
        auto changed=atlas;
        switch(field){case 0:++changed.pid;break;case 1:++changed.host_pid;break;case 2:++changed.map;break;
            case 3:++changed.epoch;break;case 4:++changed.session;break;case 5:++changed.anchor;break;}
        check(!mesh.same_context(changed)&&!compatible(mesh,changed),"cross-process/world/session/anchor atlas rejected");
        auto next=mesh;
        next.pid=changed.pid;next.host_pid=changed.host_pid;next.map=changed.map;next.epoch=changed.epoch;next.session=changed.session;next.anchor=changed.anchor;
        check(!mesh.same_content(next),"new context cannot reuse cached GPU content");
    }
    {auto changed=atlas;++changed.revision;check(!compatible(mesh,changed),"unmatched atlas revision rejected");}
    {auto changed=atlas;++changed.atlas_revision;check(!compatible(mesh,changed),"atlas must identify its own revision consistently");}
    {auto changed=mesh;++changed.revision;check(compatible(changed,atlas)&&!mesh.same_content(changed),"new geometry can reuse same atlas but must invalidate cached mesh");}
    for(int field=0;field<8;++field){auto changed=mesh;
        switch(field){case 0:++changed.atlas_revision;break;case 1:++changed.count;break;case 2:++changed.stride;break;
            case 3:++changed.bytes;break;case 4:++changed.solid;break;case 5:++changed.cutout;break;
            case 6:++changed.translucent;break;case 7:++changed.revision;break;}
        check(!mesh.same_content(changed),"changed layout or revision requires a fresh payload copy");
    }
    {auto heartbeat=mesh;heartbeat.sequence+=2;heartbeat.stamp+=100;
        check(mesh.same_content(heartbeat),"heartbeat renews unchanged immutable content without copying megabytes");}
    check(mesh.fresh(1000,200)&&mesh.fresh(1500,200),"frame valid from publication through inclusive freshness limit");
    check(!mesh.fresh(999,200)&&!mesh.fresh(1501,200),"future or expired frame rejected");
    check(!mesh.fresh(1000,201),"another host process cannot accept this publication");
    {auto future=mesh;future.stamp=std::numeric_limits<std::uint64_t>::max();check(!future.fresh(1000,200),"unsigned clock subtraction cannot turn future stamp fresh");}
    {auto old=mesh;old.stamp=1;check(!old.fresh(std::numeric_limits<std::uint64_t>::max(),200),"very old frame remains stale without overflow");}
}
int main(){
    header_rejections();mesh_bounds();atlas_and_ack_bounds();atlas_mips();animation();vertex_validation();context_and_freshness();
    auto mesh=decode(fixture(mesh_magic),mesh_magic),atlas=decode(fixture(atlas_magic),atlas_magic);
    mesh.cutout=0;mesh.count=6;mesh.bytes=144;atlas.count=16;atlas.stride=161;atlas.bytes=16*161*4;
    auto resident=mesh;
    check(detail_ready(mesh,atlas,resident,1000),"fresh actual detail pair matches resident context");
    check(detail_ready(mesh,atlas,resident,1250),"detail deadline inclusive");
    check(!detail_ready(mesh,atlas,resident,1251),"stale cracks cannot stay on a removed block");
    check(!detail_ready(mesh,atlas,resident,999),"future detail timestamp rejected");
    for(int field=0;field<6;field++){auto changed=resident;
        switch(field){case 0:++changed.pid;break;case 1:++changed.host_pid;break;case 2:++changed.epoch;break;
            case 3:++changed.map;break;case 4:++changed.session;break;case 5:++changed.anchor;break;}
        check(!detail_ready(mesh,atlas,changed,1000),"new world or mesh context rejects old decorations");}
    {auto gone=mesh;gone.flags=0;check(!detail_ready(gone,atlas,resident,1000),"inactive detail is an immediate clear");}
    {auto gone=mesh;gone.count=gone.bytes=gone.solid=gone.translucent=0;
        check(detail_ready(gone,atlas,resident,1000),"empty details clear completed mining and selection");}
    {auto bad=mesh;bad.count=16383;check(!detail_ready(bad,atlas,resident,1000),"dynamic detail count bounded below static world limit");}
    {auto old=atlas;old.stamp=749;check(!detail_ready(mesh,old,resident,1000),"atlas heartbeat must remain coherent and fresh");}
    {auto wrong=atlas;++wrong.stride;check(!detail_ready(mesh,wrong,resident,1000),"destroy-stage runtime strip layout validated");}
    std::cout<<"Block protocol: "<<checks<<" checks passed; mapping races and rendering require integration validation.\n";
}
