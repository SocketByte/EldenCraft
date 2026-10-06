// The effect paints Nether blocks with an HLSL copy of NetherRules.java. This test runs that
// exact copy (between the EC_HELL_RULES markers) on Microsoft's software D3D11 device and
// compares it with a C++ port pinned to the Java goldens in NetherConformance.java, so painted
// lava and the real Minecraft blocks and damage stay in the same places. No game is needed.
#include <d3d11.h>
#include <d3dcompiler.h>
#include <wrl/client.h>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <iostream>
#include <iterator>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

using Microsoft::WRL::ComPtr;
namespace {
void require(bool value,const char *message){if(!value)throw std::runtime_error(message);}
void checked(HRESULT hr,const char *message){require(SUCCEEDED(hr),message);}
// --- C++ port of NetherRules.java (float arithmetic, no contraction under /fp:precise).
std::uint32_t hash(int x,int z,int seed){
    std::uint32_t h=std::uint32_t(x)*0x8da6b343u^std::uint32_t(z)*0xd8163841u^std::uint32_t(seed)*0x9e3779b9u;
    h^=h>>16;h*=0x7feb352du;h^=h>>15;h*=0x846ca68bu;h^=h>>16;return h;
}
float hash01(int x,int z,int seed){return float(hash(x,z,seed)>>8)*(1.0f/16777216.0f);}
float noise(int x,int z,float inverse,int seed){
    const float fx=(float(x)+.5f)*inverse,fz=(float(z)+.5f)*inverse;
    const int ix=int(std::floor(fx)),iz=int(std::floor(fz));
    float tx=fx-float(ix),tz=fz-float(iz);tx=tx*tx*(3-2*tx);tz=tz*tz*(3-2*tz);
    const float a=hash01(ix,iz,seed),b=hash01(ix+1,iz,seed),c=hash01(ix,iz+1,seed),d=hash01(ix+1,iz+1,seed);
    const float top=a+(b-a)*tx,bottom=c+(d-c)*tx;return top+(bottom-top)*tz;
}
int ground(int x,int z,bool centred,float cx,float cz){
    const float lava=noise(x,z,1.0f/7.0f,5),dx=float(x)+.5f-cx,dz=float(z)+.5f-cz;
    const bool safe=centred&&dx*dx+dz*dz<36;
    if(!safe&&lava>.83f)return 4;if(!safe&&lava>.78f)return 5;
    const float biome=noise(x,z,1.0f/9.0f,11);
    if(biome<.25f)return 1;if(biome>.80f)return 2;
    return hash01(x,z,23)<.035f?3:0;
}
bool deep(int x,int z,bool centred,float cx,float cz){
    if(ground(x,z,centred,cx,cz)!=4)return false;
    for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(ground(x+dx,z+dz,centred,cx,cz)<4)return false;
    return true;
}
float ragged(int x,int z,float cx,float cz){
    const float dx=float(x)+.5f-cx,dz=float(z)+.5f-cz;return std::sqrt(dx*dx+dz*dz)+(noise(x,z,1.0f/6.0f,3)-.5f)*8;
}
constexpr int span=400;
}
int main(){try{
    // The port must equal Java before the shader is compared with it.
    require(hash(1,0,0)==0xb0eedb37u&&hash(-7,13,5)==0x8fb3ed57u,"C++ port disagrees with NetherConformance hash goldens");
    std::uint64_t layout=0xcbf29ce484222325ull;
    for(int c=0;c<2;c++)for(int z=-200;z<200;z++)for(int x=-200;x<200;x++){
        layout=(layout^std::uint64_t(ground(x,z,c==1,1.5f,.5f)+(deep(x,z,c==1,1.5f,.5f)?8:0)))*0x100000001b3ull;}
    require(layout==0x5b4596180ac16fe1ull,"C++ port disagrees with the NetherConformance layout golden");
    std::ifstream file(EC_SCENE_SHADER_PATH,std::ios::binary);
    const std::string fx{std::istreambuf_iterator<char>(file),{}};
    const auto begin=fx.find("// EC_HELL_RULES_BEGIN"),end=fx.find("// EC_HELL_RULES_END");
    require(begin!=std::string::npos&&end!=std::string::npos&&end>begin,"FX Nether rule markers missing");
    std::ostringstream source;source<<fx.substr(begin,end-begin)<<R"(
RWStructuredBuffer<uint> Results : register(u0);
[numthreads(64,1,1)] void main(uint3 id : SV_DispatchThreadID) {
    const int area=400*400;
    if(id.x<uint(area*2)){
        uint i=id.x%uint(area),centred=id.x/uint(area);
        int x=int(i%400u)-200,z=int(i/400u)-200;
        Results[id.x]=uint(EcHellGround(x,z,centred==1u,1.5,0.5));
        // The ragged front, in thousandths of a block, for every column near the portal.
        Results[area*2+id.x]=uint(int(round(EcHellRagged(x,z,1.5,0.5)*1000))+1000000);
    }
    if(id.x<4u){
        int xs[4]={1,-7,123456,-99999},zs[4]={0,13,-654321,4},ss[4]={0,5,23,71};
        Results[area*4+id.x]=EcHellHash(xs[id.x],zs[id.x],ss[id.x]);
    }
}
)";
    const auto shader=source.str();ComPtr<ID3DBlob> code,errors;
    const auto compiled=D3DCompile(shader.data(),shader.size(),"actual FX Nether rules",nullptr,nullptr,"main","cs_5_0",
        D3DCOMPILE_ENABLE_STRICTNESS|D3DCOMPILE_OPTIMIZATION_LEVEL3,0,&code,&errors);
    if(errors)std::cerr.write(static_cast<const char *>(errors->GetBufferPointer()),errors->GetBufferSize());
    checked(compiled,"compile actual FX Nether rules");
    ComPtr<ID3D11Device> device;ComPtr<ID3D11DeviceContext> context;const D3D_FEATURE_LEVEL level=D3D_FEATURE_LEVEL_11_0;
    checked(D3D11CreateDevice(nullptr,D3D_DRIVER_TYPE_WARP,nullptr,0,&level,1,D3D11_SDK_VERSION,&device,nullptr,&context),"create headless D3D11 WARP device");
    ComPtr<ID3D11ComputeShader> compute;checked(device->CreateComputeShader(code->GetBufferPointer(),code->GetBufferSize(),nullptr,&compute),"create compute shader");
    const UINT count=span*span*4+4;
    D3D11_BUFFER_DESC desc{};desc.ByteWidth=count*4;desc.Usage=D3D11_USAGE_DEFAULT;desc.BindFlags=D3D11_BIND_UNORDERED_ACCESS;
    desc.MiscFlags=D3D11_RESOURCE_MISC_BUFFER_STRUCTURED;desc.StructureByteStride=4;
    ComPtr<ID3D11Buffer> output,readback;checked(device->CreateBuffer(&desc,nullptr,&output),"create output buffer");
    D3D11_UNORDERED_ACCESS_VIEW_DESC view{};view.Format=DXGI_FORMAT_UNKNOWN;view.ViewDimension=D3D11_UAV_DIMENSION_BUFFER;view.Buffer.NumElements=count;
    ComPtr<ID3D11UnorderedAccessView> uav;checked(device->CreateUnorderedAccessView(output.Get(),&view,&uav),"create output UAV");
    desc.Usage=D3D11_USAGE_STAGING;desc.BindFlags=0;desc.CPUAccessFlags=D3D11_CPU_ACCESS_READ;desc.MiscFlags=desc.StructureByteStride=0;
    checked(device->CreateBuffer(&desc,nullptr,&readback),"create readback");
    auto *bound=uav.Get();context->CSSetUnorderedAccessViews(0,1,&bound,nullptr);context->CSSetShader(compute.Get(),nullptr,0);
    context->Dispatch((span*span*2+63)/64,1,1);bound=nullptr;context->CSSetUnorderedAccessViews(0,1,&bound,nullptr);
    context->CopyResource(readback.Get(),output.Get());D3D11_MAPPED_SUBRESOURCE mapped{};
    checked(context->Map(readback.Get(),0,D3D11_MAP_READ,0,&mapped),"read compute result");
    const auto *values=static_cast<const std::uint32_t *>(mapped.pData);
    const int xs[4]={1,-7,123456,-99999},zs[4]={0,13,-654321,4},ss[4]={0,5,23,71};
    bool hashes=true;for(int i=0;i<4;i++)if(values[span*span*4+i]!=hash(xs[i],zs[i],ss[i])){hashes=false;
        std::cerr<<"hash "<<i<<": HLSL "<<values[span*span*4+i]<<" C++ "<<hash(xs[i],zs[i],ss[i])<<'\n';}
    // Float noise may differ in the last bit on another compiler/GPU; a block exactly on a
    // threshold can then flip. Allow a vanishing share, never a systematic difference.
    int kinds=0,fronts=0;
    for(int c=0;c<2;c++)for(int i=0;i<span*span;i++){
        const int x=i%span-200,z=i/span-200;
        if(values[c*span*span+i]!=std::uint32_t(ground(x,z,c==1,1.5f,.5f)))++kinds;
        const long expected=std::lround(ragged(x,z,1.5f,.5f)*1000)+1000000;
        if(std::labs(long(values[span*span*2+c*span*span+i])-expected)>1)++fronts;
    }
    context->Unmap(readback.Get(),0);
    require(hashes,"HLSL EcHellHash differs from NetherRules.hash");
    require(kinds<=8,"HLSL EcHellGround differs from NetherRules.ground");
    require(fronts<=8,"HLSL EcHellRagged differs from NetherRules.ragged");
    std::cout<<"Nether rules: HLSL hash exact; "<<span*span*2<<" blocks, "<<kinds<<" threshold flips, "<<fronts
        <<" front differences against the Java-pinned port. Visual placement in game is a separate check.\n";return 0;
}catch(const std::exception &error){std::cerr<<error.what()<<'\n';return 1;}}
