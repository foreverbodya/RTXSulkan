#version 450
layout(location=0) in vec2 texCoord;
layout(location=0) out vec4 color;
uniform sampler2D Source;
uniform sampler2D DisplayExposure;
#include "shaders/display-mapping.glsl"
#if CLOUD_ART_STEP >= 3
#include "sulkan/frame.glsl"
uniform sampler2D CloudDisplay;
uniform sampler2D CloudDisplayDepth;
vec4 resolveDisplayCloud(vec2 uv){
    ivec2 size=textureSize(CloudDisplay,0);vec2 q=uv*vec2(size)-.5;ivec2 base=ivec2(floor(q));vec2 f=fract(q);
    ivec2 centre=clamp(ivec2(uv*vec2(size)),ivec2(0),size-1);float depth=texelFetch(CloudDisplayDepth,centre,0).r;
    vec4 total=vec4(0);float mass=0;
    for(int y=0;y<2;y++)for(int x=0;x<2;x++){
        ivec2 p=clamp(base+ivec2(x,y),ivec2(0),size-1);float d=texelFetch(CloudDisplayDepth,p,0).r;
        bool compatible=depth<=1e-7?d<=1e-7:abs(d-depth)<=max(1e-6,.02*depth);
        if(!compatible)continue;
        float w=(x==0?1-f.x:f.x)*(y==0?1-f.y:f.y);total+=texelFetch(CloudDisplay,p,0)*w;mass+=w;
    }
    return mass>1e-6?total/mass:vec4(0,0,0,1);
}
#endif
void main() {
    vec3 hdr=max(texture(Source,texCoord).rgb,vec3(0));
    #if CLOUD_ART_STEP >= 3
    // The artistic volume has its own history and edge resolve. Compose it
    // after RR so the diffuse denoiser cannot erase its fine silhouette.
    vec4 cloud=resolveDisplayCloud(texCoord+CurrentAndPreviousJitter.xy*ViewSizeAndInverse.zw);
    hdr=cloud.rgb+hdr*cloud.a;
#endif
    color=vec4(displayMap(hdr*exp2(texelFetch(DisplayExposure,ivec2(0),0).r)),1);
}
