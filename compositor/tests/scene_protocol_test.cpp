#include "scene_protocol.hpp"
#include <iostream>
#include <stdexcept>
#include <limits>
using namespace eldencraft::frames;
template<class T,std::size_t N>void put(std::array<std::uint8_t,N>& bytes,std::size_t offset,T value){std::memcpy(bytes.data()+offset,&value,sizeof(value));}
unsigned checks=0;
void check(bool value,const char *message){++checks;if(!value)throw std::runtime_error(message);}
void camera_pacing(const std::array<std::uint8_t,256> &packet){
    // Newest-first history: frames 12, 11, 10 (sequence gaps are allowed).
    std::array<std::uint8_t,256*camera_history> history{};
    for(std::uint64_t i=0;i<3;++i){auto p=packet;put(p,16,std::uint64_t(12-i));std::memcpy(history.data()+i*256,p.data(),256);}
    std::array<HostCamera,camera_history> cameras;
    auto read=[&](std::uint32_t answered){return read_camera_history([&](void *out,std::uint32_t size){
        check(size==history.size(),"history request names its full capacity");std::memcpy(out,history.data(),size);return answered;},
        []{return std::uint64_t(1000);},cameras);};
    check(read(3)==3&&cameras[0].frame==12&&cameras[2].frame==10,"history decoded newest first");
    check(read(1)==1&&cameras[0].frame==12,"an older core answering one packet still works");
    check(read(0)==0&&read(9)==0,"empty or oversized answers rejected");
    {auto saved=history;const std::uint64_t repeat=12;std::memcpy(history.data()+256+16,&repeat,8);
        check(read(3)==1,"history must strictly descend");history=saved;}
    {auto saved=history;const std::uint64_t other=8;std::memcpy(history.data()+512+24,&other,8);
        check(read(3)==2,"another world epoch ends the history");history=saved;}
    read(3);
    check(pick_camera(cameras,3,0).frame==12&&pick_camera(cameras,3,1).frame==11&&pick_camera(cameras,3,2).frame==10,
        "pick by submissions behind the newest");
    check(pick_camera(cameras,3,7).frame==10,"beyond history falls back to the oldest kept");
    check(render_latency==1&&pick_camera(cameras,3,render_latency).frame==11,
        "default draws with the submission before the newest (Elden Ring's presented image)");
    {auto gap=cameras;gap[1].frame=9;gap[2].frame=8;check(pick_camera(gap,3,1).frame==9,"a skipped sequence picks the nearest older one");}

    // In lockstep (one submission per Present) the newest camera is the image's.
    CameraPacer lockstep;std::uint64_t seq=100;
    for(int i=0;i<300;++i)check(lockstep.observe(++seq)==0,"lockstep keeps the newest camera");
    // The game thread runs one frame ahead at some Presents and not at others.
    CameraPacer jitter;seq=500;jitter.observe(seq);
    const int leads[]={0,1,1,0,1,0,0,1,1,1,0,1};int prior=0;
    for(int round=0;round<20;++round)for(int lead:leads){seq+=1+lead-prior;prior=lead;
        check(jitter.observe(seq)==std::uint32_t(lead),"each frame's measured lead matches the pipeline");}
    // A loading stall (Presents without submissions) never leaves a stale lead.
    for(int i=0;i<10;++i)check(jitter.observe(seq)==0,"stalled submissions catch up");
    // Two submissions every Present is not a lead: the newest camera is kept.
    CameraPacer doubled;seq=0;doubled.observe(seq=1000);std::uint32_t last=0;
    for(int i=0;i<200;++i)last=doubled.observe(seq+=2);
    check(last==0&&doubled.rate()>1.4,"double submission rate disables pacing");
    // A lead that never returns to zero is released after two seconds.
    CameraPacer stuck;stuck.observe(seq=2000);stuck.observe(seq+=2);
    for(int i=0;i<121;++i)stuck.observe(++seq);
    check(stuck.observe(++seq)==0,"a lead that never settles is released");
    // Discontinuities reset: a huge jump or a rewind.
    CameraPacer reset;reset.observe(10);reset.observe(12);
    check(reset.observe(100)==0&&reset.observe(50)==0,"jumps and rewinds reset pacing");
}
int main(){
    std::array<std::uint8_t,scene_bytes> bytes{};
    put(bytes,0,0x53464345u);put(bytes,4,1u);put(bytes,8,std::uint64_t(7));put(bytes,16,std::uint64_t(9));put(bytes,24,11u);put(bytes,28,100u);
    const float a=.1f/(1000-.1f),b=.1f*1000/(1000-.1f);
    const std::array<float,16> projection={1,0,0,0,0,2,0,0,0,0,a,b,0,0,-1,0};
    const std::array<float,16> inverse={1,0,0,0,0,.5f,0,0,0,0,0,-1,0,0,1/b,a/b};
    for(int i=0;i<16;++i){put(bytes,64+i*4,projection[i]);put(bytes,128+i*4,inverse[i]);}
    Scene scene;
    check(decode_scene(bytes,scene),"real reversed perspective pair");
    check(scene.epoch==7&&scene.anchor==9&&scene.map==11,"anchor identity");
    check(!decode_scene({bytes.data(),191},scene),"truncated extension");
    for(auto offset:{0,4,8,16,28}){auto bad=bytes;put(bad,offset,0u);check(!decode_scene(bad,scene),"invalid required identity");}
    for(auto offset:{56,216,511}){auto bad=bytes;bad[offset]=1;check(!decode_scene(bad,scene),"reserved bytes");}
    for(auto offset:{192,200,208}){auto bad=bytes;put(bad,offset,std::uint64_t(2));check(!decode_scene(bad,scene),"partial mesh exclusion cannot hide blocks");}
    {auto mesh=bytes;put(mesh,192,std::uint64_t(21));put(mesh,200,std::uint64_t(31));put(mesh,208,std::uint64_t(41));
        check(decode_scene(mesh,scene)&&scene.mesh_revision==21&&scene.atlas_revision==31&&scene.mesh_session==41,
            "captured exclusion stays tied to exact mesh atlas and producer session");}
    {auto bad=bytes;put(bad,64,std::numeric_limits<float>::quiet_NaN());check(!decode_scene(bad,scene),"nonfinite projection");}
    {auto bad=bytes;put(bad,128,0.0f);check(!decode_scene(bad,scene),"mismatched inverse");}
    auto avatar=bytes;for(int i=0;i<16;++i)put(avatar,216+i*4,inverse[i]);put(avatar,280,1u);
    check(decode_scene(avatar,scene)&&scene.avatar_mode==1,"rear avatar retains real pure perspective inverse");
    put(avatar,280,2u);check(decode_scene(avatar,scene)&&scene.avatar_mode==2,"front avatar uses same coherent plane contract");
    check(!decode_scene({avatar.data(),283},scene),"truncated avatar extension rejected");
    for(auto mode:{0u,3u,UINT32_MAX}){auto bad=avatar;put(bad,280,mode);check(!decode_scene(bad,scene),"matrix requires supported active avatar mode");}
    for(auto offset:{216,236,260,272}){auto bad=avatar;put(bad,offset,std::numeric_limits<float>::quiet_NaN());check(!decode_scene(bad,scene),"nonfinite avatar lens or depth rejected");}
    for(auto offset:{216,236,260,272}){auto bad=avatar;put(bad,offset,0.0f);check(!decode_scene(bad,scene),"singular avatar projection rejected");}
    {auto bad=avatar;put(bad,220,1.0f);check(!decode_scene(bad,scene),"world rotation cannot masquerade as pure avatar lens");}
    {auto bad=avatar;put(bad,216,1e7f);check(!decode_scene(bad,scene),"unbounded lens rejected");}
    {auto bad=avatar;put(bad,260,1.0f);check(!decode_scene(bad,scene),"behind-camera depth rejected");}
    {auto bad=avatar;bad[284]=1;check(!decode_scene(bad,scene),"remaining avatar extension reserved");}
    for(float distance:{.1f,1.0f,6.0f,100.0f,1000.0f}){
        const float depth=(b-a*distance)/distance;
        const float w=depth/b+a/b;
        check(std::abs(-1/w+distance)<distance*.0001f,"sampled depth reconstructs metres");
    }
    std::array<std::uint8_t,256> camera{};
    put(camera,0,0x4d414345u);put(camera,4,1u);put(camera,8,std::uint64_t(1000));put(camera,16,std::uint64_t(2));put(camera,24,std::uint64_t(7));put(camera,32,11u);
    put(camera,64,1.0f);put(camera,84,1.0f);put(camera,104,1.0f);
    put(camera,108,1.2f);put(camera,112,16.0f/9);put(camera,116,.1f);put(camera,120,1000.0f);
    HostCamera host;
    check(decode_camera(camera,1100,host),"fresh actual intrinsics");
    check(!decode_camera(camera,1101,host),"expired camera");
    check(!decode_camera(camera,999,host),"future camera");
    {std::uint64_t clock=999;
        check(read_camera([&](void *out,std::uint32_t count){
            check(count==camera.size(),"camera export requests exact packet size");
            clock=1000;std::memcpy(out,camera.data(),camera.size());return true;
        },[&]{return clock;},host),"producer tick during export is validated with post-copy clock");
        clock=999;
        check(!read_camera([&](void *out,std::uint32_t){std::memcpy(out,camera.data(),camera.size());return true;},
            [&]{return clock;},host),"genuinely future packet still rejected without padding");
        check(!read_camera([](void *,std::uint32_t){return false;},[&]{return std::uint64_t(1000);},host),
            "unavailable native export does not use previous camera");}
    for(auto offset:{0,4,16,24}){auto bad=camera;put(bad,offset,0u);check(!decode_camera(bad,1000,host),"invalid camera identity");}
    for(auto offset:{76,92,124,128,255}){auto bad=camera;bad[offset]=1;check(!decode_camera(bad,1000,host),"reserved camera bytes");}
    {auto bad=camera;put(bad,64,2.0f);check(!decode_camera(bad,1000,host),"nonunit basis");}
    {auto bad=camera;put(bad,96,1.0f);check(!decode_camera(bad,1000,host),"nonorthogonal basis");}
    for(auto offset:{108,112,116,120}){auto bad=camera;put(bad,offset,std::numeric_limits<float>::infinity());check(!decode_camera(bad,1000,host),"nonfinite lens");}
    {auto bad=camera;put(bad,120,.01f);check(!decode_camera(bad,1000,host),"inverted camera planes");}
    camera_pacing(camera);
    std::cout<<"Scene protocol: "<<checks<<" checks passed; GPU depth selection remains a live check.\n";
}
