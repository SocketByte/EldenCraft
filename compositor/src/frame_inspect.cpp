#include "mapping_reader.hpp"
#include <iostream>
#include <cstdlib>
#include <algorithm>
#include <string_view>
#include <vector>
int main(int argc, char **argv) {
    const bool metadata=argc>1 && std::string_view(argv[1])=="--metadata";
    const int argument=metadata?2:1;
    const unsigned seconds = argc > argument ? static_cast<unsigned>(std::clamp(std::atoi(argv[argument]), 1, 60)) : 10;
    eldencraft::frames::MappingReader reader; eldencraft::frames::Frame frame;
    const auto started=GetTickCount64(), deadline=started+seconds*1000; unsigned received=0;
    std::vector<double> completion_ms;
    while (GetTickCount64()<deadline) {
        if (reader.poll(frame,GetTickCount64(),!metadata)) {
            ++received;
            const auto &d=frame.descriptor;
            if(d.publish_ns>=d.capture_ns && d.publish_ns-d.capture_ns<10'000'000'000ull)
                completion_ms.push_back(static_cast<double>(d.publish_ns-d.capture_ns)/1e6);
            if (!metadata && (received==1 || received%60==0)) {
                std::size_t visible=0, partial=0;
                for(std::size_t i=3;i<frame.overlay.size();i+=4) { visible+=frame.overlay[i]!=0; partial+=frame.overlay[i]!=0 && frame.overlay[i]!=255; }
                std::cout << "publication=" << frame.publication << " size=" << frame.descriptor.width << 'x' << frame.descriptor.height
                    << " flags=" << frame.descriptor.flags << " visible_alpha_pixels=" << visible << " partial_alpha_pixels=" << partial << '\n';
            }
        }
        Sleep(5);
    }
    const auto elapsed=GetTickCount64()-started;
    std::sort(completion_ms.begin(),completion_ms.end());
    std::cout << "mode=" << (metadata?"metadata-only":"pixel-copy") << " stable_frames=" << received
        << " seconds=" << static_cast<double>(elapsed)/1000 << " fps=" << (elapsed?received*1000.0/elapsed:0)
        << " size=" << frame.descriptor.width << 'x' << frame.descriptor.height << " flags=" << frame.descriptor.flags;
    if(!completion_ms.empty()) std::cout << " completion_median_ms=" << completion_ms[completion_ms.size()/2]
        << " completion_p95_ms=" << completion_ms[std::min(completion_ms.size()-1,completion_ms.size()*95/100)];
    std::cout << '\n'; return received ? 0 : 2;
}
