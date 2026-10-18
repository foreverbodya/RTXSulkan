#ifndef SULKAN_CLOUD_SOURCE_GRID
#define SULKAN_CLOUD_SOURCE_GRID
// Three world-lattice cascades. View density, opacity and ray endpoints do not
// use this grid. It approximates only source-light transmission. Interpolating
// optical depth before exp() would systematically darken unresolved light.
float cloudSourceSpacing(int cascade) {return 4.0*pow(4.0,float(cascade));}
vec2 cloudSourceCenter(sampler2D layoutTexture,int cascade) {
    float spacing=cloudSourceSpacing(cascade);
    return floor(cinematicCloudOrigin(layoutTexture).xz/spacing)*spacing;
}
vec3 cloudSourcePosition(sampler2D layoutTexture,ivec3 cell,int cascade) {
    return vec3(cloudSourceCenter(layoutTexture,cascade).x+(float(cell.x)-64.0)*cloudSourceSpacing(cascade),
        cinematicCloudBottom()+CLOUD_THICKNESS*float(cell.y)/32.0,
        cloudSourceCenter(layoutTexture,cascade).y+(float(cell.z)-64.0)*cloudSourceSpacing(cascade));
}
#if defined(SULKAN_CLOUD_SOURCE_CACHE_USE) && CLOUD_SOURCE_CACHE == 1
void cloudSourceFetch(int cascade,vec3 uv,out vec3 solar,out vec2 ambient) {
    vec4 s;float lower;
    if(cascade==0){s=textureLod(CloudSource0,uv,0.0);lower=textureLod(CloudSourceAmbient0,uv,0.0).x;}
    else if(cascade==1){s=textureLod(CloudSource1,uv,0.0);lower=textureLod(CloudSourceAmbient1,uv,0.0).x;}
    else {s=textureLod(CloudSource2,uv,0.0);lower=textureLod(CloudSourceAmbient2,uv,0.0).x;}
    solar=s.rgb;ambient=vec2(s.a,lower);
}
float cloudSourceLookup(sampler2D layoutTexture,vec3 p,out vec3 solar,out vec2 ambient) {
    solar=vec3(0);ambient=vec2(0);
    float h=(p.y-cinematicCloudBottom())/CLOUD_THICKNESS;
    if(h<0.0 || h>1.0)return 1.0;
    float remaining=1.0;
    // Blend the last eight cells of each cascade into its parent. At the last
    // border retain the original integral, rather than clamping source light.
    for(int cascade=0;cascade<3;cascade++) {
        vec2 cell=(p.xz-cloudSourceCenter(layoutTexture,cascade))/cloudSourceSpacing(cascade);
        float radius=max(abs(cell.x),abs(cell.y));
        if(radius>64.0)continue;
        vec3 uv=(vec3(cell.x+64.5,h*32.0+0.5,cell.y+64.5))/vec3(129,33,129);
        float coverage=1.0-smoothstep(56.0,64.0,radius);
        float weight=remaining*coverage;
        vec3 s;vec2 a;cloudSourceFetch(cascade,uv,s,a);
        solar+=weight*s;ambient+=weight*a;remaining-=weight;
        if(remaining<=0.0)break;
    }
    return remaining;
}
#endif
#endif
