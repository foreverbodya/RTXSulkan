#ifndef SULKAN_MATERIAL_SURFACE
#define SULKAN_MATERIAL_SURFACE
#ifndef FOLIAGE_SURFACE
#define FOLIAGE_SURFACE 0
#endif
// Authored class presets, not measured resource-pack roughness maps.
// Leaf mode 1 narrows the existing dielectric lobe. No extra light or lobe.
float sulkanMaterialRoughness(uint material) {
#if FOLIAGE_SURFACE == 1
    if(material==5u)return 0.35;
#endif
    return material==6u?0.28:material==7u?0.55:0.72;
}
#endif
