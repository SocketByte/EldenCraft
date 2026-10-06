// ECNH v1 decoding: the exact layout NetherProtocol.java writes, and every rejection.
#include "nether_protocol.hpp"
#include <cstdio>
#include <cstring>
#include <limits>

namespace {
int failures=0,checks=0;
void check(bool value,const char *message){++checks;if(!value){++failures;std::printf("FAIL: %s\n",message);}}
template<class T> void put(std::array<std::uint8_t,256> &b,std::size_t at,T value){std::memcpy(b.data()+at,&value,sizeof(T));}
std::array<std::uint8_t,256> page() {
    std::array<std::uint8_t,256> b{};
    put(b,0,std::uint32_t(0x484e4345));put(b,4,std::uint32_t(1));put(b,8,std::uint64_t(42));
    put(b,16,std::uint32_t(1234));put(b,20,std::uint32_t(5678));put(b,24,std::uint64_t(9));put(b,32,std::uint32_t(0x3c272800));
    put(b,36,std::uint32_t(3));put(b,40,std::uint64_t(1000));put(b,48,std::uint64_t(3));
    put(b,56,1024.25);put(b,64,64.0);put(b,72,-512.5);put(b,80,1025.75);put(b,88,64.0);put(b,96,-511.0);
    put(b,104,.5f);put(b,108,42.0f);put(b,112,.25f);put(b,116,.75f);put(b,120,1.0f);
    put(b,128,std::uint64_t(900));put(b,136,std::uint64_t(950));put(b,144,std::uint64_t(990));put(b,152,std::uint64_t(0));
    return b;
}
bool decodes(const std::array<std::uint8_t,256> &b){eldencraft::nether::State s;return eldencraft::nether::decode(b,s);}
}
int main() {
    using namespace eldencraft::nether;
    State s;auto b=page();
    check(decode(b,s),"valid page decodes");
    check(s.pid==1234&&s.host_pid==5678&&s.epoch==9&&s.map==0x3c272800&&s.anchor==3&&s.millis==1000,"identity");
    check(s.active()&&s.centred(),"flags");
    check(s.grid[0]==1024.25&&s.grid[2]==-512.5&&s.center[0]==1025.75&&s.center[2]==-511.0,"positions");
    check(s.amount==.5f&&s.radius==42.0f&&s.warp==.25f&&s.shake==.75f&&s.dread==1.0f,"factors");
    check(s.fresh(1000,5678)&&s.fresh(1500,5678),"fresh within 500 ms for its host");
    check(!s.fresh(1501,5678),"stale after 500 ms");
    check(!s.fresh(999,5678),"heartbeat from the future rejected");
    check(!s.fresh(1000,1),"another host process rejected");
    check(s.age(900,1400)==.5f&&s.age(0,1400)<0&&s.age(1500,1400)<0,"event ages; never and future are negative");
    auto bad=[&](auto mutate,const char *what){auto c=page();mutate(c);check(!decodes(c),what);};
    bad([](auto &c){put(c,0,std::uint32_t(0));},"magic");
    bad([](auto &c){put(c,4,std::uint32_t(2));},"version");
    bad([](auto &c){put(c,8,std::uint64_t(43));},"odd (in-progress) sequence");
    bad([](auto &c){put(c,8,std::uint64_t(0));},"unpublished sequence");
    bad([](auto &c){put(c,16,std::uint32_t(0));},"producer pid");
    bad([](auto &c){put(c,20,std::uint32_t(0));},"host pid");
    bad([](auto &c){put(c,24,std::uint64_t(0));},"epoch");
    bad([](auto &c){put(c,48,std::uint64_t(0));},"anchor");
    bad([](auto &c){put(c,40,std::uint64_t(0));},"heartbeat");
    bad([](auto &c){put(c,36,std::uint32_t(4));},"unknown flag");
    bad([](auto &c){put(c,104,1.5f);},"amount above one");
    bad([](auto &c){put(c,112,-.1f);},"negative warp");
    bad([](auto &c){put(c,120,std::numeric_limits<float>::quiet_NaN());},"NaN dread");
    bad([](auto &c){put(c,108,-1.0f);},"negative radius");
    bad([](auto &c){put(c,108,200000.0f);},"radius beyond everywhere");
    bad([](auto &c){put(c,56,std::numeric_limits<double>::infinity());},"infinite grid");
    bad([](auto &c){put(c,88,4e7);},"centre out of range");
    bad([](auto &c){put(c,124,std::uint32_t(1));},"reserved word");
    bad([](auto &c){c[255]=1;},"reserved tail");
    std::array<std::uint8_t,100> shortPage{};check(!decode(shortPage,s),"short page");
    std::printf("Nether protocol: %d checks, %d failures.\n",checks,failures);
    return failures?1:0;
}
