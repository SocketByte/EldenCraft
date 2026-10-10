#include <windows.h>
#include <d3d12.h>
#include <dxgi1_4.h>
#include <wrl/client.h>
#include <iostream>
#include <stdexcept>
#include <string>

using Microsoft::WRL::ComPtr;
void checked(HRESULT hr,const char *why){if(FAILED(hr))throw std::runtime_error(why);}
int main(){try{
    ComPtr<IDXGIFactory4> factory;ComPtr<IDXGIAdapter> warp;ComPtr<ID3D12Device> device;
    checked(CreateDXGIFactory1(IID_PPV_ARGS(&factory)),"create DXGI factory");
    checked(factory->EnumWarpAdapter(IID_PPV_ARGS(&warp)),"get software D3D12 adapter");
    checked(D3D12CreateDevice(warp.Get(),D3D_FEATURE_LEVEL_11_0,IID_PPV_ARGS(&device)),"create D3D12 device");
    D3D12_HEAP_PROPERTIES heap{};heap.Type=D3D12_HEAP_TYPE_DEFAULT;
    D3D12_RESOURCE_DESC desc{};desc.Dimension=D3D12_RESOURCE_DIMENSION_TEXTURE2D;desc.Width=17;desc.Height=19;
    desc.DepthOrArraySize=desc.MipLevels=desc.SampleDesc.Count=1;desc.Format=DXGI_FORMAT_R8G8B8A8_UNORM;
    desc.Flags=D3D12_RESOURCE_FLAG_ALLOW_RENDER_TARGET|D3D12_RESOURCE_FLAG_ALLOW_SIMULTANEOUS_ACCESS;
    ComPtr<ID3D12Resource> texture;ComPtr<ID3D12Fence> fence;
    checked(device->CreateCommittedResource(&heap,D3D12_HEAP_FLAG_SHARED,&desc,D3D12_RESOURCE_STATE_COMMON,nullptr,IID_PPV_ARGS(&texture)),"create shared texture");
    checked(device->CreateFence(0,D3D12_FENCE_FLAG_SHARED,IID_PPV_ARGS(&fence)),"create shared fence");
    const auto prefix=L"Local\\EldenCraftGpuTest_"+std::to_wstring(GetCurrentProcessId())+L"_"+std::to_wstring(GetTickCount64());
    HANDLE handles[4]{};
    for(unsigned i=0;i<4;++i){
        const auto name=prefix+L"_"+std::to_wstring(i);
        checked(device->CreateSharedHandle(i<2?static_cast<ID3D12DeviceChild*>(texture.Get()):fence.Get(),nullptr,GENERIC_ALL,name.c_str(),&handles[i]),"resource/fence must support new-generation aliases without reallocation");
        HANDLE opened{};checked(device->OpenSharedHandleByName(name.c_str(),GENERIC_ALL,&opened),"open generation alias");
        if(i<2){ComPtr<ID3D12Resource> copy;checked(device->OpenSharedHandle(opened,IID_PPV_ARGS(&copy)),"open aliased texture");
            if(copy->GetDesc().Width!=17||copy->GetDesc().Height!=19)throw std::runtime_error("alias changes image dimensions");}
        else{ComPtr<ID3D12Fence> copy;checked(device->OpenSharedHandle(opened,IID_PPV_ARGS(&copy)),"open aliased fence");
            checked(fence->Signal(123),"signal same shared fence");if(copy->GetCompletedValue()!=123)throw std::runtime_error("alias loses existing fence progress");}
        CloseHandle(opened);
    }
    for(auto h:handles)CloseHandle(h);
    std::cout<<"GPU sharing: resource/fence generation aliases preserve dimensions and fence progress without duplicate texture allocations\n";return 0;
}catch(const std::exception&e){std::cerr<<e.what()<<'\n';return 1;}}
