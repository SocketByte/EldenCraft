#include "block_residency.hpp"
#include <array>
#include <iostream>
#include <stdexcept>
using namespace eldencraft::blocks;
unsigned checks=0;
void check(bool condition,const char *message){++checks;if(!condition)throw std::runtime_error(message);}
Header mesh(std::uint64_t revision){
    Header h{};h.pid=10;h.host_pid=20;h.epoch=30;h.map=40;h.session=50;h.anchor=60;
    h.revision=revision;h.atlas_revision=70;h.magic=mesh_magic;h.flags=1;h.count=3;h.bytes=72;h.stride=24;h.solid=3;return h;
}
eldencraft::frames::Scene scene(const Header &h){
    eldencraft::frames::Scene s{};s.pid=h.host_pid;s.epoch=h.epoch;s.map=h.map;s.anchor=h.anchor;
    s.mesh_session=h.session;s.mesh_revision=h.revision;s.atlas_revision=h.atlas_revision;return s;
}
int main(){
    auto a=mesh(1),b=mesh(2),c=mesh(3);auto captured=scene(a);
    check(scene_mesh(a,captured)&&!scene_mesh(b,captured),"exact captured mesh only");
    auto atlas=a;atlas.magic=atlas_magic;atlas.revision=a.atlas_revision;
    check(scene_atlas(atlas,captured),"capture pins its separate atlas revision");
    ++atlas.revision;check(!scene_atlas(atlas,captured),"candidate atlas cannot replace captured atlas");
    for(int field=0;field<5;++field){auto changed=captured;
        switch(field){case 0:++changed.pid;break;case 1:++changed.epoch;break;case 2:++changed.map;break;case 3:++changed.anchor;break;case 4:++changed.mesh_session;break;}
        check(!scene_mesh(a,changed),"captured exclusion belongs to exact host context");}
    auto foreign=a;++foreign.pid;
    check(!foreign.same_context(a),"retained resource cannot cross Minecraft producer PID even if scene IDs agree");
    check(!waits_for_captured_resident(b,&a,captured,100,0),"one candidate may follow current captured resident");
    check(waits_for_captured_resident(c,&b,captured,100,0),"third revision waits for acknowledged intermediate capture");
    check(!waits_for_captured_resident(b,&b,captured,100,0),"heartbeat of same prepared pair does not block render");
    check(!waits_for_captured_resident(c,&b,scene(b),100,0),"capture promotion releases next candidate");
    auto bootstrap=captured;bootstrap.mesh_revision=bootstrap.atlas_revision=bootstrap.mesh_session=0;
    check(waits_for_captured_resident(c,&b,bootstrap,499,0),"startup readback keeps first future resident");
    check(!waits_for_captured_resident(c,&b,bootstrap,500,0),"explicit RGB-D fallback can recover after bounded timeout");
    check(waits_for_captured_resident(c,&b,bootstrap,9,10),"clock reversal never bypasses pacing");
    auto next_session=c;++next_session.session;
    check(!waits_for_captured_resident(next_session,&b,captured,100,0),"new session reboots without old ACK deadlock");
    check(!waits_for_captured_resident(b,nullptr,bootstrap,100,0),"first upload bootstraps without ACK");
    auto empty=c;empty.count=empty.bytes=empty.solid=0;
    check(scene_mesh(empty,scene(empty)),"empty final mesh is a valid captured clear");
    std::array<Residency,3> slots{{{true,true,1,1},{true,true,2,2},{true,false,3,3}}};
    check(!replacement_slot(slots,2),"busy candidate waits without evicting captured or acknowledged resident");
    check(replacement_slot(slots,3)==2,"completed spare can be reused");
    slots[2].pinned=true;check(!replacement_slot(slots,99),"GPU completion does not override logical residency");
    slots={{{true,false,1,1},{false,false,0,0},{true,false,2,2}}};
    check(replacement_slot(slots,3)==1,"unused allocation preferred over historical geometry");
    slots[1]={true,false,1,4};check(replacement_slot(slots,3)==0,"oldest completed unpinned revision is evicted");
    for(std::uint64_t revision=3;revision<30;++revision){
        a=mesh(revision-2);b=mesh(revision-1);c=mesh(revision);
        check(waits_for_captured_resident(c,&b,scene(a),100,0),"burst cannot outrun asynchronous resident capture");
        check(!waits_for_captured_resident(c,&b,scene(b),100,0),"each captured advance permits bounded progress");
    }
    std::cout<<"Block residency: "<<checks<<" checks passed.\n";
}
