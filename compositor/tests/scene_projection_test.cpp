// Runs the actual FX face-depth helper on Microsoft's software D3D11 device.
// Analytic planes provide an independent expected depth, including subpixel
// source-texel changes at increasing distances. No game or display is needed.
#include <d3d11.h>
#include <d3dcompiler.h>
#include <wrl/client.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <fstream>
#include <iomanip>
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
std::string vector(float x,float y,float z){
    std::ostringstream out;out<<std::setprecision(9)<<std::scientific;
    out<<"float3("<<x<<","<<y<<","<<z<<")";return out.str();
}
struct Expected {float depth{},sample_depth{};};
}
int main(){try{
    std::ifstream file(EC_SCENE_SHADER_PATH,std::ios::binary);
    const std::string fx{std::istreambuf_iterator<char>(file),{}};
    const auto begin=fx.find("// EC_PLANE_DISTANCE_BEGIN"),end=fx.find("// EC_PLANE_DISTANCE_END");
    require(begin!=std::string::npos&&end!=std::string::npos&&end>begin,"FX plane helper markers missing");
    std::ostringstream source;source<<fx.substr(begin,end-begin)
        <<"\nRWStructuredBuffer<float> Results : register(u0);\n[numthreads(1,1,1)] void main() {\n";
    std::vector<Expected> expected;
    // n=(.25,.4,1); X/Y tangent offsets preserve the known plane. The
    // unnormalised camera ray has forward component1, so true forward depth
    // is exactly d at both centre and off-axis pixels.
    for(float d:{2.f,8.f,32.f,64.f})for(float ray_x:{0.f,.6f})for(float phase:{-.49f,.49f}){
        const float tangent=std::tan(35.f*3.14159265358979323846f/180.f);
        const float step_x=d*2*tangent*(16.f/9.f)/854,step_y=d*2*tangent/480;
        const float ox=.25f,oy=-.125f,oz=.375f;
        const float sx=ox+ray_x*d+phase*step_x,sy=oy+phase*step_y;
        const float sz=oz+d-.25f*phase*step_x-.4f*phase*step_y;
        source<<"Results["<<expected.size()<<"] = EcPlaneDistance("<<vector(sx,sy,sz)<<","
            <<vector(ox,oy,oz)<<","<<vector(0,0,1)<<","<<vector(ray_x,0,1)<<","
            <<vector(step_x,0,-.25f*step_x)<<","<<vector(0,step_y,-.4f*step_y)<<");\n";
        expected.push_back({d,sz-oz});
    }
    // Degenerate and parallel neighbourhoods retain the existing conservative
    // sampled depth instead of exposing NaN or an unbounded extrapolation.
    source<<"Results["<<expected.size()<<"] = EcPlaneDistance(float3(0,0,7),0,float3(0,0,1),float3(0,0,1),0,0);\n";
    expected.push_back({7,7});
    source<<"Results["<<expected.size()<<"] = EcPlaneDistance(float3(0,0,9),0,float3(0,0,1),float3(0,0,1),float3(0,1,0),float3(0,0,1));\n";
    expected.push_back({9,9});source<<"}\n";
    const auto shader=source.str();ComPtr<ID3DBlob> code,errors;
    const auto compiled=D3DCompile(shader.data(),shader.size(),"actual FX plane helper",nullptr,nullptr,"main","cs_5_0",
        D3DCOMPILE_ENABLE_STRICTNESS|D3DCOMPILE_OPTIMIZATION_LEVEL3,0,&code,&errors);
    if(errors)std::cerr.write(static_cast<const char *>(errors->GetBufferPointer()),errors->GetBufferSize());
    checked(compiled,"compile actual FX plane helper");
    ComPtr<ID3D11Device> device;ComPtr<ID3D11DeviceContext> context;
    const D3D_FEATURE_LEVEL level=D3D_FEATURE_LEVEL_11_0;
    checked(D3D11CreateDevice(nullptr,D3D_DRIVER_TYPE_WARP,nullptr,0,&level,1,D3D11_SDK_VERSION,&device,nullptr,&context),
        "create headless D3D11 WARP device");
    ComPtr<ID3D11ComputeShader> compute;
    checked(device->CreateComputeShader(code->GetBufferPointer(),code->GetBufferSize(),nullptr,&compute),"create compute shader");
    D3D11_BUFFER_DESC desc{};desc.ByteWidth=static_cast<UINT>(expected.size()*sizeof(float));desc.Usage=D3D11_USAGE_DEFAULT;
    desc.BindFlags=D3D11_BIND_UNORDERED_ACCESS;desc.MiscFlags=D3D11_RESOURCE_MISC_BUFFER_STRUCTURED;desc.StructureByteStride=sizeof(float);
    ComPtr<ID3D11Buffer> output,readback;checked(device->CreateBuffer(&desc,nullptr,&output),"create output buffer");
    D3D11_UNORDERED_ACCESS_VIEW_DESC view{};view.Format=DXGI_FORMAT_UNKNOWN;view.ViewDimension=D3D11_UAV_DIMENSION_BUFFER;
    view.Buffer.NumElements=static_cast<UINT>(expected.size());ComPtr<ID3D11UnorderedAccessView> uav;
    checked(device->CreateUnorderedAccessView(output.Get(),&view,&uav),"create output UAV");
    desc.Usage=D3D11_USAGE_STAGING;desc.BindFlags=0;desc.CPUAccessFlags=D3D11_CPU_ACCESS_READ;
    desc.MiscFlags=desc.StructureByteStride=0;checked(device->CreateBuffer(&desc,nullptr,&readback),"create readback");
    auto *bound=uav.Get();context->CSSetUnorderedAccessViews(0,1,&bound,nullptr);context->CSSetShader(compute.Get(),nullptr,0);
    context->Dispatch(1,1,1);bound=nullptr;context->CSSetUnorderedAccessViews(0,1,&bound,nullptr);
    context->CopyResource(readback.Get(),output.Get());D3D11_MAPPED_SUBRESOURCE mapped{};
    checked(context->Map(readback.Get(),0,D3D11_MAP_READ,0,&mapped),"read compute result");
    const auto *values=static_cast<const float *>(mapped.pData);bool correct=true;float largest_old_error=0;
    for(std::size_t i=0;i<expected.size();++i){
        const float tolerance=std::max(.0001f,expected[i].depth*.00001f);
        if(!std::isfinite(values[i])||std::abs(values[i]-expected[i].depth)>tolerance){
            std::cerr<<"case "<<i<<": expected "<<expected[i].depth<<", actual "<<values[i]<<'\n';correct=false;}
        largest_old_error=std::max(largest_old_error,std::abs(expected[i].sample_depth-expected[i].depth));
    }
    context->Unmap(readback.Get(),0);
    require(largest_old_error>.05f,"fixture must expose distance-dependent texel-centre depth error");
    require(correct,"current-ray face depth disagrees with analytic plane");
    std::cout<<"Scene projection: "<<expected.size()<<" actual HLSL plane cases passed; old texel-centre error reached "
        <<largest_old_error<<"m. Live camera/presentation alignment is a separate check.\n";return 0;
}catch(const std::exception &error){std::cerr<<error.what()<<'\n';return 1;}}
