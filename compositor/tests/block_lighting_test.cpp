#include "block_shader.hpp"
#include "gpu_test.hpp"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <random>

int main(){try{
    using namespace gpu_test;Gpu gpu;
    std::mt19937 rng(711);std::array<std::uint8_t,1024> map{};
    for(unsigned i=0;i<256;++i){for(unsigned c=0;c<3;++c)map[i*4+c]=static_cast<std::uint8_t>(rng());map[i*4+3]=255;}
    auto light=gpu.texture(16,16,DXGI_FORMAT_R8G8B8A8_UNORM,D3D11_BIND_SHADER_RESOURCE,map.data(),64);auto light_view=gpu.srv(light.Get());
    std::vector<std::array<std::uint32_t,2>> cases;
    for(unsigned block:{0u,7u,16u,63u,128u,240u,255u,65535u})for(unsigned sky:{0u,8u,16u,128u,240u,65535u})
        for(unsigned i=0;i<8;++i)cases.push_back({rng(),block|(sky<<16)});
    const std::string source=std::string(eldencraft::blocks::block_shader)+R"hlsl(
StructuredBuffer<uint2> Cases:register(t3);RWStructuredBuffer<uint> Results:register(u0);
[numthreads(1,1,1)]void Test(uint3 id:SV_DispatchThreadID){
    uint2 c=Cases[id.x];float4 input=float4(c.x&255,(c.x>>8)&255,(c.x>>16)&255,c.x>>24)/255.0;
    uint4 lit=uint4(floor(LitTint(input,c.y)*255+.5));
    Results[id.x]=lit.x|(lit.y<<8)|(lit.z<<16)|(lit.w<<24);
})hlsl";
    auto shader=gpu.compile(source,"Test");auto input=gpu.buffer<std::array<std::uint32_t,2>>(cases,D3D11_BIND_SHADER_RESOURCE);
    auto input_view=gpu.srv(input.Get());std::vector<std::uint32_t> empty(cases.size());auto output=gpu.buffer<std::uint32_t>(empty,D3D11_BIND_UNORDERED_ACCESS);auto output_view=gpu.uav(output.Get());
    auto *lv=light_view.Get(),*iv=input_view.Get();auto *ov=output_view.Get();gpu.context->CSSetShaderResources(2,1,&lv);gpu.context->CSSetShaderResources(3,1,&iv);
    gpu.context->CSSetUnorderedAccessViews(0,1,&ov,nullptr);gpu.context->CSSetShader(shader.Get(),nullptr,0);gpu.context->Dispatch(static_cast<UINT>(cases.size()),1,1);
    ov=nullptr;gpu.context->CSSetUnorderedAccessViews(0,1,&ov,nullptr);const auto actual=gpu.read_buffer<std::uint32_t>(output.Get());
    unsigned max_error=0;
    for(std::size_t i=0;i<cases.size();++i){const auto [argb,light_uv]=cases[i];
        const float u=std::clamp(float(light_uv&65535)/16,0.f,15.f),v=std::clamp(float(light_uv>>16)/16,0.f,15.f);
        const int x=int(u),y=int(v),x1=std::min(15,x+1),y1=std::min(15,y+1);
        for(unsigned c=0;c<4;++c){const float lo=map[(y*16+x)*4+c]*(1-(u-x))+map[(y*16+x1)*4+c]*(u-x);
            const float hi=map[(y1*16+x)*4+c]*(1-(u-x))+map[(y1*16+x1)*4+c]*(u-x);
            const auto base=(argb>>(c==0?16:c==1?8:c==2?0:24))&255;
            const auto expected=static_cast<unsigned>(std::floor(base*(lo*(1-(v-y))+hi*(v-y))/255.f+.5f));
            const auto got=(actual[i]>>(c*8))&255;const auto error=unsigned(std::abs(int(got)-int(expected)));max_error=std::max(max_error,error);
            if(error>1||c==3&&error){std::cerr<<"lighting case "<<i<<" channel "<<c<<": "<<got<<" expected "<<expected<<'\n';throw std::runtime_error("GPU shading changed vanilla light/tint");}
        }
    }
    std::cout<<"GPU lighting: "<<cases.size()<<" vanilla bilinear/clamping/ARGB/alpha cases passed; maximum channel error="<<max_error<<"/255\n";
    return 0;
}catch(const std::exception &e){std::cerr<<e.what()<<'\n';return 1;}}
