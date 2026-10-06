#include "frame_protocol.hpp"
#include <array>
#include <iostream>
#include <stdexcept>
using namespace eldencraft::frames;
template<class T, std::size_t N> void put(std::array<std::uint8_t, N>& a, std::size_t offset, T v) { std::memcpy(a.data()+offset,&v,sizeof(v)); }
void check(bool value, const char *message) { if (!value) throw std::runtime_error(message); }
int main() {
    std::array<std::uint8_t, header_bytes> header{};
    put(header,0,magic); put(header,4,version); put(header,8,std::uint32_t(header_bytes)); put(header,12,slots);
    put(header,16,slot_stride); put(header,24,max_width); put(header,28,max_height);
    put(header,32,std::uint64_t(12)); put(header,40,std::int32_t(2)); put(header,44,std::uint32_t(100));
    Header parsed; check(decode_header(header,parsed),"valid header");
    check(parsed.publication==12 && parsed.latest==2 && parsed.pid==100,"header metadata");
    check(!decode_header({header.data(),64},parsed),"truncated header");
    for (const auto offset : {0,4,8,12,24,28,44}) { auto bad=header; put(bad,offset,std::uint32_t(0)); check(!decode_header(bad,parsed),"invalid header scalar"); }
    auto bad=header; put(bad,16,UINT64_MAX); check(!decode_header(bad,parsed),"overflow stride");
    for (auto index : {-2,3,INT32_MAX}) { bad=header; put(bad,40,index); check(!decode_header(bad,parsed),"invalid slot"); }
    std::array<std::uint8_t,descriptor_bytes> desc{};
    put(desc,0,std::uint64_t(2)); put(desc,24,max_width); put(desc,28,max_height); put(desc,44,std::uint32_t(7));
    Descriptor d; check(decode_descriptor(desc,d),"maximum frame"); check(d.layer_bytes==8294400,"layer size");
    check(!decode_descriptor({desc.data(),64},d),"truncated descriptor");
    for (auto sequence : {std::uint64_t(0),std::uint64_t(3)}) { auto b=desc; put(b,0,sequence); check(!decode_descriptor(b,d),"unpublished/writing frame"); }
    for (auto width : {std::uint32_t(0),max_width+1,UINT32_MAX}) { auto b=desc; put(b,24,width); check(!decode_descriptor(b,d),"invalid width"); }
    for (auto height : {std::uint32_t(0),max_height+1,UINT32_MAX}) { auto b=desc; put(b,28,height); check(!decode_descriptor(b,d),"invalid height"); }
    auto b=desc; put(b,44,std::uint32_t(15)); check(decode_descriptor(b,d),"overlay-only flags");
    put(b,44,std::uint32_t(23)); check(decode_descriptor(b,d),"scene depth flags");
    put(b,44,std::uint32_t(24)); check(!decode_descriptor(b,d),"scene cannot omit world planes");
    put(b,44,std::uint32_t(32)); check(!decode_descriptor(b,d),"avatar requires coherent scene");
    put(b,44,std::uint32_t(55));
    check(!decode_descriptor(b,d,slot_stride),"legacy allocation cannot authorize extra avatar planes");
    check(decode_descriptor(b,d,avatar_slot_stride),"maximum coherent five-plane frame");
    check(payload_fits(d,avatar_slot_stride),"exact maximum avatar payload fits");
    check(!payload_fits(d,avatar_slot_stride-1),"truncated avatar depth rejected");
    check(!payload_fits(d,slot_stride),"missing both avatar planes rejected");
    check(!payload_fits(d,slot_stride+d.layer_bytes),"avatar color without depth rejected");
    put(b,44,std::uint32_t(63));check(!decode_descriptor(b,d,avatar_slot_stride),"avatar cannot be overlay-only");
    put(b,44,std::uint32_t(64));check(!decode_descriptor(b,d,avatar_slot_stride),"GPU-shared frame requires a texture generation");
    put(b,8,std::uint64_t(9));put(b,112,std::uint32_t(77));put(b,116,std::uint32_t(2));put(b,44,std::uint32_t(16+32+64+2+1));
    check(decode_descriptor(b,d,avatar_slot_stride)&&d.gpu_generation==77&&d.gpu_set==2,"GPU-shared scene/avatar metadata");
    put(b,24,std::uint32_t(2560));put(b,28,std::uint32_t(1440));
    check(decode_descriptor(b,d,avatar_slot_stride)&&d.width==2560,"GPU frame at host resolution");
    put(b,24,gpu_max_width+1);check(!decode_descriptor(b,d,avatar_slot_stride),"GPU frame beyond 4K");
    put(b,24,max_width);put(b,28,max_height);
    put(b,116,std::uint32_t(3));check(!decode_descriptor(b,d,avatar_slot_stride),"GPU texture set out of range");
    put(b,116,std::uint32_t(1));put(b,44,std::uint32_t(16+32+2+1));
    check(!decode_descriptor(b,d,avatar_slot_stride),"CPU frame cannot name a GPU texture set");
    put(b,112,std::uint32_t(0));put(b,116,std::uint32_t(0));put(b,24,std::uint32_t(2560));
    check(!decode_descriptor(b,d,avatar_slot_stride),"CPU frames stay within the shared-memory planes");
    put(b,24,max_width);
    put(b,112,std::uint32_t(0));put(b,116,std::uint32_t(0));
    put(b,44,std::uint32_t(128+15));check(!decode_descriptor(b,d,avatar_slot_stride),"unknown extended flag");
    put(b,44,std::uint32_t(15));check(decode_descriptor(b,d,avatar_slot_stride),"new allocation still carries ordinary first-person overlay");
    auto expanded=header;put(expanded,16,avatar_slot_stride);
    check(decode_header(expanded,parsed)&&parsed.stride==avatar_slot_stride,"self describing five-plane allocation");
    put(expanded,16,avatar_slot_stride-1);check(!decode_header(expanded,parsed),"truncated declared allocation rejected");
    put(desc,88,std::uint64_t(100)); put(desc,96,std::uint64_t(200));
    check(decode_descriptor(desc,d) && d.publish_ns-d.capture_ns==100,"completion telemetry");
    Freshness freshness;
    check(!freshness.fresh(100),"no frame");
    check(freshness.observe(1,100) && freshness.fresh(2099),"fresh frame");
    check(!freshness.observe(1,2099) && !freshness.fresh(2100),"duplicates never renew freshness");
    check(!freshness.observe(0,2100),"rollback rejected");
    check(freshness.observe(2,2200),"advancing frame renews");
    freshness.invalidate(); check(!freshness.fresh(2201),"world exit invalidates immediately");
    check(!freshness.observe(2,2201),"invalidated frame cannot revive");
    check(freshness.observe(3,2300) && !freshness.fresh(2299),"clock rollback invalid");
    freshness.reset(); check(freshness.observe(1,2400),"new publisher epoch");
    std::cout << "frame protocol: bounds, metadata, dimensions and publication guards passed\n";
}
