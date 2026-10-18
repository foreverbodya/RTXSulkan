// Geometry-guided a-trous reconstruction of demodulated diffuse lighting.
// This is a biased spatial estimate, without radiance clipping or variance input.
bool indirectFinite(vec3 value) {return !any(isnan(value)) && !any(isinf(value));}
void main() {
    ivec2 pixel=ivec2(gl_GlobalInvocationID.xy),size=imageSize(Output0);
    if(any(greaterThanEqual(pixel,size))) return;
    vec4 center=texelFetch(Lighting,pixel,0),normal=texelFetch(Normal,pixel,0);
    vec4 position=texelFetch(Position,pixel,0);
    imageStore(Output0,pixel,center);
#ifdef ROUGH_FILTER
    if(ROUGH_DENOISE==0)return;
    float centerRoughness=texelFetch(Roughness,pixel,0).w;
#endif
    if(center.a<1.0 || normal.a<0.5 || !indirectFinite(center.rgb)) return;
    vec3 n=normalize(normal.xyz),relative=position.xyz-RayCameraPosition.xyz;
    float footprint=2.0*length(relative)/(max(abs(Projection[1][1]),0.01)*float(size.y));
    float limit=min(2.0,0.01+4.0*footprint*float(INDIRECT_FILTER_STEP)/max(abs(dot(n,normalize(-relative))),0.1));
    const float kernel[5]=float[5](1.0,4.0,6.0,4.0,1.0);
    vec3 total=vec3(0);float weights=0;
    for(int y=-2;y<=2;y++) for(int x=-2;x<=2;x++) {
        ivec2 q=pixel+ivec2(x,y)*INDIRECT_FILTER_STEP;
        if(any(lessThan(q,ivec2(0))) || any(greaterThanEqual(q,size))) continue;
        vec4 value=texelFetch(Lighting,q,0),qn=texelFetch(Normal,q,0),qp=texelFetch(Position,q,0);
#ifdef ROUGH_FILTER
        if(abs(texelFetch(Roughness,q,0).w-centerRoughness)>.03)continue;
#endif
        if(value.a<1.0 || qn.a<0.5 || qp.w!=position.w || !indirectFinite(value.rgb)) continue;
        vec3 delta=qp.xyz-position.xyz;
        float agreement=dot(n,normalize(qn.xyz)),plane=abs(dot(delta,n));
        if(agreement<=0.98 || plane>0.02 || length(delta)>limit) continue;
        float weight=kernel[x+2]*kernel[y+2]*pow(max(agreement,0.0),32.0)*exp(-plane/0.01);
        total+=value.rgb*weight;weights+=weight;
    }
    // Carry the temporal count, not an invented spatial sample count.
    if(weights>0.0) imageStore(Output0,pixel,vec4(total/weights,center.a));
}
