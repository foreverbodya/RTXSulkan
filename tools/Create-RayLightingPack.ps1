#Requires -Version 7.0
param([ValidateSet(0,1,2,3,5)][int] $RealtimeStep = 0, [Parameter(Mandatory)][string] $Destination, [switch] $HitMaterials, [switch] $TemporalInputs, [switch] $DlssReconstruction, [switch] $Reflections, [switch] $AlphaDiagnostic, [switch] $Atmosphere, [switch] $MultipleScattering, [switch] $FoliageTransmission, [switch] $FoliageDiagnostic, [switch] $IndirectDiagnostic, [switch] $DiffuseIndirect, [switch] $IndirectTemporal, [switch] $IndirectSpatial, [switch] $LeafIndirect, [switch] $AutoExposure, [switch] $WaterHdr, [switch] $WaterDiagnostic, [ValidateRange(1,8)][int] $IndirectBounces = 1, [ValidateSet(0,1,2,3)][int] $DisplayToneMapper = 0, [ValidateSet(1,4,16,64)][int] $IndirectSamples = 1, [switch] $LightingAudit, [switch] $CloudDiagnostic, [switch] $CloudPreview, [switch] $CloudReconstructionGuides, [ValidateSet(0,1)][int] $CloudDensityProfile = 0, [ValidateSet(0,1)][int] $CloudViewDensitySubsteps = 0, [ValidateSet(0,1,2)][int] $FoliageOptics = 0, [switch] $DistantWorld, [switch] $DistantWorldDiagnostic, [ValidateSet(0,1)][int] $DistantWorldMode = 1, [switch] $SolidShadowComparison, [switch] $CloudLightingVolume, [switch] $CloudExactVolume, [switch] $CloudExactEnvironment, [switch] $CloudExactFloat32, [switch] $CloudLightingComparison, [switch] $DistantCloudComparison, [switch] $CloudDepthAware, [switch] $CloudDepthComparison, [ValidateSet(0,1)][int] $LeafBoundary = 0, [switch] $LeafBoundaryComparison, [ValidateSet(1,8)][int] $SunShadowSamples = 1, [switch] $SunDiskComparison, [ValidateSet(1,8)][int] $SunIndirectSamples = 8, [switch] $SunIndirectComparison, [ValidateSet(0,1)][int] $LeafSurface = 0, [switch] $LeafSurfaceComparison, [switch] $RoughReflections, [switch] $RoughReflectionComparison, [switch] $CloudSourceCache, [switch] $CloudSourceComparison, [switch] $NightSky, [switch] $EmissiveMaterials, [ValidateSet(0,1)][int] $EmissiveSampling = 0, [switch] $EmissiveComparison, [ValidateSet(0,1)][int] $RoughDenoise = 0, [switch] $RoughDenoiseComparison)
$ErrorActionPreference = 'Stop'
if($RealtimeStep -ge 3){$IndirectBounces=2;$IndirectSamples=1;$LeafIndirect=$false}

if($CloudExactEnvironment){$CloudExactVolume=$true}
if($DistantWorld -and !$Atmosphere){throw 'DistantWorld requires Atmosphere'}
if($DistantWorldDiagnostic -and !$DistantWorld){throw 'DistantWorldDiagnostic requires DistantWorld'}
if($CloudPreview -and !$Atmosphere){throw 'CloudPreview requires Atmosphere'}
if($CloudViewDensitySubsteps -ne 0 -and !$CloudPreview){throw 'CloudViewDensitySubsteps requires CloudPreview'}
if($CloudReconstructionGuides -and (!$CloudPreview -or !$DlssReconstruction)){throw 'CloudReconstructionGuides requires CloudPreview and DlssReconstruction'}
if($MultipleScattering -and !$Atmosphere){throw 'MultipleScattering requires Atmosphere'}
if($WaterDiagnostic -and !$WaterHdr){throw 'WaterDiagnostic requires WaterHdr'}
if($WaterHdr){$TemporalInputs=$true}
if($AutoExposure -and !$DlssReconstruction){throw 'AutoExposure currently requires the post-DLSS display path'}
if($LeafIndirect) {$IndirectTemporal=$true;$FoliageTransmission=$true}
if($IndirectBounces -gt 1 -or $IndirectSamples -gt 1){$IndirectTemporal=$true}
if($IndirectSpatial) {$IndirectTemporal=$true}
if($IndirectTemporal) {$DiffuseIndirect=$true;$TemporalInputs=$true}
if($DlssReconstruction) { $TemporalInputs=$true }
if($HitMaterials -and $TemporalInputs) {throw 'HitMaterials and TemporalInputs require separate runs to stay within eight color attachments'}
$projectRoot = Split-Path $PSScriptRoot -Parent
$target = [IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $target) { throw 'Destination must be new' }
[IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $projectRoot 'src/client/resources/sulkan-bundled/Sulkan-Realistic.zip'), $target)
$manifestPath = Join-Path $target 'sulkan.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -AsHashtable
$manifest.name = 'Sulkan experimental deferred ray lighting'
$manifest.options['DISPLAY_TONEMAPPER']=@{label='Display: legacy / AgX / shadows / soft shadows';default=$DisplayToneMapper;values=@(0,1,2,3)}
[void][IO.Directory]::CreateDirectory((Join-Path $target 'LICENSES'))
Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/LICENSES/Godot-MIT.txt') -Destination (Join-Path $target 'LICENSES/Godot-MIT.txt')
$manifest.shadows = $false
if($EmissiveSampling -eq 1 -or $EmissiveComparison){$EmissiveMaterials=$true}
if($EmissiveMaterials){$manifest.materials['minecraft:lantern']=15}
if($EmissiveComparison -or $EmissiveSampling -eq 1){
    if(!$DiffuseIndirect){throw 'Emissive sampling requires traced diffuse lighting'}
    $manifest.options['EMISSIVE_SAMPLING']=@{label='Emissive triangles: BSDF / MIS area sampling';default=$EmissiveSampling;values=@(0,1)}
}
if($LeafSurface -eq 1 -or $LeafSurfaceComparison){$manifest.options['FOLIAGE_SURFACE']=@{label='Leaf surface: original / smoother dielectric';default=$LeafSurface;values=@(0,1)}}
if($SunIndirectSamples -eq 1 -and $SunShadowSamples -ne 8){throw 'Sampled indirect sun requires disk shadows'}
if($SunShadowSamples -eq 8 -or $SunIndirectComparison){$manifest.options['SUN_INDIRECT_SAMPLES']=@{label='Sun samples at indirect vertices: sampled / full disk';default=$SunIndirectSamples;values=@(1,8)}}
if($SunShadowSamples -gt 1 -or $SunDiskComparison){$manifest.options['SUN_SHADOW_SAMPLES']=@{label='Sun occlusion: point / eight disk directions';default=$SunShadowSamples;values=@(1,8)}}
# Exposed only in an explicitly generated QA comparison pack.
if($SolidShadowComparison){$manifest.options['SULKAN_SOLID_SHADOW_PREFLIGHT']=@{default=0;values=@(0,1)}}
$manifest.rayCoordinates = 'camera_relative'
foreach ($name in @('gbuffer.albedo','gbuffer.normal','gbuffer.emission','ray.direct.hdr')) {
    $manifest.resources[$name] = @{ format = 'RGBA16_FLOAT' }
}
$manifest.resources['ray.direct.display'] = @{ format = 'RGBA8_UNORM' }
$manifest.resources['gbuffer.material'] = @{format='RG32_UINT'}
$manifest.sceneTargets += @('gbuffer.albedo','gbuffer.normal','gbuffer.emission')
$manifest.sceneTargets += 'gbuffer.material'
foreach ($program in $manifest.scenePrograms) {
    if ($program.pipeline -in @('sodium:pipeline/solid_terrain','sodium:pipeline/cutout_terrain')) {
        $program.fragment = 'shaders/terrain-gbuffer.fsh'
        $program.vertex = 'shaders/terrain-material.vsh'
        $program.writes = @('surface.data','gbuffer.albedo','gbuffer.normal','gbuffer.emission','gbuffer.material')
    }
    elseif ($program.pipeline -eq 'sodium:pipeline/translucent_terrain') {
        $program.fragment = 'shaders/translucent-fallback.fsh'
        $program.writes = @('surface.data')
    }
}
$manifest.passes += @(
    @{ name = 'ray.direct'; compute = 'shaders/ray-direct.comp'; rayScene = $true; rayAttributes = $true; localSize = @(8,8,1);
       reads = @{ Depth = '$depth'; Scene = '$scene'; Surface = 'surface.data'; Albedo = 'gbuffer.albedo';
                  NormalRoughness = 'gbuffer.normal'; EmissionMetal = 'gbuffer.emission'; Atlas = '$terrainAtlas'; Material='gbuffer.material' }; writes = @('ray.direct.hdr') },
    @{ name = 'ray.direct.present'; fragment = 'shaders/ray-direct-present.fsh';
       reads = @{ HDR = 'ray.direct.hdr' }; writes = @('ray.direct.display') }
)
$manifest.present = 'ray.direct.display'
if($IndirectDiagnostic -or $DiffuseIndirect) {
    $manifest.options['INDIRECT_SAMPLES']=@{label='Diffuse paths per pixel per lobe';default=$IndirectSamples;values=@(if($IndirectDiagnostic){$IndirectSamples}else{1;4;16;64})}
    $manifest.options['INDIRECT_BOUNCES']=@{default=$IndirectBounces;values=@(if($IndirectDiagnostic){$IndirectBounces}else{1;2;3;4;5;6;7;8})}
    # End-frame FP32 transport and filter readbacks pin full history images.
    # Their debug budget is distinct from the inherited production budget.
    if($IndirectDiagnostic){$manifest.budgetMiB=2048}
    $manifest.resources['indirect.raw']=@{format=$(if($IndirectDiagnostic){'RGBA32_FLOAT'}else{'RGBA16_FLOAT'});history=[bool]$IndirectDiagnostic}
    $indirectWrites=@('indirect.raw')
    if($IndirectDiagnostic) {
        $manifest.resources['indirect.sampling.contract']=@{format='RGBA32_FLOAT';size=@(4096,4,1);history=$true}
        $manifest.resources['indirect.sampling.legacy']=@{format='RGBA32_FLOAT';size=@(4096,4,1);history=$true}
        $manifest.passes+=@{name='indirect.sampling.contract';compute='shaders/indirect-sampling-diagnostic.comp';localSize=@(64,1,1);reads=@{};writes=@('indirect.sampling.contract','indirect.sampling.legacy')}
        # Diagnostic roots must remain in the graph's dependency closure.
        ($manifest.passes|Where-Object{$_.name -eq 'ray.direct'}).reads['DiagnosticSamplingContract']='indirect.sampling.contract'
        ($manifest.passes|Where-Object{$_.name -eq 'ray.direct'}).reads['DiagnosticSamplingLegacy']='indirect.sampling.legacy'
        Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/indirect-sampling-diagnostic.comp') -Destination (Join-Path $target 'shaders/indirect-sampling-diagnostic.comp')
        $manifest.options['RAY_INDIRECT_DIAGNOSTIC']=@{default=1;values=@(1)}
        foreach($name in @('indirect.hit','indirect.direction','indirect.incoming')) {
            # Readbacks happen after the whole graph, beyond ordinary last-consumer liveness.
            $manifest.resources[$name]=@{format='RGBA32_FLOAT';history=$true}
        }
        $indirectWrites+=@('indirect.hit','indirect.direction','indirect.incoming')
        if($IndirectBounces -ge 2){foreach($name in @('indirect.second.hit','indirect.second.direction')){$manifest.resources[$name]=@{format='RGBA32_FLOAT';history=$true};$indirectWrites+=$name}}
    }
    $manifest.passes+=@{name='indirect.trace';compute='shaders/indirect.comp';rayScene=$true;rayAttributes=$true;localSize=@(8,8,1);
        reads=@{Depth='$depth';Surface='surface.data';Material='gbuffer.material';NormalRoughness='gbuffer.normal';EmissionMetal='gbuffer.emission';Albedo='gbuffer.albedo';Atlas='$terrainAtlas'};
        writes=$indirectWrites}
    if($DiffuseIndirect) {
        $manifest.options['DIFFUSE_INDIRECT']=@{default=1;values=@(1)}
        ($manifest.passes|Where-Object{$_.name -eq 'ray.direct'}).reads['Indirect']='indirect.raw'
    } else {($manifest.passes|Where-Object{$_.name -eq 'ray.direct'}).reads['DiagnosticIndirect']='indirect.raw'}
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/indirect.comp') -Destination (Join-Path $target 'shaders/indirect.comp')
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/indirect-sampling.glsl') -Destination (Join-Path $target 'shaders/indirect-sampling.glsl')
}
if($FoliageOptics -ne 0 -and !$FoliageTransmission){throw 'FoliageOptics requires FoliageTransmission or LeafIndirect'}
if($LeafBoundary -ne 0 -and !$FoliageTransmission){throw 'LeafBoundary requires foliage transmission'}
if($FoliageTransmission -and ($LeafBoundary -ne 0 -or $LeafBoundaryComparison)){
    $manifest.options['LEAF_RADIANCE_BOUNDARY']=@{label='Leaf radiance: two-sided / entry boundary proxy';default=$LeafBoundary;values=@(0,1)}
}
if($FoliageTransmission){$manifest.options['FOLIAGE_OPTICS']=@{label='Foliage absorption: original / spectral / preserve tint';default=$FoliageOptics;values=@(0,1,2)}}
if($FoliageDiagnostic -or $LightingAudit) {
    $manifest.resources['foliage.optics']=@{format='RGBA32_FLOAT';size=@(4096,2,1);history=$true}
    $manifest.passes+=@{name='foliage.optics';compute='shaders/leaf-optics-diagnostic.comp';localSize=@(64,1,1);reads=@{};writes=@('foliage.optics')}
    ($manifest.passes|Where-Object{$_.name -eq 'ray.direct'}).reads['DiagnosticLeafOptics']='foliage.optics'
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/leaf-optics-diagnostic.comp') -Destination (Join-Path $target 'shaders/leaf-optics-diagnostic.comp')
}
if($FoliageTransmission) { $manifest.options['FOLIAGE_TRANSMISSION']=@{default=1;values=@(1)} }
if($FoliageDiagnostic) {
    if(!$FoliageTransmission){throw 'FoliageDiagnostic requires FoliageTransmission'}
    # QA reads these after the entire graph, beyond their ordinary consumers.
    $manifest.resources['foliage.shadow']=@{format='RGBA32_FLOAT';history=$true}
    $manifest.resources['foliage.first']=@{format='RGBA32_FLOAT';history=$true}
    $manifest.passes+=@{name='foliage.diagnostic';compute='shaders/foliage-diagnostic.comp';rayScene=$true;rayAttributes=$true;localSize=@(8,8,1);
        reads=@{Surface='surface.data';NormalRoughness='gbuffer.normal';Material='gbuffer.material';Atlas='$terrainAtlas'};writes=@('foliage.shadow','foliage.first')}
    ($manifest.passes|Where-Object{$_.name -eq 'ray.direct'}).reads['DiagnosticFoliage']='foliage.shadow'
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/foliage-diagnostic.comp') -Destination (Join-Path $target 'shaders/foliage-diagnostic.comp')
}
if ($AlphaDiagnostic) {
    $manifest.options['RAY_ALPHA_DIAGNOSTIC']=@{default=1;values=@(1)}
    $manifest.options['VEGETATION_WIND'].default=0
    $manifest.resources['ray.alpha.metadata']=@{format='RGBA32_FLOAT';history=$true}
    $manifest.resources['ray.alpha.normal']=@{format='RGBA32_FLOAT';history=$true}
    $manifest.passes += @{
        name='ray.alpha';compute='shaders/alpha-diagnostic.comp';rayScene=$true;rayAttributes=$true;localSize=@(8,8,1);
        reads=@{Atlas='$terrainAtlas'};writes=@('ray.alpha.metadata','ray.alpha.normal')
    }
    ($manifest.passes | Where-Object {$_.name -eq 'ray.direct'}).reads['DiagnosticAlpha']='ray.alpha.metadata'
    ($manifest.passes | Where-Object {$_.name -eq 'ray.direct'}).reads['DiagnosticAlphaNormal']='ray.alpha.normal'
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/alpha-diagnostic.comp') -Destination (Join-Path $target 'shaders/alpha-diagnostic.comp')
}
$hdrResource = 'ray.direct.hdr'
if ($Reflections) {
    $manifest.options['SMOOTH_METAL_REFLECTIONS'] = @{ default = 1; values = @(1) }
    $manifest.resources['ray.reflection.hdr'] = @{ format = 'RGBA16_FLOAT' }
    $manifest.resources['ray.reflection.hit'] = @{ format = 'RGBA32_FLOAT' }
    $manifest.resources['ray.composite.hdr'] = @{ format = 'RGBA16_FLOAT' }
    $manifest.passes += @{
        name='ray.reflection';compute='shaders/reflections.comp';rayScene=$true;rayAttributes=$true;localSize=@(8,8,1);
        reads=@{Depth='$depth';Surface='surface.data';NormalRoughness='gbuffer.normal';EmissionMetal='gbuffer.emission';Albedo='gbuffer.albedo';Atlas='$terrainAtlas'};
        writes=@('ray.reflection.hdr','ray.reflection.hit')
    }
    $manifest.passes += @{
        name='ray.composite';compute='shaders/reflection-composite.comp';localSize=@(8,8,1);
        reads=@{Direct='ray.direct.hdr';Reflection='ray.reflection.hdr';Hit='ray.reflection.hit'};writes=@('ray.composite.hdr')
    }
    $hdrResource='ray.composite.hdr'
    ($manifest.passes | Where-Object {$_.name -eq 'ray.direct.present'}).reads.HDR=$hdrResource
    foreach($file in @('reflections.comp','reflection-composite.comp')) {
        Copy-Item -LiteralPath (Join-Path $projectRoot ('examples/ray-lighting/'+$file)) -Destination (Join-Path $target ('shaders/'+$file))
    }
}
if($RoughReflections -or $RoughReflectionComparison) {
    if(!$Reflections){throw 'Rough reflections require Reflections'}
    $manifest.options['ROUGH_SPECULAR']=@{label='Traced rough specular';default=$(if($RoughReflectionComparison){0}else{1});values=@(0,1)}
    $manifest.resources['rough.reflection.hdr']=@{format='RGBA16_FLOAT';history=[bool]$RoughReflectionComparison}
    $manifest.resources['rough.reflection.hit']=@{format='RGBA32_FLOAT';history=[bool]$RoughReflectionComparison}
    $manifest.resources['rough.composite.hdr']=@{format='RGBA16_FLOAT';history=[bool]$RoughReflectionComparison}
    if($RoughReflectionComparison){$manifest.resources[$hdrResource].history=$true}
    $manifest.passes+=@{name='rough.reflection';compute='shaders/rough-reflections.comp';rayScene=$true;rayAttributes=$true;localSize=@(8,8,1);
        reads=@{Depth='$depth';Surface='surface.data';Material='gbuffer.material';NormalRoughness='gbuffer.normal';EmissionMetal='gbuffer.emission';Albedo='gbuffer.albedo';Atlas='$terrainAtlas'};
        writes=@('rough.reflection.hdr','rough.reflection.hit')}
    $manifest.passes+=@{name='rough.composite';compute='shaders/reflection-composite.comp';localSize=@(8,8,1);
        reads=@{Direct=$hdrResource;Reflection='rough.reflection.hdr';Hit='rough.reflection.hit'};writes=@('rough.composite.hdr')}
    $hdrResource='rough.composite.hdr'
    ($manifest.passes|Where-Object{$_.name -eq 'ray.direct.present'}).reads.HDR=$hdrResource
    foreach($file in @('rough-reflections.comp','ggx-reflection.glsl','indirect-sampling.glsl')) {
        Copy-Item -LiteralPath (Join-Path $projectRoot ('examples/ray-lighting/'+$file)) -Destination (Join-Path $target ('shaders/'+$file))
    }
}
if ($HitMaterials) {
    $manifest.options['RAY_HIT_MATERIAL_DIAGNOSTIC'] = @{ default = 1; values = @(1) }
    $manifest.resources['gbuffer.ray-reference'] = @{ format = 'RGBA16_FLOAT' }
    $manifest.sceneTargets += 'gbuffer.ray-reference'
    $manifest.resources['gbuffer.ray-coordinates'] = @{ format = 'RGBA32_FLOAT' }
    $manifest.sceneTargets += 'gbuffer.ray-coordinates'
    foreach ($program in $manifest.scenePrograms) {
        if ($program.pipeline -in @('sodium:pipeline/solid_terrain','sodium:pipeline/cutout_terrain')) {
            $program.writes += 'gbuffer.ray-reference'
            $program.writes += 'gbuffer.ray-coordinates'
        }
    }
    $manifest.resources['ray.hit.albedo'] = @{ format = 'RGBA16_FLOAT'; history = $true }
    $manifest.resources['ray.hit.metadata'] = @{ format = 'RGBA32_FLOAT'; history = $true }
    $manifest.resources['ray.hit.coordinates'] = @{ format = 'RGBA32_FLOAT'; history = $true }
    $manifest.passes += @{
        name = 'ray.hit.materials'; compute = 'shaders/ray-hit-materials.comp';
        rayScene = $true; rayAttributes = $true; localSize = @(8,8,1);
        reads = @{ Depth = '$depth'; Atlas = '$terrainAtlas' };
        writes = @('ray.hit.albedo','ray.hit.metadata','ray.hit.coordinates')
    }
    # Keep diagnostic outputs in the graph's dependency closure of the present pass.
    $lightingPass = $manifest.passes | Where-Object { $_.name -eq 'ray.direct' }
    $lightingPass.reads['DiagnosticHitAlbedo'] = 'ray.hit.albedo'
    $lightingPass.reads['DiagnosticHitMetadata'] = 'ray.hit.metadata'
    $lightingPass.reads['DiagnosticHitReference'] = 'gbuffer.ray-reference'
    $lightingPass.reads['DiagnosticHitCoordinates'] = 'ray.hit.coordinates'
    $lightingPass.reads['DiagnosticRasterCoordinates'] = 'gbuffer.ray-coordinates'
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/hit-materials.comp') -Destination (Join-Path $target 'shaders/ray-hit-materials.comp')
}
if ($TemporalInputs) {
    $manifest.options['CAMERA_MOTION_DIAGNOSTIC']=@{default=1;values=@(1)}
    $manifest.resources['motion.source']=@{format='RG32_UINT'}
    # Diagnostic double buffering also exercises last-rendered history readback while the camera moves.
    $manifest.resources['motion.data']=@{format='RG16_FLOAT';history=$true}
    $manifest.resources['motion.valid']=@{format='R32_UINT';history=$true}
    $manifest.sceneTargets += 'motion.source'
    foreach ($program in $manifest.scenePrograms) {
        if ($program.pipeline -in @('sodium:pipeline/solid_terrain','sodium:pipeline/cutout_terrain')) { $program.writes += 'motion.source' }
    }
    $manifest.passes += @{
        name='motion.camera'; compute='shaders/camera-motion.comp';localSize=@(8,8,1);
        reads=@{Depth='$depth';MotionSource='motion.source';Surface='surface.data'};
        writes=@('motion.data','motion.valid')
    }
    $lightingPass=$manifest.passes | Where-Object {$_.name -eq 'ray.direct'}
    $lightingPass.reads['DiagnosticMotion']='motion.data'
    $lightingPass.reads['DiagnosticMotionValid']='motion.valid'
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/camera-motion.comp') -Destination (Join-Path $target 'shaders/camera-motion.comp')
}
if($IndirectTemporal) {
    $manifest.options['INDIRECT_TEMPORAL']=@{default=1;values=@(1)}
    $manifest.resources['indirect.accum']=@{format='RGBA16_FLOAT';history=$true}
    $manifest.resources['indirect.position']=@{format='RGBA32_FLOAT';history=$true}
    $manifest.resources['indirect.normal']=@{format='RGBA16_FLOAT';history=$true}
    $manifest.resources['indirect.state']=@{format='RGBA32_UINT';size=@(1,1,1);history=$true}
    $manifest.resources['indirect.environment']=@{format='RGBA32_FLOAT';size=@(3,1,1);history=$true}
    $manifest.passes+=@{name='indirect.state';compute='shaders/indirect-state.comp';localSize=@(1,1,1);reads=@{};writes=@('indirect.state')}
    $manifest.passes+=@{name='indirect.environment';compute='shaders/indirect-environment.comp';localSize=@(1,1,1);reads=@{};writes=@('indirect.environment')}
    $manifest.passes+=@{name='indirect.temporal';compute='shaders/indirect-temporal.comp';localSize=@(8,8,1);
        reads=@{Sample='indirect.raw';Surface='surface.data';Material='gbuffer.material';NormalRoughness='gbuffer.normal';Motion='motion.data';MotionValid='motion.valid';
            State='indirect.state';Environment='indirect.environment';
            PreviousLighting='indirect.accum@previous';PreviousPosition='indirect.position@previous';PreviousNormal='indirect.normal@previous';PreviousState='indirect.state@previous';PreviousEnvironment='indirect.environment@previous'};
        writes=@('indirect.accum','indirect.position','indirect.normal')}
    ($manifest.passes|Where-Object{$_.name -eq 'ray.direct'}).reads['Indirect']='indirect.accum'
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/indirect-temporal.comp') -Destination (Join-Path $target 'shaders/indirect-temporal.comp')
    foreach($file in @('indirect-state.comp','indirect-environment.comp')) {
        Copy-Item -LiteralPath (Join-Path $projectRoot ('examples/ray-lighting/'+$file)) -Destination (Join-Path $target ('shaders/'+$file))
    }
}
if($LeafIndirect) {
    # Traced leaves and camera-only history require undeformed geometry for now.
    $manifest.options['VEGETATION_WIND'].default=0
    $manifest.options['VEGETATION_WIND'].values=@(0)
    $manifest.options['LEAF_INDIRECT']=@{default=1;values=@(1)}
    $backTrace=($manifest.passes|Where-Object{$_.name -eq 'indirect.trace'}) | ConvertTo-Json -Depth 16 | ConvertFrom-Json -AsHashtable
    $backTrace.name='leaf.indirect.trace';$backTrace.compute='shaders/leaf-indirect.comp'
    $backTrace.writes=@('leaf.indirect.raw')
    $manifest.resources['leaf.indirect.raw']=@{format=$(if($IndirectDiagnostic){'RGBA32_FLOAT'}else{'RGBA16_FLOAT'});history=[bool]$IndirectDiagnostic}
    if($IndirectDiagnostic) {
        $backDiagnosticNames=@('leaf.indirect.hit','leaf.indirect.direction','leaf.indirect.incoming')
        if($IndirectBounces -ge 2){$backDiagnosticNames+=@('leaf.indirect.second.hit','leaf.indirect.second.direction')}
        foreach($name in $backDiagnosticNames) {
            $manifest.resources[$name]=@{format='RGBA32_FLOAT';history=$true};$backTrace.writes+=@($name)
        }
    }
    $manifest.passes+=@($backTrace)
    $backTemporal=($manifest.passes|Where-Object{$_.name -eq 'indirect.temporal'}) | ConvertTo-Json -Depth 16 | ConvertFrom-Json -AsHashtable
    $backTemporal.name='leaf.indirect.temporal';$backTemporal.compute='shaders/leaf-indirect-temporal.comp'
    $backTemporal.reads.Sample='leaf.indirect.raw';$backTemporal.reads.PreviousLighting='leaf.indirect.accum@previous'
    $backTemporal.writes=@('leaf.indirect.accum')
    $manifest.resources['leaf.indirect.accum']=@{format='RGBA16_FLOAT';history=$true}
    $manifest.passes+=@($backTemporal)
    ($manifest.passes|Where-Object{$_.name -eq 'ray.direct'}).reads['IndirectTransmission']='leaf.indirect.accum'
    foreach($variant in @(@('indirect.comp','leaf-indirect.comp'),@('indirect-temporal.comp','leaf-indirect-temporal.comp'))) {
        (Get-Content -LiteralPath (Join-Path $projectRoot ('examples/ray-lighting/'+$variant[0])) -Raw).Replace('#version 460',"#version 460`n#define INDIRECT_BACK_LOBE 1") | Set-Content -LiteralPath (Join-Path $target ('shaders/'+$variant[1]))
    }
}
if($RoughDenoise -eq 1 -or $RoughDenoiseComparison){
    if(!$RoughReflections -or !$IndirectTemporal){throw 'Rough denoising requires rough reflections and indirect temporal guides'}
    $manifest.options['ROUGH_DENOISE']=@{label='Rough specular reconstruction';default=$RoughDenoise;values=@(0,1)}
    $manifest.resources['rough.reflection.hdr'].history=$true
    $roughPrevious='rough.reflection.hdr'
    foreach($step in @(1,2)){
        $name="rough.prefilter.$step";$manifest.resources[$name]=@{format='RGBA16_FLOAT'}
        $manifest.passes+=@{name=$name;compute="shaders/rough-prefilter-$step.comp";localSize=@(8,8,1);
            reads=@{Lighting=$roughPrevious;Position='indirect.position';Normal='indirect.normal';Roughness='gbuffer.normal'};writes=@($name)}
        Set-Content -LiteralPath (Join-Path $target "shaders/rough-prefilter-$step.comp") -Value "#version 460`n#define ROUGH_FILTER 1`n#define INDIRECT_FILTER_STEP $step`n#include `"shaders/indirect-spatial.glsl`""
        $roughPrevious=$name
    }
    foreach($entry in @(@('rough.denoised','RGBA16_FLOAT'),@('rough.position','RGBA32_FLOAT'),@('rough.normal','RGBA16_FLOAT'))){
        $manifest.resources[$entry[0]]=@{format=$entry[1];history=$true}
    }
    $roughTemporal=($manifest.passes|Where-Object{$_.name -eq 'indirect.temporal'})|ConvertTo-Json -Depth 16|ConvertFrom-Json -AsHashtable
    $roughTemporal.name='rough.temporal';$roughTemporal.compute='shaders/rough-temporal.comp'
    $roughTemporal.reads.Sample=$roughPrevious;$roughTemporal.reads.PreviousLighting='rough.denoised@previous'
    $roughTemporal.reads.PreviousPosition='rough.position@previous';$roughTemporal.reads.PreviousNormal='rough.normal@previous'
    $roughTemporal.writes=@('rough.denoised','rough.position','rough.normal');$manifest.passes+=@($roughTemporal)
    ($manifest.passes|Where-Object{$_.name -eq 'rough.composite'}).reads.Reflection='rough.denoised'
    (Get-Content -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/indirect-temporal.comp') -Raw).Replace('#version 460',"#version 460`n#define ROUGH_TEMPORAL 1")|Set-Content -LiteralPath (Join-Path $target 'shaders/rough-temporal.comp')
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/indirect-spatial.glsl') -Destination (Join-Path $target 'shaders/indirect-spatial.glsl')
}
if($IndirectSpatial) {
    $fields=@(@{prefix='indirect';binding='Indirect'})
    if($LeafIndirect){$fields+=@{prefix='leaf.indirect';binding='IndirectTransmission'}}
    foreach($field in $fields) {
    $previous=$field.prefix+'.accum'
    foreach($step in @(1,2,4)) {
        $name=$field.prefix+".spatial.$step"
        # Diagnostic end-frame readbacks outlive ordinary graph consumers.
        $manifest.resources[$name]=@{format='RGBA16_FLOAT';history=[bool]$IndirectDiagnostic}
        $manifest.passes+=@{name=$name;compute="shaders/indirect-spatial-$step.comp";localSize=@(8,8,1);
            reads=@{Lighting=$previous;Position='indirect.position';Normal='indirect.normal'};writes=@($name)}
        Set-Content -LiteralPath (Join-Path $target "shaders/indirect-spatial-$step.comp") -Value "#version 460`n#define INDIRECT_FILTER_STEP $step`n#include `"shaders/indirect-spatial.glsl`""
        $previous=$name
    }
    ($manifest.passes|Where-Object{$_.name -eq 'ray.direct'}).reads[$field.binding]=$previous
    }
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/indirect-spatial.glsl') -Destination (Join-Path $target 'shaders/indirect-spatial.glsl')
}
if($WaterHdr) {
    $manifest.options['WATER_HDR']=@{default=1;values=@(1)}
    $manifest.resources['water.surface']=@{format='RGBA32_FLOAT'}
    $manifest.sceneTargets+=@('water.surface')
    foreach($program in $manifest.scenePrograms | Where-Object {$_.pipeline -eq 'sodium:pipeline/translucent_terrain'}) {
        $program.fragment='shaders/translucent-water.fsh';$program.writes+=@('motion.source','water.surface')
    }
    $manifest.resources['water.radiance']=@{format='RGBA16_FLOAT';history=[bool]$WaterDiagnostic}
    $manifest.resources['water.composite.hdr']=@{format='RGBA16_FLOAT'}
    $outputs=@('water.radiance')
    if($WaterDiagnostic) {
        $manifest.budgetMiB=2048
        $manifest.resources[$hdrResource].history=$true
        foreach($name in @('ray.direct.hdr','ray.reflection.hdr','ray.reflection.hit')) {
            if($manifest.resources.Contains($name)){$manifest.resources[$name].history=$true}
        }
        $manifest.options['WATER_DIAGNOSTIC']=@{default=1;values=@(1)}
        foreach($name in @('water.reflection.hit','water.refraction.hit','water.optics','water.primary')) {
            $manifest.resources[$name]=@{format='RGBA32_FLOAT';history=$true};$outputs+=@($name)
        }
    }
    $manifest.passes+=@{name='water.trace';compute='shaders/water.comp';rayScene=$true;rayAttributes=$true;localSize=@(8,8,1);
        reads=@{Water='water.surface';Surface='surface.data';Depth='$depth';Atlas='$terrainAtlas'};writes=$outputs}
    $manifest.passes+=@{name='water.composite';compute='shaders/water-composite.comp';localSize=@(8,8,1);
        reads=@{Water='water.radiance';Base=$hdrResource};writes=@('water.composite.hdr')}
    $hdrResource='water.composite.hdr'
    ($manifest.passes|Where-Object{$_.name -eq 'ray.direct.present'}).reads.HDR=$hdrResource
    foreach($name in @('translucent-water.fsh','water.comp','water-composite.comp','water-optics.glsl')) {
        Copy-Item -LiteralPath (Join-Path $projectRoot ('examples/ray-lighting/'+$name)) -Destination (Join-Path $target ('shaders/'+$name))
    }
}
if($CloudPreview) {
    $manifest.budgetMiB=2048
    $manifest.options['CLOUD_DEPTH_AWARE']=@{label='Depth-aware cloud composition';default=[int][bool]($CloudDepthAware -or !$CloudDepthComparison);values=@(0,1)}
    $manifest.options['CLOUD_PREVIEW_STILL']=@{label='Static cloud development preview';default=1;values=@(1)}
    $manifest.options['CLOUD_AMBIENT_OCCLUSION']=@{label='Cloud ambient column visibility';default=1;values=@(0,1)}
    $manifest.options['CLOUD_VIEW_DENSITY_SUBSTEPS']=@{label='Cloud view density: midpoint / refined column';default=$CloudViewDensitySubsteps;values=@(0,1)}
    if($CloudDiagnostic) {
        foreach($name in @('cloud.column.reference','cloud.column.origin','cloud.column.direction')){$manifest.resources[$name]=@{format='RGBA32_FLOAT';size=@(32,16,1);history=$true}}
        $manifest.resources['cloud.column.samples']=@{format='RGBA32_FLOAT';size=@(32,512,1);history=$true}
        $manifest.passes+=@{name='cloud.column.diagnostic';compute='shaders/cloud-column-diagnostic.comp';localSize=@(8,8,1);
            reads=@{CloudLayout='$texture/vanilla-clouds';Positions='cloud.position.reference'};
            writes=@('cloud.column.reference','cloud.column.origin','cloud.column.direction')}
        $manifest.passes+=@{name='cloud.column.samples';compute='shaders/cloud-column-samples.comp';localSize=@(8,8,1);
            reads=@{CloudLayout='$texture/vanilla-clouds';Positions='cloud.position.reference'};writes=@('cloud.column.samples')}
    }
    foreach($name in @('cloud.ambient.reference','cloud.ambient.position')){$manifest.resources[$name]=@{format='RGBA32_FLOAT';size=@(128,64,1);history=$true}}
    $manifest.passes+=@{name='cloud.ambient.diagnostic';compute='shaders/cloud-ambient-diagnostic.comp';localSize=@(8,8,1);
        reads=@{CloudLayout='$texture/vanilla-clouds'};writes=@('cloud.ambient.reference','cloud.ambient.position')}
    foreach($name in @('cloud.scattering.reference','cloud.scattering.parameters')){$manifest.resources[$name]=@{format='RGBA32_FLOAT';size=@(32,64,1);history=$true}}
    $manifest.passes+=@{name='cloud.scattering.diagnostic';compute='shaders/cloud-scattering-diagnostic.comp';localSize=@(8,8,1);
        reads=@{};writes=@('cloud.scattering.reference','cloud.scattering.parameters')}
    $manifest.resources['cloud.shadow']=@{format='R32_FLOAT';size=@(512,512,1);history=$true}
    $manifest.passes+=@{name='cloud.shadow';compute='shaders/cloud-shadow.comp';localSize=@(8,8,1);
        reads=@{CloudLayout='$texture/vanilla-clouds'};writes=@('cloud.shadow')}
    foreach($name in @('cloud.shadow.reference','cloud.shadow.position')){$manifest.resources[$name]=@{format='RGBA32_FLOAT';size=@(128,64,1);history=$true}}
    $manifest.passes+=@{name='cloud.shadow.diagnostic';compute='shaders/cloud-shadow-diagnostic.comp';localSize=@(8,8,1);
        reads=@{CloudLayout='$texture/vanilla-clouds';CloudShadow='cloud.shadow'};writes=@('cloud.shadow.reference','cloud.shadow.position')}
    ($manifest.passes|Where-Object{$_.name -eq 'ray.direct'}).reads['CloudShadowReference']='cloud.shadow.reference'
    ($manifest.passes|Where-Object{$_.name -eq 'ray.direct'}).reads['CloudShadowPositions']='cloud.shadow.position'
    $manifest.resources[$hdrResource].history=$true
    $manifest.resources['cloud.view']=@{format='RGBA16_FLOAT';scale=0.5;history=$true}
    $manifest.resources['cloud.geometry']=@{format='RGBA32_FLOAT';scale=0.5;history=$true}
    $manifest.resources['cloud.environment']=@{format='RGBA16_FLOAT';size=@(256,128,1);history=$true}
    $manifest.resources['cloud.lighting']=@{format='RGBA32_FLOAT';size=@($(if($MultipleScattering){11}else{10}),1,1);history=$true}
    $manifest.resources['cloud.composite.hdr']=@{format='RGBA16_FLOAT';history=$true}
    foreach($name in @('cloud.compose.coefficients','cloud.compose.depth','cloud.compose.proxy')){$manifest.resources[$name]=@{format='RGBA32_FLOAT';history=$true}}
    $manifest.passes+=@{name='cloud.view';compute='shaders/cloud-view.comp';localSize=@(8,8,1);
        reads=@{Depth='$depth';CloudLayout='$texture/vanilla-clouds';ClearLighting='atmosphere.lighting'};writes=@('cloud.view','cloud.geometry')}
    $manifest.passes+=@{name='cloud.environment';compute='shaders/cloud-environment.comp';localSize=@(8,8,1);
        reads=@{CloudLayout='$texture/vanilla-clouds';ClearSky='atmosphere.sky';ClearLighting='atmosphere.lighting'};writes=@('cloud.environment')}
    $manifest.passes+=@{name='cloud.lighting';compute='shaders/cloud-lighting.comp';localSize=@(1,1,1);
        reads=@{CloudSky='cloud.environment';ClearLighting='atmosphere.lighting'};writes=@('cloud.lighting')}
    $manifest.passes+=@{name='cloud.composite';compute='shaders/cloud-composite.comp';localSize=@(8,8,1);
        reads=@{Base=$hdrResource;Depth='$depth';ClearLighting='atmosphere.lighting';CloudLayout='$texture/vanilla-clouds';CloudView='cloud.view';CloudGeometry='cloud.geometry';ScatteringReference='cloud.scattering.reference';ScatteringParameters='cloud.scattering.parameters';AmbientReference='cloud.ambient.reference';AmbientPositions='cloud.ambient.position'};
        writes=@('cloud.composite.hdr','cloud.compose.coefficients','cloud.compose.depth','cloud.compose.proxy')}
    if($CloudDiagnostic) {
        # Keep opt-in diagnostic outputs reachable for completed-frame readback.
        ($manifest.passes|Where-Object{$_.name -eq 'cloud.composite'}).reads['ColumnReference']='cloud.column.reference'
        ($manifest.passes|Where-Object{$_.name -eq 'cloud.composite'}).reads['ColumnSamples']='cloud.column.samples'
    }
    if($WaterHdr){foreach($pass in $manifest.passes|Where-Object{$_.name -in @('cloud.view','cloud.composite')}){$pass.reads['Water']='water.surface'}}
    $hdrResource='cloud.composite.hdr'
    ($manifest.passes|Where-Object{$_.name -eq 'ray.direct.present'}).reads.HDR=$hdrResource
    foreach($name in @('cloud-view.comp','cloud-view-density.glsl','cloud-column-diagnostic.comp','cloud-column-samples.comp','cloud-environment.comp','cloud-lighting.comp','cloud-composite.comp','cloud-sampling.glsl','cloud-shadow.comp','cloud-shadow-diagnostic.comp','cloud-scattering-diagnostic.comp','cloud-ambient-diagnostic.comp')) {
        Copy-Item -LiteralPath (Join-Path $projectRoot ('examples/ray-lighting/'+$name)) -Destination (Join-Path $target ('shaders/'+$name))
    }
}
if($DlssReconstruction) {
    $manifest.resources['reconstruction.depth']=@{format='R32_FLOAT'}
    $manifest.resources['reconstruction.exposure']=@{format='R32_FLOAT';size=@(1,1,1)}
    $manifest.passes += @{
        name='reconstruction.inputs';compute='shaders/reconstruction-inputs.comp';localSize=@(8,8,1);
        reads=@{Depth='$depth'};writes=@('reconstruction.depth')
    }
    $manifest.passes += @{
        name='reconstruction.exposure';compute='shaders/reconstruction-exposure.comp';localSize=@(1,1,1);
        reads=@{};writes=@('reconstruction.exposure')
    }
    $manifest.present=$hdrResource
    $manifest.reconstruction=@{type='dlss_quality';color=$hdrResource;depth='reconstruction.depth';motion='motion.data';exposure='reconstruction.exposure';display='shaders/reconstruction-display.fsh'}
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/reconstruction-inputs.comp') -Destination (Join-Path $target 'shaders/reconstruction-inputs.comp')
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/reconstruction-exposure.comp') -Destination (Join-Path $target 'shaders/reconstruction-exposure.comp')
    (Get-Content -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/present.fsh') -Raw).Replace('HDR','Source') | Set-Content -LiteralPath (Join-Path $target 'shaders/reconstruction-display.fsh')
    if($WaterHdr){($manifest.passes|Where-Object{$_.name -eq 'reconstruction.inputs'}).reads['Water']='water.surface'}
    if($CloudPreview) {
        $manifest.options['CLOUD_RECONSTRUCTION_GUIDES']=@{label='Experimental cloud reconstruction guides';default=[int][bool]$CloudReconstructionGuides;values=@(0,1)}
        $manifest.resources['cloud.reconstruction.depth']=@{format='R32_FLOAT';history=$true}
        $manifest.resources['cloud.reconstruction.motion']=@{format='RG16_FLOAT';history=$true}
        $manifest.resources['cloud.reconstruction.valid']=@{format='R32_UINT';history=$true}
        $manifest.resources['cloud.reconstruction.guide']=@{format='RGBA32_FLOAT';history=$true}
        $manifest.passes+=@{name='cloud.reconstruction';compute='shaders/cloud-reconstruction.comp';localSize=@(8,8,1);
            reads=@{BaseDepth='reconstruction.depth';BaseMotion='motion.data';BaseValid='motion.valid';CloudProxy='cloud.compose.proxy';CloudGeometry='cloud.geometry';CloudCoefficients='cloud.compose.coefficients';CloudSegment='cloud.compose.depth'};
            writes=@('cloud.reconstruction.depth','cloud.reconstruction.motion','cloud.reconstruction.valid','cloud.reconstruction.guide')}
        $manifest.reconstruction.depth='cloud.reconstruction.depth';$manifest.reconstruction.motion='cloud.reconstruction.motion'
        Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/cloud-reconstruction.comp') -Destination (Join-Path $target 'shaders/cloud-reconstruction.comp')
    }
}
if($NightSky){
    if(!$Atmosphere -or !$AutoExposure){throw 'NightSky requires Atmosphere and AutoExposure'}
    $manifest.options['NIGHT_SKY']=@{label='Cinematic night sky and moon lighting';default=1;values=@(0,1)}
}
if($Atmosphere) {
    $manifest.options['CINEMATIC_ATMOSPHERE']=@{label='Atmosphere';default=1;values=@(1)}
    $manifest.resources['atmosphere.sky']=@{format='RGBA16_FLOAT';size=@(256,128,1)}
    $manifest.resources['atmosphere.lighting']=@{format='RGBA32_FLOAT';size=@(10,1,1)}
    if($MultipleScattering) {
        $manifest.options['ATM_MULTIPLE_SCATTERING']=@{label='Atmospheric multiple scattering';default=1;values=@(0,1)}
        $manifest.resources['atmosphere.multiple']=@{format='RGBA32_FLOAT';size=@(32,32,1);history=$true}
        $manifest.resources['atmosphere.lighting'].size=@(11,1,1)
        $manifest.passes+=@{name='atmosphere.multiple';compute='shaders/atmosphere-multiple.comp';localSize=@(8,8,1);reads=@{};writes=@('atmosphere.multiple')}
        Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/atmosphere-multiple.comp') -Destination (Join-Path $target 'shaders/atmosphere-multiple.comp')
        [void][IO.Directory]::CreateDirectory((Join-Path $target 'LICENSES'))
        Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/LICENSES/UnrealEngineSkyAtmosphere-MIT.txt') -Destination (Join-Path $target 'LICENSES/UnrealEngineSkyAtmosphere-MIT.txt')
    }
    $manifest.passes += @{
        name='atmosphere.sky';compute='shaders/atmosphere-sky.comp';localSize=@(8,8,1);
        reads=@{};writes=@('atmosphere.sky')
    }
    $manifest.passes += @{
        name='atmosphere.lighting';compute='shaders/atmosphere-lighting.comp';localSize=@(1,1,1);
        reads=@{Sky='atmosphere.sky'};writes=@('atmosphere.lighting')
    }
    if($MultipleScattering) {
        foreach($pass in $manifest.passes|Where-Object{$_.name -in @('atmosphere.sky','atmosphere.lighting')}){$pass.reads['AtmosphereMultiple']='atmosphere.multiple'}
    }
    foreach($pass in $manifest.passes | Where-Object {$_.name -in @('ray.direct','ray.reflection','rough.reflection','indirect.trace','leaf.indirect.trace','indirect.environment','water.trace')}) {
        $pass.reads['AtmosphereSky']='atmosphere.sky';$pass.reads['AtmosphereLighting']='atmosphere.lighting'
    }
    if($CloudPreview) {
        foreach($pass in $manifest.passes|Where-Object{$_.name -in @('ray.direct','ray.reflection','rough.reflection','indirect.trace','leaf.indirect.trace','indirect.environment','water.trace')}) {
            $pass.reads['AtmosphereLighting']='cloud.lighting'
            $pass.reads['CloudShadow']='cloud.shadow';$pass.reads['ShadowCloudLayout']='$texture/vanilla-clouds'
            if($pass.name -ne 'ray.direct'){$pass.reads['AtmosphereSky']='cloud.environment'}
        }
    }
    foreach($file in @('atmosphere-sky.comp','atmosphere-lighting.comp')) {
        Copy-Item -LiteralPath (Join-Path $projectRoot ('examples/ray-lighting/'+$file)) -Destination (Join-Path $target ('shaders/'+$file))
    }
}
if($DistantWorld) {
    $manifest.options['DISTANT_WORLD_BLEND']=@{label='Blend the distant world into the horizon';default=$DistantWorldMode;values=@(0,1)}
    $distantBase=$hdrResource
    $manifest.resources['distant.world.hdr']=@{format='RGBA16_FLOAT';history=[bool]$DistantWorldDiagnostic}
    $distantWrites=@('distant.world.hdr')
    if($DistantWorldDiagnostic) {
        $manifest.budgetMiB=2048
        $manifest.options['DISTANT_WORLD_DIAGNOSTIC']=@{default=1;values=@(1)}
        foreach($name in @('distant.world.guide','distant.world.target','distant.world.linear')){$manifest.resources[$name]=@{format='RGBA32_FLOAT';history=$true}}
        $manifest.resources['distant.world.source']=@{format='RGBA32_UINT';history=$true}
        $manifest.resources[$distantBase].history=$true
        $distantWrites+=@('distant.world.source','distant.world.guide','distant.world.target','distant.world.linear')
    }
    $distantSky=if($CloudPreview){'cloud.environment'}else{'atmosphere.sky'}
    if($DistantWorldDiagnostic){$manifest.resources[$distantSky].history=$true;$manifest.resources['atmosphere.lighting'].history=$true}
    $distantReads=@{Base=$distantBase;Depth='$depth';Surface='surface.data';Sky=$distantSky;Lighting='atmosphere.lighting'}
    if($CloudPreview) {
        $distantReads.CloudCoefficients='cloud.compose.coefficients';$distantReads.ClearSky='atmosphere.sky'
        if($DistantWorldDiagnostic){$manifest.resources['cloud.compose.coefficients'].history=$true;$manifest.resources['atmosphere.sky'].history=$true}
    }
    if($DistantCloudComparison) {
        if(!$CloudPreview -or !$DistantWorldDiagnostic){throw 'DistantCloudComparison requires cloud preview and distant-world diagnostics'}
        $manifest.options['DISTANT_CLOUD_TARGET_SCREEN']=@{default=0;values=@(0,1)}
    }

    if($WaterHdr){$distantReads.Water='water.surface';$distantReads.WaterRadiance='water.radiance'}
    $manifest.passes+=@{name='distant.world';compute='shaders/distant-world.comp';localSize=@(8,8,1);reads=$distantReads;writes=$distantWrites}
    $hdrResource='distant.world.hdr'
    ($manifest.passes|Where-Object{$_.name -eq 'ray.direct.present'}).reads.HDR=$hdrResource
    if($DlssReconstruction){$manifest.present=$hdrResource;$manifest.reconstruction.color=$hdrResource}
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/distant-world.comp') -Destination (Join-Path $target 'shaders/distant-world.comp')
}
if($AutoExposure) {
    $manifest.options['AUTO_EXPOSURE']=@{default=1;values=@(1)}
    $manifest.resources['display.meter']=@{format='RGBA32_FLOAT';size=@(64,36,1);history=[bool]$IndirectDiagnostic}
    $manifest.resources['display.exposure']=@{format='RGBA32_FLOAT';size=@(1,1,1);history=$true}
    $manifest.resources['display.histogram']=@{format='R32_UINT';size=@(256,1,1);history=[bool]$IndirectDiagnostic}
    $manifest.passes+=@{name='display.meter';compute='shaders/exposure-meter.comp';localSize=@(8,8,1);reads=@{HDR=$hdrResource};writes=@('display.meter')}
    $manifest.passes+=@{name='display.histogram';compute='shaders/exposure-histogram.comp';localSize=@(256,1,1);reads=@{HDR=$hdrResource};writes=@('display.histogram')}
    $manifest.passes+=@{name='display.exposure';compute='shaders/exposure-adapt.comp';localSize=@(1,1,1);reads=@{Meter='display.meter';Histogram='display.histogram';Previous='display.exposure@previous'};writes=@('display.exposure')}
    $manifest.reconstruction['displayReads']=@{DisplayExposure='display.exposure'}
    $manifest.reconstruction.display='shaders/exposure-display.fsh'
    foreach($name in @('exposure-meter.comp','exposure-adapt.comp','exposure-histogram.comp','exposure-metering.glsl','exposure-display.fsh')) {
        Copy-Item -LiteralPath (Join-Path $projectRoot ('examples/ray-lighting/'+$name)) -Destination (Join-Path $target ('shaders/'+$name))
    }
}
if($LightingAudit) {
    if(!$LeafIndirect -or !$IndirectSpatial -or !$Atmosphere -or !$DlssReconstruction){throw 'LightingAudit requires LeafIndirect, IndirectSpatial, Atmosphere and DlssReconstruction'}
    $manifest.budgetMiB=2048
    $manifest.options['LIGHTING_AUDIT']=@{default=1;values=@(1)}
    foreach($name in @('ray.direct.hdr','atmosphere.lighting','atmosphere.sky','reconstruction.depth')){$manifest.resources[$name].history=$true}
    $auditWrites=@()
    foreach($term in @('sun','reflected','transmitted','emission','albedo')) {
        $name='lighting.audit.'+$term
        $manifest.resources[$name]=@{format='RGBA32_FLOAT';history=$true};$auditWrites+=@($name)
    }
    ($manifest.passes|Where-Object{$_.name -eq 'ray.direct'}).writes+=@($auditWrites)
}
if($CloudDiagnostic) {
    foreach($name in @('cloud.field.reference','cloud.medium.reference','cloud.position.reference','cloud.period.reference','cloud.hash.reference','cloud.coordinates.reference','cloud.wrap.reference','cloud.translated.reference')) {
        $manifest.resources[$name]=@{format='RGBA32_FLOAT';size=@(128,64,1);history=$true}
    }
    foreach($name in @('cloud.hash.reference','cloud.wrap.reference')){$manifest.resources[$name].format='RGBA32_UINT'}
    $manifest.passes+=@{name='cloud.field.diagnostic';compute='shaders/cloud-field-diagnostic.comp';localSize=@(8,8,1);
        reads=@{CloudLayout='$texture/vanilla-clouds'};writes=@('cloud.field.reference','cloud.medium.reference','cloud.position.reference','cloud.period.reference','cloud.hash.reference','cloud.coordinates.reference','cloud.wrap.reference','cloud.translated.reference')}
    ($manifest.passes|Where-Object{$_.name -eq 'ray.direct'}).reads['DiagnosticCloudField']='cloud.field.reference'
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/cloud-field-diagnostic.comp') -Destination (Join-Path $target 'shaders/cloud-field-diagnostic.comp')
}
if($CloudPreview -or $CloudDiagnostic) {
    $manifest.options['CLOUD_DENSITY_PROFILE']=@{label='Cloud shape: cellular / detail erosion';default=$CloudDensityProfile;values=@(0,1)}
    $manifest.options['CLOUD_CELLULAR_FIELD']=@{label='Cellular volume cloud density';default=1;values=@(1)}
    $manifest.textures['cloud-cellular']=@{source='textures/cloud-cellular-128.png';filter='nearest';wrap='repeat'}
    [void][IO.Directory]::CreateDirectory((Join-Path $target 'textures'))
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/textures/cloud-cellular-128.png') -Destination (Join-Path $target 'textures/cloud-cellular-128.png')
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/LICENSES/TileableVolumeNoise-MIT.txt') -Destination (Join-Path $target 'LICENSES/TileableVolumeNoise-MIT.txt')
    foreach($pass in $manifest.passes|Where-Object{$_.name.StartsWith('cloud.') -or $_.name -in @('ray.direct','ray.reflection','rough.reflection','indirect.trace','leaf.indirect.trace','indirect.environment','water.trace')}) {
        $pass.reads['CloudNoise']='$texture/cloud-cellular'
    }
    if($CloudDiagnostic) {
        foreach($name in @('cloud.cellular.reference','cloud.cellular.shiftx','cloud.cellular.shiftz','cloud.detail.reference','cloud.detail.shiftx','cloud.detail.shiftz')){$manifest.resources[$name]=@{format='RGBA32_FLOAT';size=@(128,64,1);history=$true}}
        $manifest.passes+=@{name='cloud.noise.diagnostic';compute='shaders/cloud-noise-diagnostic.comp';localSize=@(8,8,1);
            reads=@{CloudLayout='$texture/vanilla-clouds';CloudNoise='$texture/cloud-cellular';Positions='cloud.position.reference';Translations='cloud.translated.reference'};writes=@('cloud.cellular.reference','cloud.cellular.shiftx','cloud.cellular.shiftz','cloud.detail.reference','cloud.detail.shiftx','cloud.detail.shiftz')}
        ($manifest.passes|Where-Object{$_.name -eq 'ray.direct'}).reads['DiagnosticCloudNoise']='cloud.cellular.reference'
        Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/cloud-noise-diagnostic.comp') -Destination (Join-Path $target 'shaders/cloud-noise-diagnostic.comp')
    }
}
if($CloudSourceCache -or $CloudSourceComparison) {
    if(!$CloudPreview){throw 'CloudSourceCache requires CloudPreview'}
    $manifest.options['CLOUD_SOURCE_CACHE']=@{label='Cloud source transmission cache';default=$(if($CloudSourceComparison){0}else{1});values=@(0,1)}
    foreach($i in 0..2){
        $manifest.resources["cloud.source.$i"]=@{format='RGBA16_FLOAT';size=@(129,33,129);history=$true}
        $manifest.resources["cloud.source.ambient.$i"]=@{format='RGBA16_FLOAT';size=@(129,33,129);history=$true}
    }
    foreach($pass in $manifest.passes|Where-Object{$_.name -in @('cloud.view','cloud.environment','cloud.composite')}) {
        foreach($i in 0..2){$pass.reads["CloudSource$i"]="cloud.source.$i";$pass.reads["CloudSourceAmbient$i"]="cloud.source.ambient.$i"}
        $pass.linearReads=@(@($pass.linearReads)+@('CloudSource0','CloudSource1','CloudSource2','CloudSourceAmbient0','CloudSourceAmbient1','CloudSourceAmbient2')|Where-Object{$_})
    }
    $manifest.passes+=@{name='cloud.source.cache';compute='shaders/cloud-source-cache.comp';localSize=@(8,4,8);
        reads=@{CloudLayout='$texture/vanilla-clouds';CloudNoise='$texture/cloud-cellular'};writes=@('cloud.source.0','cloud.source.ambient.0','cloud.source.1','cloud.source.ambient.1','cloud.source.2','cloud.source.ambient.2')}
    foreach($name in @('cloud-source-cache.comp','cloud-source-grid.glsl')) {
        Copy-Item -LiteralPath (Join-Path $projectRoot ('examples/ray-lighting/'+$name)) -Destination (Join-Path $target ('shaders/'+$name))
    }
}
if($CloudExactVolume) {
    if(!$CloudPreview){throw 'CloudExactVolume requires CloudPreview'}
    $manifest.resources['cloud.exact.noise']=@{format=$(if($CloudExactFloat32){'RGBA32_FLOAT'}else{'RGBA8_UNORM'});size=@(130,130,130)}
    foreach($pass in $manifest.passes|Where-Object{$_.reads.ContainsKey('CloudNoise') -and (!$CloudExactEnvironment -or $_.name -eq 'cloud.environment')}) {$pass.reads['CloudExactNoise']='cloud.exact.noise'}
    $manifest.passes+=@{name='cloud.exact.noise';compute='shaders/cloud-exact-noise.comp';localSize=@(8,8,8);
        reads=@{CloudNoise='$texture/cloud-cellular'};writes=@('cloud.exact.noise')}
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/cloud-lighting-noise.comp') -Destination (Join-Path $target 'shaders/cloud-exact-noise.comp')
}
if($CloudLightingVolume -or $CloudLightingComparison) {
    if(!$CloudPreview){throw 'CloudLightingVolume requires CloudPreview'}
    $manifest.options['CLOUD_LIGHTING_VOLUME']=@{default=$(if($CloudLightingComparison){0}else{1});values=@(0,1)}
    $manifest.resources['cloud.lighting.noise']=@{format='RGBA8_UNORM';size=@(130,130,130)}
    foreach($pass in $manifest.passes|Where-Object{$_.reads.ContainsKey('CloudNoise')}) {
        $pass.reads['CloudLightingNoise']='cloud.lighting.noise'
        $pass.linearReads=@(@($pass.linearReads)+@('CloudLightingNoise') | Where-Object {$_})
    }
    $manifest.passes+=@{name='cloud.lighting.noise';compute='shaders/cloud-lighting-noise.comp';localSize=@(8,8,8);
        reads=@{CloudNoise='$texture/cloud-cellular'};writes=@('cloud.lighting.noise')}
    Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/cloud-lighting-noise.comp') -Destination (Join-Path $target 'shaders/cloud-lighting-noise.comp')
    if($CloudDiagnostic) {
        foreach($name in @('cloud.lighting.base.reference','cloud.lighting.detail.reference','cloud.lighting.density.reference')){$manifest.resources[$name]=@{format='RGBA32_FLOAT';size=@(128,64,1);history=$true}}
        $manifest.passes+=@{name='cloud.lighting.noise.diagnostic';compute='shaders/cloud-lighting-noise-diagnostic.comp';localSize=@(8,8,1);
            reads=@{CloudLayout='$texture/vanilla-clouds';CloudNoise='$texture/cloud-cellular';CloudLightingNoise='cloud.lighting.noise';Positions='cloud.position.reference'};
            linearReads=@('CloudLightingNoise');writes=@('cloud.lighting.base.reference','cloud.lighting.detail.reference','cloud.lighting.density.reference')}
        ($manifest.passes|Where-Object{$_.name -eq 'ray.direct'}).reads['DiagnosticCloudLighting']='cloud.lighting.base.reference'
        Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/cloud-lighting-noise-diagnostic.comp') -Destination (Join-Path $target 'shaders/cloud-lighting-noise-diagnostic.comp')
    }

}
# Hashtable iteration varies between PowerShell processes. Sort object keys
# ordinally while preserving all arrays (pass/output order is meaningful).
function ConvertTo-StableManifestValue([object]$Value) {
    if($Value -is [System.Collections.IDictionary]) {
        $ordered=[ordered]@{};[string[]]$keys=@($Value.Keys)
        [Array]::Sort($keys,[StringComparer]::Ordinal)
        foreach($key in $keys){$ordered[$key]=ConvertTo-StableManifestValue $Value[$key]}
        return $ordered
    }
    if($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        $items=[System.Collections.Generic.List[object]]::new()
        foreach($item in $Value){$items.Add((ConvertTo-StableManifestValue $item))}
        return ,$items.ToArray()
    }
    return $Value
}
ConvertTo-StableManifestValue $manifest | ConvertTo-Json -Depth 32 | Set-Content -LiteralPath $manifestPath
# PackFiles resolves includes before the GLSL preprocessor handles #ifdef.
# The shared source must therefore exist even when atmospheric passes are disabled.
$sources = @{ 'emitted-radiance.glsl'='emitted-radiance.glsl'; 'emission-sampling.glsl'='emission-sampling.glsl'; 'indirect-sampling.glsl'='indirect-sampling.glsl'; 'cloud-source-grid.glsl'='cloud-source-grid.glsl'; 'night-lighting.glsl'='night-lighting.glsl'; 'material-surface.glsl'='material-surface.glsl'; 'sun-source.glsl'='sun-source.glsl'; 'leaf-optics.glsl'='leaf-optics.glsl'; 'integer-wrap.glsl'='integer-wrap.glsl'; 'cloud-field.glsl'='cinematic-cloud-field.glsl'; 'cloud-volume.glsl'='cinematic-cloud-volume.glsl'; 'cloud-view-density.glsl'='cloud-view-density.glsl'; 'cloud-scattering.glsl'='cloud-scattering.glsl'; 'cloud-shadow.glsl'='cloud-shadow.glsl'; 'foliage.glsl'='foliage.glsl'; 'atmosphere.glsl'='atmosphere.glsl'; 'terrain-material.vsh'='terrain-material.vsh'; 'terrain-gbuffer.fsh'='terrain-gbuffer.fsh'; 'translucent-fallback.fsh'='translucent-fallback.fsh'; 'direct.comp'='ray-direct.comp'; 'present.fsh'='ray-direct-present.fsh' }
$sources['cloud-cellular.glsl']='cloud-cellular.glsl'
foreach ($file in $sources.GetEnumerator()) {
    Copy-Item -LiteralPath (Join-Path $projectRoot ('examples/ray-lighting/' + $file.Key)) -Destination (Join-Path $target ('shaders/' + $file.Value))
}
Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/surface-lighting.glsl') -Destination (Join-Path $target 'shaders/surface-lighting.glsl')
Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/secondary-lighting.glsl') -Destination (Join-Path $target 'shaders/secondary-lighting.glsl')
Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/display-mapping.glsl') -Destination (Join-Path $target 'shaders/display-mapping.glsl')
Write-Output "Experimental ray lighting pack created: $target"

if($RealtimeStep -gt 0){& (Join-Path $PSScriptRoot 'Enable-RealtimePack.ps1') -PackDirectory $target -Step $RealtimeStep}
