#pragma once
// ECNH v1, Minecraft's Nether state. See PROTOCOL.md. Decoding only; no Windows types.
#include "frame_protocol.hpp"
#include <array>
#include <cmath>

namespace eldencraft::nether {
inline constexpr wchar_t mapping_name[]=L"Local\\EldenCraftNether";
inline constexpr std::uint32_t magic=0x484e4345,version=1,flag_active=1,flag_centred=2;
inline constexpr std::size_t bytes=256;
// Older than this and the page is a dead producer, not a held Nether.
inline constexpr std::uint64_t max_age_ms=500;
inline constexpr float everywhere=100000.0f;
struct State {
    std::uint64_t sequence{},epoch{},anchor{},millis{},opened{},flash{},beat{},shake_at{};
    std::uint32_t pid{},host_pid{},map{},flags{};
    std::array<double,3> grid{},center{};
    float amount{},radius{},warp{},shake{},dread{};
    bool active()const{return (flags&flag_active)!=0;}
    bool centred()const{return (flags&flag_centred)!=0;}
    bool fresh(std::uint64_t now,std::uint32_t host)const{return active()&&host_pid==host&&millis<=now&&now-millis<=max_age_ms;}
    /** Seconds since an event in the same GetTickCount64 domain; negative when it never happened. */
    float age(std::uint64_t at,std::uint64_t now)const{return at==0||at>now?-1.0f:static_cast<float>(now-at)/1000.0f;}
};
inline bool unit(float value){return value>=0&&value<=1;}
inline bool decode(std::span<const std::uint8_t> data,State &out) {
    using frames::read;
    if(data.size()<bytes||read<std::uint32_t>(data,0)!=magic||read<std::uint32_t>(data,4)!=version)return false;
    State s;
    s.sequence=read<std::uint64_t>(data,8);s.pid=read<std::uint32_t>(data,16);s.host_pid=read<std::uint32_t>(data,20);
    s.epoch=read<std::uint64_t>(data,24);s.map=read<std::uint32_t>(data,32);s.flags=read<std::uint32_t>(data,36);
    s.millis=read<std::uint64_t>(data,40);s.anchor=read<std::uint64_t>(data,48);
    for(int i=0;i<3;++i){s.grid[i]=read<double>(data,56+i*8);s.center[i]=read<double>(data,80+i*8);
        if(!std::isfinite(s.grid[i])||!std::isfinite(s.center[i])||std::abs(s.grid[i])>3e7||std::abs(s.center[i])>3e7)return false;}
    s.amount=read<float>(data,104);s.radius=read<float>(data,108);s.warp=read<float>(data,112);s.shake=read<float>(data,116);s.dread=read<float>(data,120);
    s.opened=read<std::uint64_t>(data,128);s.flash=read<std::uint64_t>(data,136);s.beat=read<std::uint64_t>(data,144);s.shake_at=read<std::uint64_t>(data,152);
    if(!s.sequence||(s.sequence&1)||!s.pid||!s.host_pid||!s.epoch||!s.anchor||!s.millis||(s.flags&~3u))return false;
    if(!unit(s.amount)||!unit(s.warp)||!unit(s.shake)||!unit(s.dread)||!(s.radius>=0&&s.radius<=everywhere))return false;
    if(read<std::uint32_t>(data,124))return false;
    for(std::size_t i=160;i<bytes;++i)if(data[i])return false;
    out=s;return true;
}
} // namespace eldencraft::nether
