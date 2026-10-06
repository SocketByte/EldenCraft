#pragma once
#include "nether_protocol.hpp"
#include <windows.h>
#include <atomic>

namespace eldencraft::nether {
// Read-only ECNH consumer. A dead producer's mapping is released so a new Minecraft can own the name.
class Reader {
    HANDLE mapping_{},process_{};const std::uint8_t *view_{};std::uint32_t pid_{};std::uint64_t retry_{};
    static std::uint64_t sequence(const std::uint8_t *p){
        const auto value=*reinterpret_cast<const volatile std::uint64_t*>(p+8);
        std::atomic_thread_fence(std::memory_order_seq_cst);return value;
    }
public:
    State state{};bool valid{};
    ~Reader(){close();}
    void close(){
        if(view_)UnmapViewOfFile(view_);if(mapping_)CloseHandle(mapping_);if(process_)CloseHandle(process_);
        view_=nullptr;mapping_=process_=nullptr;pid_=0;valid=false;state={};
    }
    // True while a fresh, coherent page from a live producer names this host process.
    bool poll(std::uint64_t now,std::uint32_t host){
        if(process_&&WaitForSingleObject(process_,0)!=WAIT_TIMEOUT){close();return false;}
        if(!view_){
            if(now<retry_)return false;retry_=now+1000;
            mapping_=OpenFileMappingW(FILE_MAP_READ,FALSE,mapping_name);if(!mapping_)return false;
            view_=static_cast<const std::uint8_t*>(MapViewOfFile(mapping_,FILE_MAP_READ,0,0,bytes));
            if(!view_){close();return false;}
        }
        // A torn read keeps the previous coherent page; its own deadline still applies.
        for(int attempt=0;attempt<3;++attempt){
            const auto before=sequence(view_);if(!before||(before&1))continue;
            std::array<std::uint8_t,bytes> copy{};std::memcpy(copy.data(),view_,copy.size());
            if(sequence(view_)!=before)continue;
            State next;if(!decode(copy,next)||next.sequence!=before){valid=false;break;}
            if(next.pid!=pid_){if(process_)CloseHandle(process_);process_=OpenProcess(SYNCHRONIZE,FALSE,next.pid);pid_=next.pid;}
            if(!process_||WaitForSingleObject(process_,0)!=WAIT_TIMEOUT){close();return false;}
            state=next;valid=true;break;
        }
        // Sample the clock after copying: a heartbeat written during the copy is not from the future.
        return valid&&state.fresh(GetTickCount64(),host);
    }
};
} // namespace eldencraft::nether
