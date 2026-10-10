#pragma once
namespace eldencraft::frames {
// One tile covers exactly 16x16 source texels. Bounds are in source pixels;
// a separate 1x1 texture stores their union. No geometry or depth is approximated.
inline constexpr char scene_mask_shader[]=R"hlsl(
cbuffer Size : register(b0) { uint Width; uint Height; };
Texture2D<float> SceneDepth : register(t0);
Texture2D<float4> TileBounds : register(t1);
RWTexture2D<float4> Mask : register(u0);
RWTexture2D<float4> Root : register(u1);
groupshared uint MinX,MinY,MaxX,MaxY;
void Reset(uint lane) { if(lane==0){MinX=Width;MinY=Height;MaxX=0;MaxY=0;} }
void Include(uint4 b) {
    InterlockedMin(MinX,b.x);InterlockedMin(MinY,b.y);
    InterlockedMax(MaxX,b.z);InterlockedMax(MaxY,b.w);
}
[numthreads(16,16,1)] void Tiles(uint3 group:SV_GroupID,uint3 pixel:SV_DispatchThreadID,uint lane:SV_GroupIndex){
    Reset(lane);GroupMemoryBarrierWithGroupSync();
    if(pixel.x<Width&&pixel.y<Height){
        float depth=SceneDepth.Load(int3(pixel.xy,0));
        // Same rejection as EcReadScene. NaN may pass its comparisons, so keep
        // it conservatively; the original reconstruction still rejects it.
        if(!(depth<=.0000001||depth>1))Include(uint4(pixel.xy,pixel.xy+1));
    }
    GroupMemoryBarrierWithGroupSync();
    if(lane==0)Mask[group.xy]=float4(MinX,MinY,MaxX,MaxY);
}
[numthreads(256,1,1)] void Bounds(uint lane:SV_GroupIndex){
    Reset(lane);GroupMemoryBarrierWithGroupSync();
    uint tilesX=(Width+15)/16,tilesY=(Height+15)/16;
    uint4 bounds=uint4(Width,Height,0,0);
    for(uint i=lane;i<tilesX*tilesY;i+=256){
        uint4 b=uint4(TileBounds.Load(int3(i%tilesX,i/tilesX,0)));
        bounds.xy=min(bounds.xy,b.xy);bounds.zw=max(bounds.zw,b.zw);
    }
    Include(bounds);GroupMemoryBarrierWithGroupSync();
    if(lane==0)Root[uint2(0,0)]=float4(MinX,MinY,MaxX,MaxY);
}
)hlsl";
}
