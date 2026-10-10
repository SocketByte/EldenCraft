#pragma once
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <d3d11.h>
#include <d3dcompiler.h>
#include <wrl/client.h>
#include <array>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <span>
#include <stdexcept>
#include <string>
#include <vector>

namespace gpu_test {
using Microsoft::WRL::ComPtr;
inline void check(bool value,const char *message){if(!value)throw std::runtime_error(message);}
inline void checked(HRESULT value,const char *message){check(SUCCEEDED(value),message);}
struct Gpu {
    ComPtr<ID3D11Device> device;ComPtr<ID3D11DeviceContext> context;
    Gpu(){const D3D_FEATURE_LEVEL level=D3D_FEATURE_LEVEL_11_0;
        checked(D3D11CreateDevice(nullptr,D3D_DRIVER_TYPE_WARP,nullptr,0,&level,1,D3D11_SDK_VERSION,&device,nullptr,&context),"create WARP device");}
    ComPtr<ID3D11ComputeShader> compile(const std::string &source,const char *entry){
        ComPtr<ID3DBlob> code,diagnostics;
        const auto hr=D3DCompile(source.data(),source.size(),"actual runtime HLSL",nullptr,nullptr,entry,"cs_5_0",
            D3DCOMPILE_ENABLE_STRICTNESS|D3DCOMPILE_OPTIMIZATION_LEVEL3,0,&code,&diagnostics);
        if(diagnostics)std::cerr.write(static_cast<const char*>(diagnostics->GetBufferPointer()),diagnostics->GetBufferSize());
        checked(hr,"compile runtime compute shader");
        ComPtr<ID3D11ShaderReflection> reflect;
        checked(D3DReflect(code->GetBufferPointer(),code->GetBufferSize(),IID_PPV_ARGS(&reflect)),"reflect compute requirements");
        check(!(reflect->GetRequiresFlags()&D3D_SHADER_REQUIRES_TYPED_UAV_LOAD_ADDITIONAL_FORMATS),"shader must work without additional-format typed UAV loads");
        ComPtr<ID3D11ComputeShader> shader;
        checked(device->CreateComputeShader(code->GetBufferPointer(),code->GetBufferSize(),nullptr,&shader),"create compute shader");return shader;
    }
    ComPtr<ID3D11Texture2D> texture(UINT w,UINT h,DXGI_FORMAT format,UINT binds,const void *data=nullptr,UINT pitch=0){
        D3D11_TEXTURE2D_DESC desc{};desc.Width=w;desc.Height=h;desc.MipLevels=desc.ArraySize=1;
        desc.Format=format;desc.SampleDesc.Count=1;desc.Usage=D3D11_USAGE_DEFAULT;desc.BindFlags=binds;
        D3D11_SUBRESOURCE_DATA initial{data,pitch,0};ComPtr<ID3D11Texture2D> result;
        checked(device->CreateTexture2D(&desc,data?&initial:nullptr,&result),"create test texture");return result;
    }
    ComPtr<ID3D11ShaderResourceView> srv(ID3D11Resource *r){ComPtr<ID3D11ShaderResourceView> v;checked(device->CreateShaderResourceView(r,nullptr,&v),"create SRV");return v;}
    ComPtr<ID3D11UnorderedAccessView> uav(ID3D11Resource *r){ComPtr<ID3D11UnorderedAccessView> v;checked(device->CreateUnorderedAccessView(r,nullptr,&v),"create UAV");return v;}
    template<class T>ComPtr<ID3D11Buffer> buffer(std::span<const T> data,UINT binds){
        D3D11_BUFFER_DESC desc{};desc.ByteWidth=static_cast<UINT>(data.size_bytes());desc.Usage=D3D11_USAGE_DEFAULT;desc.BindFlags=binds;
        desc.MiscFlags=D3D11_RESOURCE_MISC_BUFFER_STRUCTURED;desc.StructureByteStride=sizeof(T);
        D3D11_SUBRESOURCE_DATA initial{data.data(),0,0};ComPtr<ID3D11Buffer> result;
        checked(device->CreateBuffer(&desc,&initial,&result),"create structured buffer");return result;
    }
    template<class T>std::vector<T> read_buffer(ID3D11Buffer *r){
        D3D11_BUFFER_DESC desc{};r->GetDesc(&desc);desc.Usage=D3D11_USAGE_STAGING;desc.BindFlags=0;desc.CPUAccessFlags=D3D11_CPU_ACCESS_READ;
        desc.MiscFlags=desc.StructureByteStride=0;ComPtr<ID3D11Buffer> readback;checked(device->CreateBuffer(&desc,nullptr,&readback),"create buffer readback");
        context->CopyResource(readback.Get(),r);D3D11_MAPPED_SUBRESOURCE mapped{};checked(context->Map(readback.Get(),0,D3D11_MAP_READ,0,&mapped),"map buffer readback");
        const auto *data=static_cast<const T*>(mapped.pData);std::vector<T> result(data,data+desc.ByteWidth/sizeof(T));context->Unmap(readback.Get(),0);return result;
    }
    std::vector<std::array<float,4>> read_texture(ID3D11Texture2D *r){
        D3D11_TEXTURE2D_DESC desc{};r->GetDesc(&desc);desc.Usage=D3D11_USAGE_STAGING;desc.BindFlags=0;desc.CPUAccessFlags=D3D11_CPU_ACCESS_READ;
        ComPtr<ID3D11Texture2D> readback;checked(device->CreateTexture2D(&desc,nullptr,&readback),"create texture readback");
        context->CopyResource(readback.Get(),r);D3D11_MAPPED_SUBRESOURCE mapped{};checked(context->Map(readback.Get(),0,D3D11_MAP_READ,0,&mapped),"map texture readback");
        std::vector<std::array<float,4>> result(std::size_t(desc.Width)*desc.Height);
        for(UINT y=0;y<desc.Height;++y)std::memcpy(result.data()+std::size_t(y)*desc.Width,static_cast<const std::uint8_t*>(mapped.pData)+std::size_t(y)*mapped.RowPitch,desc.Width*16);
        context->Unmap(readback.Get(),0);return result;
    }
};
}
