#include "gpu_transport.hpp"
#include <array>
#include <iostream>
#include <stdexcept>
using namespace eldencraft::gpu;
template<class T> void put(std::array<std::uint8_t, mapping_bytes> &a, std::size_t offset, T v) { std::memcpy(a.data() + offset, &v, sizeof(v)); }
void check(bool value, const char *message) { if (!value) throw std::runtime_error(message); }
int main() {
    std::array<std::uint8_t, mapping_bytes> page{};
    Request request;
    check(!decode_request(page, request), "empty page has no request");
    put(page, request_offset, request_magic); put(page, request_offset + 4, std::uint32_t(1656));
    put(page, request_offset + 8, std::uint32_t(979)); put(page, request_offset + 12, std::uint32_t(4242));
    check(decode_request(page, request) && request.width == 1656 && request.height == 979 && request.pid == 4242, "valid guest request");
    check(request.plane_mask==all_planes,"legacy requests keep all full-size planes");
    put(page,request_offset+24,base_planes);
    check(decode_request(page,request)&&request.plane_mask==base_planes,"first person requests only world/depth/overlay");
    put(page,request_offset+24,all_planes);
    check(decode_request(page,request)&&request.plane_mask==all_planes,"F5 requests both avatar planes");
    auto invalid=page;put(invalid,request_offset+24,std::uint32_t(8));
    check(!decode_request(invalid,request),"partial/unknown plane layouts rejected");
    check(!decode_request({page.data(), 512}, request), "truncated page");
    auto bad = page; put(bad, request_offset + 4, max_width + 1); check(!decode_request(bad, request), "oversized width");
    bad = page; put(bad, request_offset + 4, std::uint32_t(3840)); put(bad, request_offset + 8, std::uint32_t(2160));
    check(decode_request(bad, request), "4K request");
    bad = page; put(bad, request_offset + 8, std::uint32_t(0)); check(!decode_request(bad, request), "zero height");
    bad = page; put(bad, request_offset + 12, std::uint32_t(0)); check(!decode_request(bad, request), "missing guest pid");
    bad = page; put(bad, request_offset, magic); check(!decode_request(bad, request), "host magic is not a request");
    put(page, request_offset + 16, status_failed); put(page, request_offset + 20, std::uint32_t(7));
    check(decode_request(page, request) && request.status == status_failed && request.status_generation == 7, "import status");
    check(set_reusable(0, 0), "never-written set is free");
    check(!set_reusable(10, 9), "unacknowledged set is busy");
    check(set_reusable(10, 10) && set_reusable(10, 12), "copied or superseded set is free");
    CopyAcks copies;std::uint64_t ack=0;
    auto copied=[&](std::uint32_t,std::uint64_t frame){ack=std::max(ack,frame);};
    std::array<std::uint64_t,sets> written={1,2,3};
    check(std::none_of(written.begin(),written.end(),[&](auto frame){return set_reusable(frame,ack);}),"paused host fills all three sets");
    check(!copies.discard_through(3,2),"producer fence must complete before a paused host releases a publication");
    copies.note(0,1,100);copies.note(1,2,101);
    copies.retire(100,copied);
    check(ack==1&&!copies.discard_through(3,50),"discard cannot release newer sets while an older host copy is in flight");
    copies.retire(UINT64_MAX,copied);
    check(ack==1&&!copies.discard_through(3,UINT64_MAX),"device removal is not completed GPU work");
    copies.retire(101,copied);ack=std::max(ack,copies.discard_through(3,50));
    check(ack==3,"discard only acknowledges the consumed publication, never future unpublished writes");
    for(std::uint64_t frame=4;frame<=600;++frame){
        const auto free=std::find_if(written.begin(),written.end(),[&](auto f){return set_reusable(f,ack);});
        check(free!=written.end(),"gated composition must keep producing without CPU readback or resize");
        *free=frame;ack=std::max(ack,copies.discard_through(frame,frame));
    }
    copies.note(2,601,102);copies.retire(101,copied);
    check(ack==600&&!copies.discard_through(602,602),"resumed composition still protects its new copy");
    copies.retire(102,copied);check(ack==601,"resumed copy releases its set after completion");
    check(texture_name(12, 34, 2, 4) == L"Local\\EldenCraftGpu_12_34_2_4", "texture name");
    check(fence_name(12, 34) == L"Local\\EldenCraftGpu_12_34_ready", "fence name");
    check(depth_plane(1) && depth_plane(4) && !depth_plane(0) && !depth_plane(2) && !depth_plane(3), "plane formats");
    std::cout << "gpu transport: request bounds, set reuse and object names passed\n";
}
