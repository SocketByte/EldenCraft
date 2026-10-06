#include "mapping_reader.hpp"
#include <iostream>
#include <stdexcept>
#include <string>
using namespace eldencraft::frames;
unsigned checks=0;
void check(bool condition,const char *message){++checks;if(!condition)throw std::runtime_error(message);}
// Exercises the real Win32 mapping reader against an isolated producer name;
// never opens Local\EldenCraftFrame, so a running Minecraft is unaffected.
int main(){try{
    const auto pid=GetCurrentProcessId();const auto name=L"Local\\EldenCraftFrameTest-"+std::to_wstring(pid);
    const std::uint64_t size=header_bytes+slot_stride*slots;
    const auto mapping=CreateFileMappingW(INVALID_HANDLE_VALUE,nullptr,PAGE_READWRITE,DWORD(size>>32),DWORD(size),name.c_str());
    check(mapping!=nullptr,"create isolated frame mapping");
    auto *data=static_cast<std::uint8_t*>(MapViewOfFile(mapping,FILE_MAP_WRITE,0,0,size));
    check(data!=nullptr,"map isolated frame writer");
    auto put=[&](std::size_t offset,auto value){std::memcpy(data+offset,&value,sizeof(value));};
    put(0,magic);put(4,version);put(8,std::uint32_t(header_bytes));put(12,slots);put(16,slot_stride);
    put(24,max_width);put(28,max_height);put(40,std::int32_t(0));put(44,pid);
    constexpr std::uint32_t width=4,height=2;constexpr std::size_t layer=width*height*4;
    const std::size_t desc=descriptor_offset,base=header_bytes;
    auto publish=[&](std::uint64_t sequence,std::uint64_t publication,std::uint8_t fill){
        put(desc,sequence);put(desc+24,width);put(desc+28,height);put(desc+44,std::uint32_t(15));
        for(std::size_t i=0;i<layer;++i)data[base+layer*2+i]=static_cast<std::uint8_t>(fill+i);
        put(32,publication);
    };
    publish(2,1,10);
    MappingReader reader(name.c_str());Frame frame;
    check(reader.acquire(1000),"coherent publication acquired without copying");
    check(reader.descriptor().width==width&&reader.descriptor().height==height,"descriptor exposed before commit");
    check(reader.plane(2)&&reader.plane(2)[0]==10&&reader.plane(2)[layer-1]==static_cast<std::uint8_t>(10+layer-1),"overlay plane read in place");
    check(reader.plane(0)!=nullptr&&reader.plane(3)==nullptr,"three-plane slot never exposes avatar planes");
    check(!reader.fresh(1000),"acquire alone does not establish freshness");
    check(reader.commit(frame,1000)&&frame.publication==1,"intact copy commits publication");
    check(reader.fresh(1500),"committed publication is fresh");
    check(!reader.acquire(1100),"same publication is not uploaded twice");
    publish(4,2,20);
    check(reader.acquire(1200),"next publication acquired");
    put(desc,std::uint64_t(6)); // Producer reused the slot during the caller's copy.
    check(!reader.commit(frame,1200)&&frame.publication==1,"torn copy is discarded");
    check(reader.plane(2)==nullptr,"discarded copy releases plane access");
    check(reader.acquire(1300)&&reader.commit(frame,1300)&&frame.publication==2,"publication retried after torn copy");
    publish(8,3,30);put(32,std::uint64_t(4));
    check(reader.acquire(1400),"publication counter ahead of header is read coherently");
    put(32,std::uint64_t(5));
    check(!reader.commit(frame,1400),"publication change during copy is discarded");
    put(40,std::int32_t(-1));put(32,std::uint64_t(6));
    check(!reader.acquire(1500)&&!reader.fresh(1500),"explicit invalidation hides the frame");
    put(40,std::int32_t(0));publish(10,7,40);
    check(reader.poll(frame,1600)&&frame.overlay.size()==layer&&frame.overlay[0]==40,"copy-mode poll still owns private pixels");
    UnmapViewOfFile(data);CloseHandle(mapping);
    std::cout<<"Frame mapping checks passed: "<<checks<<"\n";return 0;
}catch(const std::exception &error){std::cerr<<error.what()<<"\n";return 1;}}
