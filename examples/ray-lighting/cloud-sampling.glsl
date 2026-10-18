// Explicit clamped bilinear footprint shared by color and finite-volume guides.
vec2 cinematicCloudFootprint(ivec2 pixel, ivec2 fullSize, ivec2 size, out ivec2 cells[4], out vec4 weights) {
    // Exact integer texel-center mapping before the one fractional division.
    // Avoid two rounded UV operations amplified by neighboring proxy distances.
    // Half-resolution textures and full extents <= 32768 keep this in int32.
    ivec2 denominator=2*fullSize;
    ivec2 numerator=(2*pixel+1)*size-fullSize;
    ivec2 a=numerator/denominator;
    if(numerator.x<0)a.x=-1;
    if(numerator.y<0)a.y=-1;
    ivec2 remainder=numerator-a*denominator;
    precise vec2 f=vec2(remainder)/vec2(denominator);
    // Compute the small complement before rounding f near one. A cancelled
    // 1-f can bias a tiny-opacity distance quotient even at the image centre.
    precise vec2 complement=vec2(denominator-remainder)/vec2(denominator);
    ivec2 b=a+1;
    cells[0]=clamp(a,ivec2(0),size-1);
    cells[1]=clamp(ivec2(b.x,a.y),ivec2(0),size-1);
    cells[2]=clamp(ivec2(a.x,b.y),ivec2(0),size-1);
    cells[3]=clamp(b,ivec2(0),size-1);
    weights=vec4(complement.x*complement.y,f.x*complement.y,complement.x*f.y,f.x*f.y);
    return f;
}
