#include "scene_mask_commands.hpp"
#include "scene_mask_shader.hpp"
#include "block_bindings.hpp"
#include <d3d12.h>
#include <d3d12sdklayers.h>
#include <dxgi1_4.h>
#include <d3dcompiler.h>
#include <wrl/client.h>
#include <algorithm>
#include <cstring>
#include <iostream>
#include <stdexcept>
#include <vector>

using Microsoft::WRL::ComPtr;
using namespace reshade::api;
void check(bool ok,const char *why){if(!ok)throw std::runtime_error(why);}
void checked(HRESULT hr,const char *why){check(SUCCEEDED(hr),why);}
struct NativeCommands {
    ID3D12Device *device;ID3D12GraphicsCommandList *commands;ID3D12DescriptorHeap *heap;
    UINT stride,next{},copies{};
    void barrier(resource r,resource_usage before,resource_usage after){
        const auto state=[](resource_usage use){return use==resource_usage::unordered_access?D3D12_RESOURCE_STATE_UNORDERED_ACCESS:D3D12_RESOURCE_STATE_ALL_SHADER_RESOURCE;};
        D3D12_RESOURCE_BARRIER b{};b.Type=D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;
        b.Transition={reinterpret_cast<ID3D12Resource*>(r.handle),D3D12_RESOURCE_BARRIER_ALL_SUBRESOURCES,state(before),state(after)};
        commands->ResourceBarrier(1,&b);
    }
    void push_constants(shader_stage,pipeline_layout layout,UINT index,UINT first,UINT count,const void *data){
        commands->SetComputeRootSignature(reinterpret_cast<ID3D12RootSignature*>(layout.handle));
        commands->SetComputeRoot32BitConstants(index,count,data,first);
    }
    void push_descriptors(shader_stage stage,pipeline_layout layout,UINT index,const descriptor_table_update &update){
        check(update.count&&update.binding+update.count<=3,"descriptor update outside shader table");
        check(next+update.binding+update.count<=128,"transient descriptor test heap exhausted");
        std::vector<D3D12_CPU_DESCRIPTOR_HANDLE> source(update.count);std::vector<UINT> sizes(update.count,1);
        for(UINT i=0;i<update.count;++i){
            source[i].ptr=static_cast<const resource_view*>(update.descriptors)[i].handle;
            // Fail safely before executing the invalid driver call from 0.25.5.
            check(source[i].ptr!=0,"zero CPU descriptor handle would crash ReShade D3D12 CopyDescriptors");
        }
        auto cpu=heap->GetCPUDescriptorHandleForHeapStart();cpu.ptr+=(next+update.binding)*stride;
        auto gpu=heap->GetGPUDescriptorHandleForHeapStart();gpu.ptr+=next*stride;
        // Match pinned ReShade 6.8 push_descriptors: allocate binding+count,
        // copy only the supplied CPU handles, bind the table at its base.
        device->CopyDescriptors(1,&cpu,&update.count,update.count,source.data(),sizes.data(),D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
        commands->SetDescriptorHeaps(1,&heap);
        auto *root=reinterpret_cast<ID3D12RootSignature*>(layout.handle);
        if(stage==shader_stage::compute){commands->SetComputeRootSignature(root);commands->SetComputeRootDescriptorTable(index,gpu);}
        else{commands->SetGraphicsRootSignature(root);commands->SetGraphicsRootDescriptorTable(index,gpu);}
        next+=update.binding+update.count;++copies;
    }
    void bind_pipeline(pipeline_stage,pipeline p){commands->SetPipelineState(reinterpret_cast<ID3D12PipelineState*>(p.handle));}
    void dispatch(UINT x,UINT y,UINT z){commands->Dispatch(x,y,z);}
};
int main(int argc,char **argv){try{
    const bool hardware=argc>1&&std::strcmp(argv[1],"--hardware")==0;
    ComPtr<ID3D12Debug> debug;const bool validation=SUCCEEDED(D3D12GetDebugInterface(IID_PPV_ARGS(&debug)));
    if(validation)debug->EnableDebugLayer();
    ComPtr<IDXGIFactory4> factory;ComPtr<IDXGIAdapter> adapter;ComPtr<ID3D12Device> device;
    checked(CreateDXGIFactory1(IID_PPV_ARGS(&factory)),"create factory");
    if(!hardware)checked(factory->EnumWarpAdapter(IID_PPV_ARGS(&adapter)),"select WARP");
    checked(D3D12CreateDevice(adapter.Get(),D3D_FEATURE_LEVEL_11_0,IID_PPV_ARGS(&device)),"create D3D12 device");
    ComPtr<IDXGIAdapter1> selected;checked(factory->EnumAdapterByLuid(device->GetAdapterLuid(),IID_PPV_ARGS(&selected)),"identify selected GPU");
    DXGI_ADAPTER_DESC1 adapter_info{};checked(selected->GetDesc1(&adapter_info),"read selected GPU");
    std::wcout<<L"Adapter: "<<adapter_info.Description<<L'\n';
    D3D12_COMMAND_QUEUE_DESC queue_desc{};ComPtr<ID3D12CommandQueue> queue;
    checked(device->CreateCommandQueue(&queue_desc,IID_PPV_ARGS(&queue)),"create queue");
    ComPtr<ID3D12CommandAllocator> allocator;ComPtr<ID3D12GraphicsCommandList> commands;
    checked(device->CreateCommandAllocator(D3D12_COMMAND_LIST_TYPE_DIRECT,IID_PPV_ARGS(&allocator)),"create allocator");
    checked(device->CreateCommandList(0,D3D12_COMMAND_LIST_TYPE_DIRECT,allocator.Get(),nullptr,IID_PPV_ARGS(&commands)),"create commands");
    checked(commands->Close(),"close initial commands");
    ComPtr<ID3D12Fence> fence;checked(device->CreateFence(0,D3D12_FENCE_FLAG_NONE,IID_PPV_ARGS(&fence)),"create fence");
    const auto event=CreateEventW(nullptr,FALSE,FALSE,nullptr);check(event!=nullptr,"create completion event");UINT64 serial=0;
    auto execute=[&]{checked(commands->Close(),"close commands");ID3D12CommandList *lists[]={commands.Get()};queue->ExecuteCommandLists(1,lists);
        checked(queue->Signal(fence.Get(),++serial),"signal work");checked(fence->SetEventOnCompletion(serial,event),"arm completion");
        check(WaitForSingleObject(event,30000)==WAIT_OBJECT_0,"D3D12 work must complete");checked(device->GetDeviceRemovedReason(),"D3D12 device removed");};
    auto reset=[&]{checked(allocator->Reset(),"reset allocator");checked(commands->Reset(allocator.Get(),nullptr),"reset commands");};
    auto root=[&](UINT inputs){
        D3D12_DESCRIPTOR_RANGE ranges[2]={{D3D12_DESCRIPTOR_RANGE_TYPE_SRV,inputs,0,0,0},{D3D12_DESCRIPTOR_RANGE_TYPE_UAV,2,0,0,0}};
        D3D12_ROOT_PARAMETER params[3]{};params[0].ParameterType=D3D12_ROOT_PARAMETER_TYPE_32BIT_CONSTANTS;params[0].Constants={0,0,2};
        for(UINT i=0;i<2;++i){params[i+1].ParameterType=D3D12_ROOT_PARAMETER_TYPE_DESCRIPTOR_TABLE;params[i+1].DescriptorTable={1,&ranges[i]};}
        D3D12_ROOT_SIGNATURE_DESC desc{3,params,0,nullptr,D3D12_ROOT_SIGNATURE_FLAG_NONE};ComPtr<ID3DBlob> code,error;
        checked(D3D12SerializeRootSignature(&desc,D3D_ROOT_SIGNATURE_VERSION_1,&code,&error),"serialize scene root");
        ComPtr<ID3D12RootSignature> result;checked(device->CreateRootSignature(0,code->GetBufferPointer(),code->GetBufferSize(),IID_PPV_ARGS(&result)),"create scene root");return result;
    };
    auto scene_root=root(2),block_root=root(3);
    std::array<ComPtr<ID3D12PipelineState>,2> psos;std::array<pipeline,2> pipelines;
    const char *entries[]={"Tiles","Bounds"};
    for(UINT i=0;i<2;++i){ComPtr<ID3DBlob> code,error;
        checked(D3DCompile(eldencraft::frames::scene_mask_shader,sizeof(eldencraft::frames::scene_mask_shader)-1,"runtime scene mask",nullptr,nullptr,entries[i],"cs_5_0",D3DCOMPILE_ENABLE_STRICTNESS|D3DCOMPILE_OPTIMIZATION_LEVEL3,0,&code,&error),"compile scene mask");
        D3D12_COMPUTE_PIPELINE_STATE_DESC desc{};desc.pRootSignature=scene_root.Get();desc.CS={code->GetBufferPointer(),code->GetBufferSize()};
        checked(device->CreateComputePipelineState(&desc,IID_PPV_ARGS(&psos[i])),"create scene mask pipeline");pipelines[i]={reinterpret_cast<UINT64>(psos[i].Get())};
    }
    D3D12_DESCRIPTOR_HEAP_DESC heap_desc{D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV,6,D3D12_DESCRIPTOR_HEAP_FLAG_NONE,0};
    ComPtr<ID3D12DescriptorHeap> source_heap,gpu_heap;checked(device->CreateDescriptorHeap(&heap_desc,IID_PPV_ARGS(&source_heap)),"create source descriptors");
    heap_desc.NumDescriptors=128;heap_desc.Flags=D3D12_DESCRIPTOR_HEAP_FLAG_SHADER_VISIBLE;
    checked(device->CreateDescriptorHeap(&heap_desc,IID_PPV_ARGS(&gpu_heap)),"create transient descriptors");
    const auto stride=device->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_CBV_SRV_UAV);
    auto handle=[&](UINT index){auto h=source_heap->GetCPUDescriptorHandleForHeapStart();h.ptr+=index*stride;return h;};
    auto texture=[&](UINT w,UINT h,DXGI_FORMAT format,bool writable){D3D12_HEAP_PROPERTIES heap{};heap.Type=D3D12_HEAP_TYPE_DEFAULT;
        D3D12_RESOURCE_DESC desc{};desc.Dimension=D3D12_RESOURCE_DIMENSION_TEXTURE2D;desc.Width=w;desc.Height=h;desc.DepthOrArraySize=desc.MipLevels=desc.SampleDesc.Count=1;desc.Format=format;
        if(writable)desc.Flags=D3D12_RESOURCE_FLAG_ALLOW_UNORDERED_ACCESS;
        ComPtr<ID3D12Resource> r;checked(device->CreateCommittedResource(&heap,D3D12_HEAP_FLAG_NONE,&desc,D3D12_RESOURCE_STATE_ALL_SHADER_RESOURCE,nullptr,IID_PPV_ARGS(&r)),"create texture");return r;};
    auto buffer=[&](UINT64 bytes,D3D12_HEAP_TYPE type){D3D12_HEAP_PROPERTIES heap{};heap.Type=type;D3D12_RESOURCE_DESC desc{};desc.Dimension=D3D12_RESOURCE_DIMENSION_BUFFER;desc.Width=bytes;desc.Height=1;
        desc.DepthOrArraySize=desc.MipLevels=desc.SampleDesc.Count=1;desc.Layout=D3D12_TEXTURE_LAYOUT_ROW_MAJOR;ComPtr<ID3D12Resource> r;
        checked(device->CreateCommittedResource(&heap,D3D12_HEAP_FLAG_NONE,&desc,type==D3D12_HEAP_TYPE_UPLOAD?D3D12_RESOURCE_STATE_GENERIC_READ:D3D12_RESOURCE_STATE_COPY_DEST,nullptr,IID_PPV_ARGS(&r)),"create staging buffer");return r;};
    auto mask=texture(240,135,DXGI_FORMAT_R32G32B32A32_FLOAT,true),bounds=texture(1,1,DXGI_FORMAT_R32G32B32A32_FLOAT,true);
    D3D12_SHADER_RESOURCE_VIEW_DESC srv{};srv.Format=DXGI_FORMAT_R32G32B32A32_FLOAT;srv.ViewDimension=D3D12_SRV_DIMENSION_TEXTURE2D;srv.Shader4ComponentMapping=D3D12_DEFAULT_SHADER_4_COMPONENT_MAPPING;srv.Texture2D.MipLevels=1;
    device->CreateShaderResourceView(mask.Get(),&srv,handle(1));device->CreateShaderResourceView(bounds.Get(),&srv,handle(2));
    D3D12_UNORDERED_ACCESS_VIEW_DESC uav{};uav.Format=srv.Format;uav.ViewDimension=D3D12_UAV_DIMENSION_TEXTURE2D;
    device->CreateUnorderedAccessView(mask.Get(),nullptr,&uav,handle(3));device->CreateUnorderedAccessView(bounds.Get(),nullptr,&uav,handle(4));
    const std::array<resource,2> textures={resource{reinterpret_cast<UINT64>(mask.Get())},resource{reinterpret_cast<UINT64>(bounds.Get())}};
    const std::array<resource_view,2> srvs={resource_view{handle(1).ptr},resource_view{handle(2).ptr}},uavs={resource_view{handle(3).ptr},resource_view{handle(4).ptr}};
    unsigned cases=0;
    for(const auto [w,h]:{std::pair<UINT,UINT>{1,1},{17,19},{2560,1440},{3840,2160}})for(bool occupied:{false,true}){
        auto depth=texture(w,h,DXGI_FORMAT_R32_FLOAT,false);srv.Format=DXGI_FORMAT_R32_FLOAT;device->CreateShaderResourceView(depth.Get(),&srv,handle(0));
        const auto desc=depth->GetDesc();D3D12_PLACED_SUBRESOURCE_FOOTPRINT footprint{};UINT64 bytes;
        device->GetCopyableFootprints(&desc,0,1,0,&footprint,nullptr,nullptr,&bytes);auto upload=buffer(bytes,D3D12_HEAP_TYPE_UPLOAD);
        void *data{};D3D12_RANGE no_read{};checked(upload->Map(0,&no_read,&data),"map depth upload");std::memset(data,0,bytes);
        if(occupied){const float value=.25f;std::memcpy(static_cast<char*>(data)+(h-1)*footprint.Footprint.RowPitch+(w-1)*4,&value,4);}upload->Unmap(0,nullptr);
        auto readback=buffer(256,D3D12_HEAP_TYPE_READBACK);reset();
        D3D12_RESOURCE_BARRIER b{};b.Type=D3D12_RESOURCE_BARRIER_TYPE_TRANSITION;b.Transition={depth.Get(),0,D3D12_RESOURCE_STATE_ALL_SHADER_RESOURCE,D3D12_RESOURCE_STATE_COPY_DEST};commands->ResourceBarrier(1,&b);
        D3D12_TEXTURE_COPY_LOCATION src{},dst{};src.pResource=upload.Get();src.Type=D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;src.PlacedFootprint=footprint;dst.pResource=depth.Get();
        commands->CopyTextureRegion(&dst,0,0,0,&src,nullptr);std::swap(b.Transition.StateBefore,b.Transition.StateAfter);commands->ResourceBarrier(1,&b);
        NativeCommands native{device.Get(),commands.Get(),gpu_heap.Get(),stride};
        eldencraft::frames::dispatch_scene_mask(native,{reinterpret_cast<UINT64>(scene_root.Get())},pipelines,textures,srvs,uavs,{handle(0).ptr},w,h);
        check(native.copies==4,"both scene mask stages must use actual D3D12 descriptor copies");
        b.Transition={bounds.Get(),0,D3D12_RESOURCE_STATE_ALL_SHADER_RESOURCE,D3D12_RESOURCE_STATE_COPY_SOURCE};commands->ResourceBarrier(1,&b);
        src={};src.pResource=bounds.Get();dst={};dst.pResource=readback.Get();dst.Type=D3D12_TEXTURE_COPY_TYPE_PLACED_FOOTPRINT;
        dst.PlacedFootprint.Footprint={DXGI_FORMAT_R32G32B32A32_FLOAT,1,1,1,256};commands->CopyTextureRegion(&dst,0,0,0,&src,nullptr);
        std::swap(b.Transition.StateBefore,b.Transition.StateAfter);commands->ResourceBarrier(1,&b);execute();
        D3D12_RANGE read{0,16};checked(readback->Map(0,&read,&data),"read native mask output");std::array<float,4> actual;std::memcpy(actual.data(),data,16);readback->Unmap(0,&no_read);
        const std::array<float,4> expected=occupied?std::array<float,4>{float(w-1),float(h-1),float(w),float(h)}:std::array<float,4>{float(w),float(h),0,0};
        check(actual==expected,"D3D12 partial descriptor bindings lose empty/edge texels");++cases;
    }
    reset();NativeCommands native{device.Get(),commands.Get(),gpu_heap.Get(),stride};
    const pipeline_layout layout{reinterpret_cast<UINT64>(block_root.Get())};
    eldencraft::blocks::bind_block_textures(native,layout,srvs[0],srvs[1],{},false);
    eldencraft::blocks::bind_block_textures(native,layout,srvs[0],srvs[1],srvs[0],true);
    check(native.copies==2&&native.next==5,"detail and raw-lighted blocks bind only their required descriptors");execute();
    if(validation){ComPtr<ID3D12InfoQueue> info;checked(device.As(&info),"get D3D12 diagnostics");
        for(UINT64 i=0;i<info->GetNumStoredMessagesAllowedByRetrievalFilter();++i){SIZE_T bytes{};info->GetMessage(i,nullptr,&bytes);std::vector<char> storage(bytes);auto *m=reinterpret_cast<D3D12_MESSAGE*>(storage.data());
            checked(info->GetMessage(i,m,&bytes),"read D3D12 diagnostics");if(m->Severity<=D3D12_MESSAGE_SEVERITY_ERROR)throw std::runtime_error(m->pDescription);}}
    CloseHandle(event);std::cout<<"D3D12 "<<(hardware?"hardware":"WARP")<<": "<<cases<<" real scene-mask command sequences through 4K and detail/raw block descriptor copies passed; debug layer "<<(validation?"enabled":"unavailable")<<'\n';return 0;
}catch(const std::exception&e){std::cerr<<e.what()<<'\n';return 1;}}
