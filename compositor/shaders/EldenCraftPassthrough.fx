// Real Minecraft pixels only. No recreated inventory, hand, icons or text.
// Self-contained: no external shader pack/include dependency.
texture EcOverlayTexture : ELDENCRAFT_OVERLAY;
texture EcSceneTexture : ELDENCRAFT_SCENE;
texture EcSceneDepthTexture : ELDENCRAFT_SCENE_DEPTH;
texture EcHostDepthTexture : DEPTH;
texture EcAvatarTexture : ELDENCRAFT_AVATAR;
texture EcAvatarDepthTexture : ELDENCRAFT_AVATAR_DEPTH;
texture EcBlockColorTexture : ELDENCRAFT_BLOCK_COLOR;
texture EcBlockDepthTexture : ELDENCRAFT_BLOCK_DEPTH;
sampler EcBlockColorSampler { Texture=EcBlockColorTexture; AddressU=CLAMP; AddressV=CLAMP; MinFilter=POINT; MagFilter=POINT; MipFilter=POINT; };
sampler EcBlockDepthSampler { Texture=EcBlockDepthTexture; AddressU=CLAMP; AddressV=CLAMP; MinFilter=POINT; MagFilter=POINT; MipFilter=POINT; };
sampler EcSceneSampler { Texture=EcSceneTexture; AddressU=CLAMP; AddressV=CLAMP; MinFilter=POINT; MagFilter=POINT; MipFilter=POINT; };
sampler EcSceneDepthSampler { Texture=EcSceneDepthTexture; AddressU=CLAMP; AddressV=CLAMP; MinFilter=POINT; MagFilter=POINT; MipFilter=POINT; };
sampler EcHostDepthSampler { Texture=EcHostDepthTexture; AddressU=CLAMP; AddressV=CLAMP; MinFilter=POINT; MagFilter=POINT; MipFilter=POINT; };
sampler EcAvatarSampler { Texture=EcAvatarTexture; AddressU=CLAMP; AddressV=CLAMP; MinFilter=POINT; MagFilter=POINT; MipFilter=POINT; };
sampler EcAvatarDepthSampler { Texture=EcAvatarDepthTexture; AddressU=CLAMP; AddressV=CLAMP; MinFilter=POINT; MagFilter=POINT; MipFilter=POINT; };
uniform int EcDepthMode < ui_type="combo"; ui_label="Scene depth calibration"; ui_items="Uncalibrated (scene hidden)\0Forward Z\0Reverse Z (Elden Ring)\0"; ui_tooltip="Elden Ring 2.7.1.0 renders reverse Z. Uses actual host camera near/far values. Uncalibrated hides the scene for depth debugging."; > = 2;
// Elden Ring's own finished frame, blurred, is the local light around each
// Minecraft pixel (dark interiors, torches, sky) and the scenery behind it.
uniform bool EcRelight < ui_label="Relight Minecraft from Elden Ring"; ui_tooltip="Multiplies Minecraft world, blocks and avatar by the blurred host frame's local brightness. The hand/HUD overlay is never relit."; > = true;
uniform float EcLightGain < ui_type="slider"; ui_min=0.0; ui_max=6.0; ui_label="Relight gain"; > = 2.6;
uniform float EcLightMin < ui_type="slider"; ui_min=0.0; ui_max=1.0; ui_label="Relight minimum"; > = 0.18;
uniform float EcLightMax < ui_type="slider"; ui_min=0.5; ui_max=2.0; ui_label="Relight maximum"; > = 1.15;
uniform float EcLightTint < ui_type="slider"; ui_min=0.0; ui_max=1.0; ui_label="Relight colour tint"; > = 0.35;
uniform float EcHazeStrength < ui_type="slider"; ui_min=0.0; ui_max=1.0; ui_label="Distance haze strength"; > = 0.5;
uniform float EcHazeStart < ui_type="slider"; ui_min=0.0; ui_max=96.0; ui_label="Distance haze start (m)"; > = 24.0;
uniform float EcHazeEnd < ui_type="slider"; ui_min=8.0; ui_max=400.0; ui_label="Distance haze end (m)"; > = 160.0;
texture EcHostColorTexture : COLOR;
sampler EcHostColorSampler { Texture=EcHostColorTexture; AddressU=CLAMP; AddressV=CLAMP; MinFilter=LINEAR; MagFilter=LINEAR; MipFilter=LINEAR; };
texture EcHazeTexture { Width=BUFFER_WIDTH/8; Height=BUFFER_HEIGHT/8; Format=RGBA16F; };
sampler EcHazeSampler { Texture=EcHazeTexture; AddressU=CLAMP; AddressV=CLAMP; MinFilter=LINEAR; MagFilter=LINEAR; MipFilter=LINEAR; };
texture EcLightTexture { Width=BUFFER_WIDTH/64; Height=BUFFER_HEIGHT/64; Format=RGBA16F; };
sampler EcLightSampler { Texture=EcLightTexture; AddressU=CLAMP; AddressV=CLAMP; MinFilter=LINEAR; MagFilter=LINEAR; MipFilter=LINEAR; };
uniform int EcSceneDebug < ui_type="combo"; ui_label="Scene depth inspection"; ui_items="Off\0Host raw depth\0Host metre bands\0Guest scene inspection\0"; > = 0;
uniform bool EcSceneReady=false,EcDepthReady=false,EcSceneZeroToOne=true;
uniform bool EcBlocksReady=false;
uniform bool EcAvatarReady=false;
uniform float4 EcAvatarInverse0,EcAvatarInverse1,EcAvatarInverse2,EcAvatarInverse3;
uniform float4 EcHostLens=float4(.7,1.777,.1,1000);
uniform float3 EcHostRight=float3(1,0,0),EcHostUp=float3(0,1,0),EcHostForward=float3(0,0,1),EcHostTranslation=0;
uniform float2 EcSceneTexel=float2(1.0/854,1.0/480);
uniform float4 EcSceneProjection0,EcSceneProjection1,EcSceneProjection2,EcSceneProjection3;
uniform float4 EcSceneInverse0,EcSceneInverse1,EcSceneInverse2,EcSceneInverse3;
// ---------------------------------------------------------------- the Nether
// Minecraft publishes ECNH (compositor/include/nether_protocol.hpp) once a lit portal has taken the player:
// Elden Ring's own frame is turned to hell before any Minecraft layer is drawn (sky, fog,
// grade, and Minecraft blocks painted onto its terrain, block for block where the real ones
// are), then a post pass adds portal warp, heat haze, embers and the heartbeat vignette over
// the composited world. The hand/HUD overlay is never touched.
uniform float EcHellIntensity < ui_type="slider"; ui_min=0.0; ui_max=1.0; ui_label="Nether intensity"; ui_tooltip="Scales every Nether effect while a portal has opened it. 0 turns the effect off without closing the Nether."; > = 1.0;
uniform bool EcHellPaintGround < ui_label="Nether: paint Elden Ring terrain"; ui_tooltip="Netherrack, nylium, soul sand, magma and lava spread across Elden Ring's own surfaces from the portal."; > = true;
uniform float EcHellFogDensity < ui_type="slider"; ui_min=0.0; ui_max=0.02; ui_label="Nether fog density"; > = 0.0018;
uniform bool EcHellReady=false,EcHellGridReady=false,EcHellCentred=false;
uniform float EcHellAmount=0,EcHellWarp=0,EcHellRadius=0;
uniform float3 EcHellGrid=0,EcHellCenter=0;
// Seconds since: opened, lightning flash, heartbeat, fireball shake (negative: none yet).
uniform float4 EcHellEvents=float4(-1,-1,-1,-1);
// Shake strength, dread.
uniform float2 EcHellLevels=0;
uniform float EcTimer < source="timer"; >;
uniform int EcFrameCount < source="framecount"; >;
static const float EcHellPi=3.14159265;
static const float EcAspect=float(BUFFER_WIDTH)/float(BUFFER_HEIGHT);
sampler EcOverlaySampler {
    Texture = EcOverlayTexture;
    AddressU = CLAMP; AddressV = CLAMP;
    MinFilter = POINT; MagFilter = POINT; MipFilter = POINT;
};
uniform bool EcFrameActive = false;
uniform bool EcBottomUp = true;
void EcFullscreenVS(uint id : SV_VertexID, out float4 position : SV_Position, out float2 uv : TEXCOORD) {
    uv = float2((id << 1) & 2, id & 2);
    position = float4(uv * float2(2.0,-2.0) + float2(-1.0,1.0),0.0,1.0);
}
float4 EcOverlayPS(float4 position : SV_Position, float2 uv : TEXCOORD) : SV_Target {
    if (!EcFrameActive) return float4(0.0,0.0,0.0,0.0);
    if (EcBottomUp) uv.y = 1.0-uv.y;
    return tex2D(EcOverlaySampler,uv);
}
// 8x box downsample: 16 bilinear taps, each averaging a 2x2 source block.
float4 EcDownsample(sampler source,float2 uv) {
    const float2 texel=1.0/tex2Dsize(source,0);
    float4 sum=0;
    [unroll] for(int y=0;y<4;++y)
        [unroll] for(int x=0;x<4;++x)
            sum+=tex2Dlod(source,float4(uv+(float2(x,y)-1.5)*2.0*texel,0,0));
    return sum/16.0;
}
float4 EcHostHazePS(float4 position : SV_Position,float2 uv : TEXCOORD) : SV_Target { return EcDownsample(EcHostColorSampler,uv); }
float4 EcHostLightPS(float4 position : SV_Position,float2 uv : TEXCOORD) : SV_Target { return EcDownsample(EcHazeSampler,uv); }
// Local host light: brightness of the heavily blurred frame, with a little of
// its colour. Multiplies Minecraft's own (lightmap-lit) premultiplied colour.
float3 EcHostLight(float2 uv) {
    const float3 environment=tex2Dlod(EcLightSampler,float4(uv,0,0)).rgb;
    const float luminance=dot(environment,float3(.299,.587,.114));
    const float3 tint=saturate(environment/max(luminance,.001)*.5);
    const float k=clamp(EcLightMin+luminance*EcLightGain,0,EcLightMax);
    return k*lerp(float3(1,1,1),tint*2,EcLightTint);
}
float4 EcRelightWorld(float4 color,float metres,float2 uv) {
    if(!EcRelight||color.a<=0)return color;
    float3 light=EcHostLight(uv);
    // In the Nether, Minecraft's own fire, lava and magma keep glowing instead of taking
    // the dark host light. Colour is premultiplied; judge the straight colour.
    const float3 straight=color.rgb/max(color.a,.001);
    const float hot=saturate((straight.r-.55)*3)*saturate((straight.r-straight.b)*2.5)*saturate(straight.g*3);
    light=lerp(light,max(light,1),hot*(EcHellReady?EcHellAmount*EcHellIntensity:0));
    color.rgb*=light;
    // Fade toward the host scenery behind the Minecraft surface. Colour is
    // premultiplied, so the haze contribution is scaled by coverage too.
    const float haze=saturate((metres-EcHazeStart)/max(EcHazeEnd-EcHazeStart,1))*EcHazeStrength;
    if(haze>0)color.rgb=lerp(color.rgb,tex2Dlod(EcHazeSampler,float4(uv,0,0)).rgb*color.a,haze);
    return color;
}
float4 EcProject(float3 relative) {
    float4 p=float4(relative,1);
    return float4(dot(EcSceneProjection0,p),dot(EcSceneProjection1,p),dot(EcSceneProjection2,p),dot(EcSceneProjection3,p));
}
float4 EcUnproject(float4 clip) {
    return float4(dot(EcSceneInverse0,clip),dot(EcSceneInverse1,clip),dot(EcSceneInverse2,clip),dot(EcSceneInverse3,clip));
}
float EcHostMetres(float depth) {
    float n=EcHostLens.z,f=EcHostLens.w;
    if(EcDepthMode==2)return depth<=0?1e8:n*f/(n+depth*(f-n));
    return depth>=1?1e8:n*f/(f-depth*(f-n));
}
bool EcReadScene(float2 imageUv,out float3 world) {
    world=0;
    if(any(imageUv<0)||any(imageUv>=1))return false;
    // POINT depth belongs to a texel centre. Using the unsnapped probe NDC
    // reconstructs a different ray and makes adjacent cube faces swim.
    imageUv=(floor(imageUv/EcSceneTexel)+.5)*EcSceneTexel;
    float2 rasterUv=imageUv;
    if(EcBottomUp)rasterUv.y=1-rasterUv.y;
    float2 ndc=float2(rasterUv.x*2-1,1-rasterUv.y*2);
    float depth=tex2Dlod(EcSceneDepthSampler,float4(imageUv,0,0)).r;
    if(depth<=0.0000001||depth>1)return false;
    float4 reconstructed=EcUnproject(float4(ndc,EcSceneZeroToOne?depth:depth*2-1,1));
    if(abs(reconstructed.w)<.00001)return false;
    world=reconstructed.xyz/reconstructed.w;
    return all(abs(world)<1e6);
}
bool EcSampleScene(float3 relativePosition,out float3 world,out float2 imageUv) {
    float4 clip=EcProject(relativePosition);
    world=0;imageUv=0;
    if(clip.w<=0.0001)return false;
    float2 ndc=clip.xy/clip.w;
    if(any(abs(ndc)>1))return false;
    imageUv=float2(ndc.x*.5+.5,.5-ndc.y*.5);
    if(EcBottomUp)imageUv.y=1-imageUv.y;
    return EcReadScene(imageUv,world);
}
// EC_PLANE_DISTANCE_BEGIN -- compiled verbatim by the analytic GPU regression.
float EcPlaneDistance(float3 surfacePosition,float3 origin,float3 forward,float3 ray,float3 dx,float3 dy) {
    float fallback=dot(surfacePosition-origin,forward);
    float3 normal=cross(dx,dy);
    float denominator=dot(normal,ray);
    if(abs(denominator)<length(normal)*.001)return fallback;
    float distance=dot(normal,surfacePosition-origin)/denominator;
    return distance>.01&&distance<96?distance:fallback;
}
// EC_PLANE_DISTANCE_END
float EcSurfaceDistance(float2 imageUv,float3 surfacePosition,float3 ray) {
    // Intersect the current host ray with the captured face, rather than
    // repeatedly borrowing the depth of alternating foreground/background
    // texels. Choose the continuous neighbour on each axis at silhouettes.
    float3 l=0,r=0,u=0,d=0;
    bool vl=EcReadScene(imageUv-float2(EcSceneTexel.x,0),l);
    bool vr=EcReadScene(imageUv+float2(EcSceneTexel.x,0),r);
    bool vu=EcReadScene(imageUv-float2(0,EcSceneTexel.y),u);
    bool vd=EcReadScene(imageUv+float2(0,EcSceneTexel.y),d);
    float fallback=dot(surfacePosition-EcHostTranslation,EcHostForward);
    if((!vl&&!vr)||(!vu&&!vd))return fallback;
    float3 dx=vl&&(!vr||dot(l-surfacePosition,l-surfacePosition)<dot(r-surfacePosition,r-surfacePosition))?surfacePosition-l:r-surfacePosition;
    float3 dy=vu&&(!vd||dot(u-surfacePosition,u-surfacePosition)<dot(d-surfacePosition,d-surfacePosition))?surfacePosition-u:d-surfacePosition;
    float limit=max(.03,length(surfacePosition)*max(EcSceneTexel.x,EcSceneTexel.y)*12);
    if(length(dx)>limit||length(dy)>limit)return fallback;
    return EcPlaneDistance(surfacePosition,EcHostTranslation,EcHostForward,ray,dx,dy);
}
float4 EcCapturedScene(float2 uv,float limit,out float surfaceDistance) {
    surfaceDistance=1e8;
    if(!EcSceneReady)return 0;
    float3 ray=EcHostForward+EcHostRight*((uv.x*2-1)*EcHostLens.x*EcHostLens.y)+EcHostUp*((1-uv.y*2)*EcHostLens.x);
    if(limit<=.05)return 0;
    float3 samplePoint=0;float2 sceneUv=0;bool found=false;float distance=limit;
    // Geometric probes .05*(limit/.05)^(i/11): one pow per pixel, not per step.
    float probeGrowth=pow(max(limit/.05,1),1.0/11),probe=.05/probeGrowth;
    // Small bounded search handles a one-frame translated camera without using a GTA projection or depth scale.
    [loop] for(int stepIndex=0;stepIndex<12;++stepIndex) {
        probe*=probeGrowth;
        if(EcSampleScene(EcHostTranslation+ray*probe,samplePoint,sceneUv)) {
            float measured=dot(samplePoint-EcHostTranslation,EcHostForward);
            // At the host-depth boundary an oblique face's texel centre can
            // lie beyond the limit while this output ray still hits its face.
            // One final face check avoids discarding that visible intersection.
            if(stepIndex==11)measured=EcSurfaceDistance(sceneUv,samplePoint,ray);
            if(measured<=probe+.025){distance=measured;found=true;break;}
        }
    }
    if(!found)return 0;
    [loop] for(int refinement=0;refinement<3;++refinement) {
        distance=EcSurfaceDistance(sceneUv,samplePoint,ray);
        if(!EcSampleScene(EcHostTranslation+ray*distance,samplePoint,sceneUv))return 0;
    }
    // Keep the face depth at THIS output ray. Replacing it with the captured
    // texel centre undoes refinement: the oblique-face error grows with texel
    // footprint/distance and flickers against host terrain or native triangles.
    distance=EcSurfaceDistance(sceneUv,samplePoint,ray);
    if(distance<=.01||distance>limit)return 0;
    float3 hostPoint=samplePoint-EcHostTranslation;
    float sampleDistance=dot(hostPoint,EcHostForward);
    if(sampleDistance<=.01)return 0;
    float2 projected=float2(dot(hostPoint,EcHostRight)/(sampleDistance*EcHostLens.x*EcHostLens.y),dot(hostPoint,EcHostUp)/(sampleDistance*EcHostLens.x));
    float2 wanted=float2(uv.x*2-1,1-uv.y*2);
    // A disocclusion hole is transparent rather than a displaced/through-wall object.
    if(any(abs(projected-wanted)>EcSceneTexel*4))return 0;
    surfaceDistance=distance;
    return tex2D(EcSceneSampler,sceneUv);
}
float4 EcAvatar(float2 uv,float limit,out float distance) {
    distance=1e8;
    if(!EcAvatarReady)return 0;
    // The whole genuine avatar stays camera-relative, without iterative RGB-D
    // reprojection/disocclusion. Adjust only lens/aspect and the proven MC/ER
    // opposite screen-right convention; skin, shield and armor stay together.
    float2 ndc=float2(uv.x*2-1,1-uv.y*2);
    ndc*=float2(-EcHostLens.x*EcHostLens.y/EcAvatarInverse0.x,EcHostLens.x/EcAvatarInverse1.y);
    if(any(abs(ndc)>=1))return 0;
    float2 sampleUv=float2(ndc.x*.5+.5,.5-ndc.y*.5);
    if(EcBottomUp)sampleUv.y=1-sampleUv.y;
    float4 color=tex2D(EcAvatarSampler,sampleUv);
    if(color.a<=0)return 0;
    float raw=tex2D(EcAvatarDepthSampler,sampleUv).r;
    if(!(raw>0&&raw<=1))return 0;
    float4 clip=float4(ndc,EcSceneZeroToOne?raw:raw*2-1,1);
    float z=dot(EcAvatarInverse2,clip),w=dot(EcAvatarInverse3,clip);
    if(abs(w)<.000001)return 0;
    float metres=-z/w;
    if(!(metres>.001&&metres<=limit))return 0;
    distance=metres;return color;
}
void EcOrder(inout float4 a,inout float da,inout float4 b,inout float db) {
    if(da>db){float4 c=a;a=b;b=c;float d=da;da=db;db=d;}
}
float4 EcScenePS(float4 position : SV_Position,float2 uv : TEXCOORD) : SV_Target {
    if(!EcFrameActive||!EcDepthReady)return 0;
    float raw=tex2D(EcHostDepthSampler,uv).r;
    if(EcSceneDebug==1)return float4(raw,raw,raw,1);
    float hostDepth=EcHostMetres(raw);
    if(EcSceneDebug==2){float band=frac(hostDepth);return float4(band,band,band,1);}
    float limit=EcSceneDebug==3?96:min(hostDepth+.03,96);
    float capturedDistance=1e8;
    float4 captured=EcCapturedScene(uv,limit,capturedDistance);
    // Real MC triangles were rasterized in the current host view. No captured
    // screen-depth reconstruction is needed to reveal a block's other faces.
    float blockDistance=1e8;float4 block=0;
    if(EcBlocksReady){float d=tex2D(EcBlockDepthSampler,uv).r;
        if(d>.001&&d<=limit){blockDistance=d;block=tex2D(EcBlockColorSampler,uv);}}
    float avatarDistance=1e8;float4 avatar=EcAvatar(uv,limit,avatarDistance);
    // Every world layer gets the same host light before ordering, so a block
    // and the entity in front of it never disagree about local brightness.
    if(EcSceneDebug!=3){captured=EcRelightWorld(captured,capturedDistance,uv);
        block=EcRelightWorld(block,blockDistance,uv);avatar=EcRelightWorld(avatar,avatarDistance,uv);}
    EcOrder(captured,capturedDistance,block,blockDistance);
    EcOrder(block,blockDistance,avatar,avatarDistance);
    EcOrder(captured,capturedDistance,block,blockDistance);
    return captured+(block+avatar*(1-block.a))*(1-captured.a);
}
// EC_HELL_RULES_BEGIN -- the HLSL copy of NetherRules.java; nether_rules_test runs it against the Java goldens.
uint EcHellHash(int x,int z,int seed) {
    uint h=asuint(x)*0x8da6b343u^asuint(z)*0xd8163841u^asuint(seed)*0x9e3779b9u;
    h^=h>>16;h*=0x7feb352du;h^=h>>15;h*=0x846ca68bu;h^=h>>16;
    return h;
}
float EcHellHash01(int x,int z,int seed){return float(EcHellHash(x,z,seed)>>8)*(1.0/16777216.0);}
// NetherRules.noise with the reciprocal of its lattice scale (0.14285715=1/7, 0.11111111=1/9, 0.16666667=1/6).
float EcHellNoise(int x,int z,float inverse,int seed) {
    float fx=(float(x)+0.5)*inverse,fz=(float(z)+0.5)*inverse;
    int ix=int(floor(fx)),iz=int(floor(fz));
    float tx=fx-float(ix),tz=fz-float(iz);tx=tx*tx*(3-2*tx);tz=tz*tz*(3-2*tz);
    float a=EcHellHash01(ix,iz,seed),b=EcHellHash01(ix+1,iz,seed),c=EcHellHash01(ix,iz+1,seed),d=EcHellHash01(ix+1,iz+1,seed);
    float top=a+(b-a)*tx,bottom=c+(d-c)*tx;
    return top+(bottom-top)*tz;
}
float EcHellRagged(int x,int z,float cx,float cz) {
    float dx=float(x)+0.5-cx,dz=float(z)+0.5-cz;
    return sqrt(dx*dx+dz*dz)+(EcHellNoise(x,z,0.16666667,3)-0.5)*8;
}
// 0 netherrack, 1 crimson nylium, 2 soul sand, 3 magma, 4 lava, 5 magma rim of a lava pool.
int EcHellGround(int x,int z,bool centred,float cx,float cz) {
    float lava=EcHellNoise(x,z,0.14285715,5);
    float dx=float(x)+0.5-cx,dz=float(z)+0.5-cz;
    bool safe=centred&&dx*dx+dz*dz<36;
    if(!safe&&lava>0.83)return 4;
    if(!safe&&lava>0.78)return 5;
    float biome=EcHellNoise(x,z,0.11111111,11);
    if(biome<0.25)return 1;
    if(biome>0.80)return 2;
    return EcHellHash01(x,z,23)<0.035?3:0;
}
// EC_HELL_RULES_END
float EcHellRandom(float2 p){return frac(sin(dot(p,float2(12.9898,78.233)))*43758.5453);}
float EcHellSmooth(float2 p) {
    float2 i=floor(p),f=frac(p);f=f*f*(3-2*f);
    float a=EcHellRandom(i),b=EcHellRandom(i+float2(1,0)),c=EcHellRandom(i+float2(0,1)),d=EcHellRandom(i+float2(1,1));
    return lerp(lerp(a,b,f.x),lerp(c,d,f.x),f.y);
}
float EcHellFbm(float2 p) {
    float sum=0,amplitude=.5;
    [unroll] for(int i=0;i<5;++i){sum+=EcHellSmooth(p)*amplitude;p=p*2.03+float2(17.1,-9.3);amplitude*=.5;}
    return sum;
}
float3 EcHellRay(float2 uv){return EcHostForward+EcHostRight*((uv.x*2-1)*EcHostLens.x*EcHostLens.y)+EcHostUp*((1-uv.y*2)*EcHostLens.x);}
float EcHellSeconds(){float s=EcTimer*.001;return s-3600*floor(s/3600);}
// A burning sky: churning ember-lit cloud, a blood moon fixed in the world, lightning.
float3 EcHellSky(float3 dir,float flash) {
    float t=EcHellSeconds(),e=dir.y;
    float3 horizon=float3(.46,.08,.025),zenith=float3(.03,.003,.003),below=float3(.22,.035,.012);
    float3 color=e>=0?lerp(horizon,zenith,saturate(pow(max(e,0),.55)*1.25)):lerp(horizon,below,saturate(-e*3));
    float2 plane=dir.xz/(max(e,0)+.14)*1.4;
    float churn=EcHellFbm(plane+t*float2(.015,.024)+EcHellFbm(plane*.7-t*.01)*1.7);
    float cloud=smoothstep(.38,.82,churn)*saturate(e*5+.35);
    float3 underlit=lerp(float3(.07,.01,.008),float3(.62,.14,.035),pow(1-saturate(e*1.6),3));
    color=lerp(color,underlit*(.65+.7*churn),cloud*.88);
    float3 moon=normalize(float3(.55,.30,.78));float m=dot(dir,moon);
    float disc=smoothstep(.99895,.9993,m)*(.75+.25*EcHellFbm(dir.xz*90));
    color=lerp(color,float3(.88,.13,.05),disc*(1-cloud*.65));
    color+=float3(.55,.06,.02)*(pow(saturate(m),900)*.8+pow(saturate(m),60)*.12)*(1-cloud*.4);
    color+=flash*float3(.95,.5,.4)*(.18+cloud*1.1);
    return color;
}
// The fog colour: the sky's horizon glow without its cloud detail (evaluated for every surface).
float3 EcHellHaze(float3 dir,float flash) {
    float glow=EcHellSmooth(dir.xz*2.5+EcHellSeconds()*float2(.03,.02));
    return lerp(float3(.26,.045,.016),float3(.44,.085,.03),glow)*(1-saturate(dir.y)*.5)+flash*float3(.5,.25,.2)*.15;
}
// Elden Ring's lit colour pushed into red heat: shadows to black-crimson, highlights to orange.
float3 EcHellGrade(float3 c) {
    float l=dot(c,float3(.2126,.7152,.0722));
    float3 warm=c*float3(1.04,.8,.7),tone=l*float3(1.3,.55,.4);
    return saturate(lerp(warm,tone,.25)*.97);
}
// Minecraft's Nether textures, re-drawn procedurally at 16 texels per block (nothing is shipped).
float3 EcHellNetherrack(int2 t,int bx,int bz,int face) {
    float h=EcHellHash01(t.x+bx*16,t.y+bz*16,31+face),c=EcHellHash01((t.x>>1)+bx*8,(t.y>>1)+bz*8,37+face);
    float v=h*.6+c*.4;
    return v<.22?float3(.29,.10,.10):v<.55?float3(.38,.14,.14):v<.84?float3(.45,.18,.18):float3(.55,.25,.24);
}
float3 EcHellNylium(int2 t,int bx,int bz) {
    float h=EcHellHash01(t.x+bx*16,t.y+bz*16,43);
    return h<.18?float3(.36,.04,.05):h<.82?float3(.53,.07,.07):float3(.68,.11,.10);
}
float3 EcHellSoulSand(int2 t,int bx,int bz,int face) {
    float h=EcHellHash01(t.x+bx*16,t.y+bz*16,47+face),f=EcHellHash01((t.x>>2)+bx*4,(t.y>>2)+bz*4,53+face);
    return f>.78&&h>.35?float3(.17,.11,.085):h<.4?float3(.24,.17,.13):float3(.33,.25,.19);
}
float3 EcHellLava(float2 cell,float t) {
    float v=sin(cell.x*1.7+t*.55+sin(cell.y*1.3+t*.4)*1.6)*.5+.5;
    v=v*.7+EcHellSmooth(cell*1.9+t*float2(.18,.27))*.3;
    return v<.3?float3(.80,.24,.03):v<.6?float3(.97,.48,.07):v<.85?float3(1.,.74,.20):float3(1.,.93,.55);
}
// Magma: dark crust, glowing veins that breathe.
float3 EcHellMagma(int2 t,int bx,int bz,float time,out float3 glow) {
    float n=EcHellSmooth((float2(t)+float2(bx,bz)*16)*.32);
    bool crack=abs(n-.5)<.075;
    glow=crack?float3(1.,.42,.07)*(.75+.25*sin(time*2.2+float(bx*7+bz*3))):0;
    return crack?float3(.45,.16,.04):float3(.24,.08,.03);
}
float3 EcHellPoint(float2 uv){return EcHellRay(uv)*EcHostMetres(tex2Dlod(EcHostDepthSampler,float4(uv,0,0)).r);}
float3 EcHellNormal(float2 uv,float3 c,float step) {
    float2 px=float2(BUFFER_RCP_WIDTH,BUFFER_RCP_HEIGHT)*step;
    float3 r=EcHellPoint(uv+float2(px.x,0)),l=EcHellPoint(uv-float2(px.x,0)),d=EcHellPoint(uv+float2(0,px.y)),u=EcHellPoint(uv-float2(0,px.y));
    float3 dx=dot(r-c,r-c)<dot(c-l,c-l)?r-c:c-l,dy=dot(d-c,d-c)<dot(c-u,c-u)?d-c:c-u;
    float3 n=cross(dy,dx);float len=length(n);
    if(len<1e-8)return float3(0,1,0);
    n/=len;return dot(n,c)>0?-n:n;
}
// One Elden Ring surface pixel inside the spread, as the Minecraft block that took it over.
float3 EcHellBlock(float3 original,float3 position,float3 normal,float metres,float flatness,out float3 emissive) {
    emissive=0;
    float3 q=position-EcHellGrid;float3 b=floor(q-normal*.02);
    int bx=int(b.x),bz=int(b.z);
    float2 center=(EcHellCenter-EcHellGrid).xz;
    float rag=EcHellCentred?EcHellRagged(bx,bz,center.x,center.y):-1e9;
    float front=EcHellRadius-rag,t=EcHellSeconds();
    // Just beyond the front: scorched, smouldering Elden Ring.
    if(front<0){
        float scorch=saturate(1+front/3);
        emissive=float3(1.,.35,.05)*scorch*scorch*step(.9,EcHellHash01(bx,bz,61))*(.6+.4*sin(t*5+float(bx)))*.5;
        return original*lerp(1,.75,scorch);
    }
    int face=normal.y>.5?0:normal.y<-.5?2:1;
    float2 st=face!=1?q.xz:abs(normal.x)>abs(normal.z)?q.zy:q.xy;
    int2 texel=int2(floor(frac(st)*16));
    int kind=EcHellGround(bx,bz,EcHellCentred,center.x,center.y);
    float3 albedo;float3 glow=0;
    if(face==0&&kind==4){emissive=EcHellLava(floor(q.xz*16)/16*4,t)*.9;albedo=0;}
    else if(kind==3||kind==5){albedo=EcHellMagma(texel,bx,bz,t,glow);emissive=glow;}
    else if(kind==1&&(face==0||frac(q.y)>.8))albedo=EcHellNylium(texel,bx,bz);
    else if(kind==2)albedo=EcHellSoulSand(texel,bx,bz,face);
    else albedo=EcHellNetherrack(texel,bx,bz,face);
    // Texels smaller than a pixel would shimmer: fade to each material's mean with distance.
    float footprint=metres*2*EcHostLens.x/BUFFER_HEIGHT*16;
    float3 mean=kind==1&&face==0?float3(.52,.07,.07):kind==2?float3(.29,.21,.16):kind==3||kind==5?float3(.27,.09,.035):float3(.41,.16,.16);
    albedo=lerp(albedo,mean,saturate(footprint-.6));
    // Elden Ring's own light on it, with Minecraft's per-face shading for the voxel read.
    float l=dot(original,float3(.2126,.7152,.0722));
    float shade=face==0?1:face==2?.5:abs(normal.x)>abs(normal.z)?.6:.8;
    float3 lit=albedo*clamp(l*2.6+.08,.08,1.6)*shade;
    // The burning front: the newest blocks are still on fire.
    if(front<2)emissive+=float3(1.,.42,.08)*(1-front*.5)*(.65+.35*sin(t*9+float(bx*13+bz*7)))*.7;
    emissive*=flatness;
    // What cannot become a block (characters, foliage) is charred instead.
    return lerp(original*.85,lit,flatness);
}
float4 EcHellWorldPS(float4 position : SV_Position,float2 uv : TEXCOORD) : SV_Target {
    float4 original=tex2D(EcHostColorSampler,uv);
    float amount=EcHellReady?EcHellAmount*EcHellIntensity:0;
    if(amount<=.001||EcSceneDebug!=0)return original;
    if(!EcDepthReady||(EcDepthMode!=1&&EcDepthMode!=2))return float4(lerp(original.rgb,EcHellGrade(original.rgb),amount),original.a);
    float flash=EcHellEvents.y>=0?exp(-EcHellEvents.y*9)+.6*exp(-abs(EcHellEvents.y-.22)*25):0;
    float3 ray=EcHellRay(uv),dir=normalize(ray);
    float metres=EcHostMetres(tex2D(EcHostDepthSampler,uv).r);
    float3 hell;
    if(metres>=EcHostLens.w*.98)hell=EcHellSky(dir,flash);
    else {
        float3 surface=original.rgb,emissive=0,p=ray*metres;
        if(EcHellPaintGround&&EcHellGridReady){
            float3 n=EcHellNormal(uv,p,1),wide=EcHellNormal(uv,p,3);
            // Curved or broken surfaces (characters, foliage, silhouettes) keep their own look.
            float flatness=smoothstep(.8,.96,dot(n,wide));
            surface=EcHellBlock(original.rgb,p,n,metres,flatness,emissive);
        }
        hell=EcHellGrade(surface)+emissive;
        float fog=saturate(1-exp(-metres*EcHellFogDensity))*.7;
        float3 haze=EcHellHaze(dir,flash);
        hell=lerp(hell,haze,fog)+flash*float3(.5,.25,.2)*.12*(1-fog);
    }
    return float4(lerp(original.rgb,hell,amount),original.a);
}
float2 EcHellRotate(float2 v,float a){float s=sin(a),c=cos(a);return float2(v.x*c-v.y*s,v.x*s+v.y*c);}
// Square sparks drifting up through the air; turning the camera slides them like distant motes.
float3 EcHellEmbers(float2 uv,float t) {
    float3 sum=0;
    float yaw=atan2(EcHostForward.x,EcHostForward.z),pitch=asin(clamp(EcHostForward.y,-1,1));
    float side=dot(EcHostRight,float3(cos(yaw),0,-sin(yaw)))>=0?1:-1;
    [unroll] for(int k=0;k<4;++k){
        float scale=5+k*4;int period=int(scale*8);
        float2 p=float2(uv.x*EcAspect,uv.y)*scale;
        p.x+=side*yaw/(2*EcHellPi)*float(period);p.y-=pitch*scale*.9+t*(.35+.18*k);
        p.x+=sin(p.y*.35+float(k))*.6;
        float2 cell=floor(p),f=frac(p);
        int cx=int(cell.x)%period;if(cx<0)cx+=period;
        float h0=EcHellHash01(cx,int(cell.y),71+k),h1=EcHellHash01(cx,int(cell.y),79+k),h2=EcHellHash01(cx,int(cell.y),83+k);
        float2 c=.2+.6*float2(h1,h2);c.x+=sin(t*1.3+h0*40)*.12;
        float2 d=abs(f-c);float size=(.035+.05*h1)*(1-k*.15);
        float body=1-smoothstep(size*.55,size,max(d.x,d.y));
        float halo=exp(-length(f-c)*(16+k*6))*.35;
        float flicker=.55+.45*sin(t*(6+h1*9)+h2*20);
        // The last layer is grey ash rather than sparks.
        float3 hot=k==3?float3(.35,.32,.30):lerp(float3(1,.32,.04),float3(1,.72,.22),h2);
        sum+=(h0<=.16?1:0)*(body+(k==3?0:halo))*flicker*hot*(1-.18*k);
    }
    return sum;
}
float4 EcHellPostPS(float4 position : SV_Position,float2 uv : TEXCOORD) : SV_Target {
    float amount=EcHellReady?EcHellAmount*EcHellIntensity:0,warp=EcHellReady?EcHellWarp*EcHellIntensity:0;
    float open=EcHellEvents.x>=0&&EcHellReady?exp(-EcHellEvents.x*1.6)*EcHellIntensity:0;
    if((amount<=.001&&warp<=.001&&open<=.001)||EcSceneDebug!=0)return tex2D(EcHostColorSampler,uv);
    float t=EcHellSeconds();float2 p=uv;
    // A ghast fireball bursting close by.
    float shake=EcHellEvents.w>=0?EcHellLevels.x*exp(-EcHellEvents.w*5):0;
    p+=(float2(EcHellRandom(float2(t*61,1)),EcHellRandom(float2(t*67,5)))-.5)*.008*shake*EcHellIntensity;
    // The portal taking hold: the world swirls and stretches, like Minecraft's own nausea.
    float2 c=p-.5;c.x*=EcAspect;float r=length(c);
    float twist=max(warp,open*.6)*max(warp,open*.6);
    c=EcHellRotate(c,twist*(1.8*(1-saturate(r*1.3))+.3*sin(t*3+r*12)));
    c*=1-twist*.07*sin(t*2.6);c.x/=EcAspect;p=c+.5;
    // Heat shimmer, stronger low on the screen where the ground burns.
    p+=float2(sin(uv.y*95+t*4.6+sin(uv.x*31+t)*2),cos(uv.x*73-t*3.8))*amount*.0004*(.4+uv.y);
    float2 radial=r>1e-4?(p-.5)/max(r,1e-4):0;float aberration=(amount*.0008+twist*.01)*r*r;
    float3 color=float3(tex2D(EcHostColorSampler,p+radial*aberration).r,tex2D(EcHostColorSampler,p).g,tex2D(EcHostColorSampler,p-radial*aberration).b);
    color=lerp(color,color*float3(.72,.32,1.25)+float3(.12,0,.2)*twist,twist*.6);
    // Arrival: a violet-white blast that burns down to red.
    color=lerp(color,float3(1,.72,.92),open*.55*saturate(1-EcHellEvents.x*1.2));
    color=lerp(color,color*float3(1.15,.65,.5),open*.3);
    float flash=EcHellEvents.y>=0?exp(-EcHellEvents.y*9)+.6*exp(-abs(EcHellEvents.y-.22)*25):0;
    color+=float3(1,.6,.5)*flash*.08*amount;
    color+=EcHellEmbers(uv,t)*amount*.45;
    // The heartbeat: a double pulse that closes the red vignette in.
    float beatAge=EcHellEvents.z;
    float beat=beatAge>=0?exp(-beatAge*7)+.7*exp(-max(beatAge-.3,0)*7)*step(.3,beatAge):0;
    float dread=EcHellLevels.y;
    float2 v=(uv-.5)*float2(EcAspect,1);
    float vignette=smoothstep(.55,1.15,length(v)*1.2)*amount*(.2+.1*dread+.25*dread*beat);
    color=lerp(color,color*float3(.45,.08,.06),saturate(vignette));
    color+=(EcHellRandom(uv*float2(1931,1087)+float(EcFrameCount%997))-.5)*.012*amount;
    return float4(color,1);
}
technique EldenCraftOverlay < ui_label = "EldenCraft: real Minecraft hand and HUD"; > {
    // The Nether turns Elden Ring's own frame first, so relighting and haze see hell.
    pass { VertexShader=EcFullscreenVS;PixelShader=EcHellWorldPS; }
    // Blur the host frame before any Minecraft layer is drawn.
    pass { VertexShader=EcFullscreenVS;PixelShader=EcHostHazePS;RenderTarget=EcHazeTexture; }
    pass { VertexShader=EcFullscreenVS;PixelShader=EcHostLightPS;RenderTarget=EcLightTexture; }
    pass {
        VertexShader=EcFullscreenVS;PixelShader=EcScenePS;
        BlendEnable=true;SrcBlend=ONE;DestBlend=INVSRCALPHA;SrcBlendAlpha=ONE;DestBlendAlpha=INVSRCALPHA;
    }
    // Warp, heat haze, embers and the heartbeat over the composited world, under the HUD.
    pass { VertexShader=EcFullscreenVS;PixelShader=EcHellPostPS; }
    pass {
        VertexShader = EcFullscreenVS;
        PixelShader = EcOverlayPS;
        BlendEnable = true;
        SrcBlend = ONE; DestBlend = INVSRCALPHA;
        SrcBlendAlpha = ONE; DestBlendAlpha = INVSRCALPHA;
    }
}
