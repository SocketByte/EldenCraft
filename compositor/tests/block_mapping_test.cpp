#include "block_mapping.hpp"
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
using namespace eldencraft::blocks;
unsigned checks=0;
std::uint64_t observed_clock=1000;
void check(bool condition,const char *message){++checks;if(!condition)throw std::runtime_error(message);}
int main(){try{
    const auto pid=GetCurrentProcessId();const auto name=L"Local\\EldenCraftBlockMappingTest-"+std::to_wstring(pid);
    const auto mapping=CreateFileMappingW(INVALID_HANDLE_VALUE,nullptr,PAGE_READWRITE,0,256,name.c_str());
    check(mapping!=nullptr,"create isolated test mapping");
    auto *data=static_cast<std::uint8_t*>(MapViewOfFile(mapping,FILE_MAP_WRITE,0,0,256));
    check(data!=nullptr,"map isolated test writer");
    auto put=[&](std::size_t offset,auto value){std::memcpy(data+offset,&value,sizeof(value));};
    put(0,mesh_magic);put(4,1u);put(8,std::uint64_t(2));put(16,pid);put(20,pid);put(24,std::uint64_t(1));
    put(32,1u);put(36,1u);put(40,std::uint64_t(1));put(48,std::uint64_t(1000));
    put(56,std::uint64_t(1));put(64,std::uint64_t(1));put(76,24u);put(96,std::uint64_t(1));
    Mailbox reader([]{return observed_clock;});
    check(reader.poll(name.c_str(),256,mesh_magic,1000,pid),"coherent empty revision accepted");
    check(reader.retained(1000,pid),"accepted revision retained");
    put(8,std::uint64_t(3));
    observed_clock=1050;
    check(!reader.poll(name.c_str(),256,mesh_magic,1050,pid),"in-progress publication is not accepted");
    check(reader.retained(1050,pid),"transient seqlock read retains original coherent payload");
    check(!reader.retained(1501,pid),"odd writer cannot extend previous deadline");
    put(36,0u);put(8,std::uint64_t(4));
    observed_clock=1100;
    check(!reader.poll(name.c_str(),256,mesh_magic,1100,pid),"explicit inactive is not renderable");
    check(!reader.retained(1100,pid),"explicit inactive revokes cached active state immediately");
    put(36,1u);put(48,std::uint64_t(1200));put(8,std::uint64_t(6));
    observed_clock=1200;
    check(reader.poll(name.c_str(),256,mesh_magic,1200,pid),"active producer can resume same immutable payload");
    check(reader.retained(1200,pid),"resumed coherent publication retains");
    check(!reader.retained(1200,pid+1),"another host cannot use retained payload");
    put(48,std::uint64_t(1301));put(8,std::uint64_t(8));observed_clock=1301;
    check(reader.poll(name.c_str(),256,mesh_magic,1300,pid),"producer tick after pre-read timestamp stays valid");
    check(reader.retained(observed_clock,pid),"post-copy observer retains advancing heartbeat");
    put(48,std::uint64_t(1401));put(8,std::uint64_t(10));observed_clock=1400;
    check(!reader.poll(name.c_str(),256,mesh_magic,1400,pid),"genuinely future heartbeat rejected without skew allowance");
    check(!reader.retained(observed_clock,pid),"future heartbeat cannot authorize cached content");
    put(48,std::uint64_t(1450));put(8,std::uint64_t(12));observed_clock=1450;
    check(reader.poll(name.c_str(),256,mesh_magic,1450,pid),"valid heartbeat can recover after invalid clock");
    put(56,std::uint64_t(2));put(72,3u);put(80,72u);put(84,3u);
    put(128,std::numeric_limits<float>::quiet_NaN());put(8,std::uint64_t(14));
    check(!reader.poll(name.c_str(),256,mesh_magic,1450,pid),"coherent invalid vertex revision rejected");
    check(!reader.retained(1450,pid),"invalid payload revokes prior accepted mesh");
    reader.close();check(!reader.retained(1450,pid),"close revokes all retained state");
    UnmapViewOfFile(data);CloseHandle(mapping);
    std::cout<<"Block mapping: "<<checks<<" checks passed.\n";
}catch(const std::exception &error){std::cerr<<error.what()<<'\n';return 1;}}
