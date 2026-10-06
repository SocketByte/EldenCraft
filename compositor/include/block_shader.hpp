#pragma once
namespace eldencraft::blocks {
inline constexpr char block_shader[] = R"hlsl(
cbuffer Camera : register(b0) {
    float4 Eye;
    float4 Right; // xyz basis, w reciprocal horizontal tangent
    float4 Up;    // xyz basis, w reciprocal vertical tangent
    float4 Forward; // xyz basis, w far/(far-near)
    float4 Clip;  // x near, y far, z -near*far/(far-near), w material mode
    float4 Reserved0; // xy inverse target size, z host depth mode, w genuine block details
    float4 Reserved1; float4 Reserved2;
};
Texture2D<float4> Atlas : register(t0);
Texture2D<float> HostDepth : register(t1);
SamplerState PointSampler : register(s0);
struct Vertex { float3 position:POSITION; float2 uv:TEXCOORD0; float4 tint:COLOR0; };
struct Fragment { float4 position:SV_Position; float2 uv:TEXCOORD0; float4 tint:COLOR0; float meters:TEXCOORD1; };
Fragment VS(Vertex v) {
    Fragment o;
    float3 p = v.position - Eye.xyz;
    float z = dot(p, Forward.xyz);
    o.position = float4(dot(p,Right.xyz)*Right.w, dot(p,Up.xyz)*Up.w, Forward.w*z+Clip.z, z);
    o.uv=v.uv; o.tint=v.tint; o.meters=z;
    return o;
}
struct Output { float4 color:SV_Target0; float depth:SV_Target1; };
Output PS(Fragment i) {
    // Reject host-occluded fragments BEFORE blending: a nearer glass surface
    // must not retain an opaque block behind intervening Elden Ring terrain.
    float raw=HostDepth.SampleLevel(PointSampler,i.position.xy*Reserved0.xy,0);
    if(!isfinite(raw) || raw<0 || raw>1) discard;
    float n=Clip.x,f=Clip.y;
    float hostMeters=Reserved0.z>1.5
        ? (raw<=0 ? 1e8 : n*f/(n+raw*(f-n)))
        : (raw>=1 ? 1e8 : n*f/(f-raw*(f-n)));
    clip(hostMeters+0.03-i.meters);
    float4 c=Atlas.Sample(PointSampler,i.uv)*i.tint;
    if(Reserved0.w>0.5 && Clip.w<0.5){
        clip(c.a-0.1);Output crack;crack.color=c;crack.depth=i.meters;return crack;
    }
    if (Clip.w > 0.5) clip(c.a - (Clip.w < 1.5 ? 0.1 : 0.003921569));
    float alpha=Clip.w > 1.5 ? saturate(c.a) : 1.0;
    Output o; o.color=float4(c.rgb*alpha,alpha); o.depth=i.meters; return o;
}
)hlsl";
}
