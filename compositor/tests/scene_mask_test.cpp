#include "scene_mask_shader.hpp"
#include "gpu_test.hpp"
#include <cmath>
#include <fstream>
#include <iterator>
#include <limits>
#include <random>

int main(){try{
    using namespace gpu_test;Gpu gpu;
    auto tiles_shader=gpu.compile(eldencraft::frames::scene_mask_shader,"Tiles");
    auto bounds_shader=gpu.compile(eldencraft::frames::scene_mask_shader,"Bounds");
    for(const auto [w,h]:{std::pair<UINT,UINT>{1,1},{17,19},{1656,979},{3840,2160}})for(bool sparse:{false,true}){
        std::vector<float> depths(std::size_t(w)*h);
        if(sparse){
            for(UINT x:{0u,std::min(15u,w-1),std::min(16u,w-1),w-1})
                for(UINT y:{0u,std::min(15u,h-1),std::min(16u,h-1),h-1})depths[std::size_t(y)*w+x]=.25f;
            if(w*h>4){depths[1]=1e-7f;depths[2]=1.1f;depths[3]=std::numeric_limits<float>::quiet_NaN();}
        }
        auto depth=gpu.texture(w,h,DXGI_FORMAT_R32_FLOAT,D3D11_BIND_SHADER_RESOURCE,depths.data(),w*4);auto dv=gpu.srv(depth.Get());
        auto mask=gpu.texture(240,135,DXGI_FORMAT_R32G32B32A32_FLOAT,D3D11_BIND_SHADER_RESOURCE|D3D11_BIND_UNORDERED_ACCESS);
        auto root=gpu.texture(1,1,DXGI_FORMAT_R32G32B32A32_FLOAT,D3D11_BIND_UNORDERED_ACCESS);
        auto mv=gpu.uav(mask.Get()),rv=gpu.uav(root.Get());auto ms=gpu.srv(mask.Get());
        const UINT size[]={w,h,0,0};D3D11_BUFFER_DESC desc{};desc.ByteWidth=16;desc.Usage=D3D11_USAGE_DEFAULT;desc.BindFlags=D3D11_BIND_CONSTANT_BUFFER;
        D3D11_SUBRESOURCE_DATA initial{size,0,0};ComPtr<ID3D11Buffer> cb;checked(gpu.device->CreateBuffer(&desc,&initial,&cb),"create size constants");
        auto *constants=cb.Get();gpu.context->CSSetConstantBuffers(0,1,&constants);
        ID3D11ShaderResourceView *inputs[]={dv.Get(),nullptr};ID3D11UnorderedAccessView *outputs[]={mv.Get(),nullptr};
        gpu.context->CSSetShaderResources(0,2,inputs);gpu.context->CSSetUnorderedAccessViews(0,2,outputs,nullptr);
        gpu.context->CSSetShader(tiles_shader.Get(),nullptr,0);gpu.context->Dispatch((w+15)/16,(h+15)/16,1);
        outputs[0]=nullptr;outputs[1]=rv.Get();gpu.context->CSSetUnorderedAccessViews(0,2,outputs,nullptr);
        inputs[0]=nullptr;inputs[1]=ms.Get();gpu.context->CSSetShaderResources(0,2,inputs);
        gpu.context->CSSetShader(bounds_shader.Get(),nullptr,0);gpu.context->Dispatch(1,1,1);
        inputs[0]=inputs[1]=nullptr;outputs[0]=outputs[1]=nullptr;
        gpu.context->CSSetShaderResources(0,2,inputs);gpu.context->CSSetUnorderedAccessViews(0,2,outputs,nullptr);
        const auto actual=gpu.read_texture(mask.Get()),global=gpu.read_texture(root.Get());
        std::array<float,4> all={float(w),float(h),0,0};
        for(UINT ty=0;ty<(h+15)/16;++ty)for(UINT tx=0;tx<(w+15)/16;++tx){
            std::array<float,4> expected={float(w),float(h),0,0};
            for(UINT y=ty*16;y<std::min(h,(ty+1)*16);++y)for(UINT x=tx*16;x<std::min(w,(tx+1)*16);++x){
                const float d=depths[std::size_t(y)*w+x];if(d<=1e-7f||d>1)continue;
                expected[0]=std::min(expected[0],float(x));expected[1]=std::min(expected[1],float(y));
                expected[2]=std::max(expected[2],float(x+1));expected[3]=std::max(expected[3],float(y+1));
            }
            check(actual[std::size_t(ty)*240+tx]==expected,"tile mask loses a thin/edge texel or admits invalid depth");
            all[0]=std::min(all[0],expected[0]);all[1]=std::min(all[1],expected[1]);all[2]=std::max(all[2],expected[2]);all[3]=std::max(all[3],expected[3]);
        }
        check(global[0]==all,"global union differs from exact occupied texels");
    }
    std::ifstream file(EC_SCENE_SHADER_PATH,std::ios::binary);const std::string fx{std::istreambuf_iterator<char>(file),{}};
    const auto begin=fx.find("// EC_SCENE_MASK_INTERSECTION_BEGIN"),end=fx.find("// EC_SCENE_MASK_INTERSECTION_END");
    check(begin!=std::string::npos&&end>begin,"actual FX intersection helper missing");
    struct Path {std::array<float,4> endpoints,bounds;};std::vector<Path> paths;
    std::mt19937 rng(415);std::uniform_real_distribution<float> jitter(-3,3),z(.1f,8),distance(.05f,95);
    for(unsigned i=0;i<2000;++i){
        const float ox=jitter(rng),oy=jitter(rng),oz=z(rng),rx=jitter(rng),ry=jitter(rng),limit=distance(rng);
        const auto uv=[&](float d){return std::array<float,2>{(ox+rx*d)/(oz+d)*.5f+.5f,(oy+ry*d)/(oz+d)*-.5f+.5f};};
        const auto a=uv(.05f),b=uv(limit);const auto probe=uv(.05f*std::pow(limit/.05f,float(i%12)/11));
        // A single occupied source texel at an original geometric probe. Every
        // projected positive-w segment must retain it, including very thin rays.
        if(probe[0]<0||probe[0]>=1||probe[1]<0||probe[1]>=1)continue;
        const float px=std::floor(probe[0]*3840),py=std::floor(probe[1]*2160);
        paths.push_back({{a[0],a[1],b[0],b[1]},{(px-1)/3840,(py-1)/2160,(px+2)/3840,(py+2)/2160}});
    }
    const auto occupied=paths.size();
    paths.push_back({{0,0,0,1},{.25f,.25f,.75f,.75f}});
    paths.push_back({{0,0,1,0},{.25f,.25f,.75f,.75f}});
    const std::string source=fx.substr(begin,end-begin)+R"hlsl(
struct Path { float4 endpoints;float4 bounds; };StructuredBuffer<Path> Paths:register(t0);RWStructuredBuffer<uint> Results:register(u0);
[numthreads(1,1,1)]void Test(uint3 id:SV_DispatchThreadID){Path p=Paths[id.x];Results[id.x]=EcMaskIntersects(p.endpoints.xy,p.endpoints.zw,p.bounds)?1:0;}
)hlsl";
    auto shader=gpu.compile(source,"Test");auto input=gpu.buffer<Path>(paths,D3D11_BIND_SHADER_RESOURCE);auto input_view=gpu.srv(input.Get());
    std::vector<UINT> empty(paths.size());auto output=gpu.buffer<UINT>(empty,D3D11_BIND_UNORDERED_ACCESS);auto output_view=gpu.uav(output.Get());
    auto *iv=input_view.Get();auto *ov=output_view.Get();gpu.context->CSSetShaderResources(0,1,&iv);gpu.context->CSSetUnorderedAccessViews(0,1,&ov,nullptr);
    gpu.context->CSSetShader(shader.Get(),nullptr,0);gpu.context->Dispatch(static_cast<UINT>(paths.size()),1,1);ov=nullptr;gpu.context->CSSetUnorderedAccessViews(0,1,&ov,nullptr);
    const auto result=gpu.read_buffer<UINT>(output.Get());
    for(std::size_t i=0;i<occupied;++i)check(result[i]==1,"empty-ray cull hid an original occupied probe");
    check(result[occupied]==0&&result[occupied+1]==0,"parallel empty rays must skip search");
    std::cout<<"Scene mask: exact empty/sparse tile bounds through 4K; "<<occupied<<" projected thin-texel paths retained\n";return 0;
}catch(const std::exception&e){std::cerr<<e.what()<<'\n';return 1;}}
