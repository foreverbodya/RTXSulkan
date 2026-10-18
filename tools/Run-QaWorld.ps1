#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $InstanceMods,
    [Parameter(Mandatory)][string] $QaDirectory,
    [string] $PrismRoot = (Join-Path $env:APPDATA 'PrismLauncher'),
    [string] $JavaRoot,
    [string] $Artifact,
    [switch] $RayVisibility,
    [switch] $RayLighting,
    [switch] $Reflections,
    [switch] $RoughReflections,
    [switch] $RoughReflectionComparison,
    [switch] $AlphaTraversal,
    [switch] $RayOrigin,
    [switch] $Atmosphere,
    [switch] $MultipleScattering,
    [switch] $FoliageTransmission,
    [switch] $IndirectDiagnostic,
    [switch] $DiffuseIndirect,
    [switch] $IndirectTemporal,
    [switch] $IndirectSpatial,
    [switch] $LeafIndirect,
    [ValidateSet(0,1,2)][int] $FoliageOptics = 0,
    [ValidateSet(1,2)][int] $ComparisonFoliageOptics = 1,
    [switch] $LeafOpticsComparison,
    [ValidateSet(0,1)][int] $LeafBoundary = 0,
    [switch] $LeafBoundaryComparison,
    [ValidateSet(0,1)][int] $LeafSurface = 0,
    [switch] $LeafSurfaceComparison,
    [ValidateSet(1,8)][int] $SunShadowSamples = 1,
    [switch] $SunDiskComparison,
    [ValidateSet(1,8)][int] $SunIndirectSamples = 8,
    [switch] $SunIndirectComparison,
    [ValidateRange(1,8)][int] $IndirectBounces = 1,
    [ValidateSet(1,4,16,64)][int] $IndirectSamples = 1,
    [ValidateSet(4,8,11)][int] $IndirectReferenceStride = 11,
    [switch] $AutoExposure,
    [ValidateSet(0,1,2,3)][int] $DisplayToneMapper = 0,
    [switch] $ToneMapComparison,
    [switch] $NightDisplay,
    [switch] $NightSky,
    [switch] $NightOnly,
    [switch] $InteriorVisual,
    [switch] $EmissiveMaterials,
    [ValidateSet(0,1)][int] $EmissiveSampling = 0,
    [switch] $EmissiveComparison,
    [ValidateSet(0,1)][int] $RoughDenoise = 0,
    [switch] $RoughDenoiseComparison,
    [switch] $RoughNoiseSequence,
    [switch] $WaterHdr,
    [switch] $SkyMotion,
    [switch] $HeldItemCapture,
    [switch] $VisualWorld,
    [ValidateRange(-80,80)][double] $CameraPitch = 0,
    [switch] $BounceComparison,
    [switch] $AtmosphereComparison,
    [switch] $SamplingComparison,
    [switch] $LightingAudit,
    [switch] $DistantWorld,
    [switch] $DistantWorldDiagnostic,
    [ValidateSet(0,1)][int] $DistantWorldMode = 1,
    [switch] $HorizonComparison,
    [switch] $CloudDiagnostic,
    [switch] $CloudPreview,
    [switch] $CloudCameraAudit,
    [switch] $CloudMotionAudit,
    [switch] $CloudMotionSequence,
    [switch] $CameraBobAudit,
    [switch] $GpuTimings,
    [switch] $PerformanceProfile,
    [switch] $WorkgroupExperiment,
    [ValidateSet("workgroup","cloud-cell","cloud-3d","cloud-3d-env")][string] $ComputeExperiment = "workgroup",
    [ValidateSet(3000,12600,18000)][int] $ProfileTime = 3000,
    [string] $ProfilePlan = "static,motion,static-repeat,stream,settled",
    [switch] $SolidShadowComparison,
    [switch] $CloudLightingComparison,
    [switch] $CloudLightingVolume,
    [switch] $CloudSourceCache,
    [switch] $CloudSourceComparison,
    [ValidateRange(0,384)][int] $CloudCameraHeight = 0,
    [switch] $CloudInteriorTree,
    [switch] $DistantCloudComparison,
    [switch] $CloudDepthAware,
    [switch] $CloudDepthComparison,
    [switch] $CloudAmbientComparison,
    [switch] $CloudDensityComparison,
    [switch] $CloudColumnComparison,
    [switch] $CloudReconstructionGuides,
    [ValidateSet(0,1)][int] $CloudDensityProfile = 0,
    [ValidateSet(0,1)][int] $CloudViewDensitySubsteps = 0,
    [switch] $MotionPhaseSweep,
    [ValidateRange(1,8)][int] $ComparisonLowBounces = 1,
    [ValidateRange(1,8)][int] $ComparisonHighBounces = 2,
    [switch] $RaySurfaceProbe,
    [ValidateRange(2,32)][int] $RenderDistance = 6,
    [switch] $DrawDistance32,
    [switch] $RayAttributes,
    [switch] $RayHitMaterials,
    [switch] $DlssFeatures,
    [switch] $TemporalInputs,
    [switch] $TemporalJitter,
    [switch] $WorldDlss,
    [string] $NativeLibrary,
    [string] $RuntimeDirectory,
    [ValidateRange(10,260)][int] $MaxFps = 60,
    [switch] $DisableVsync,
    [ValidateRange(180,1800)][int] $TimeoutSeconds = 600
)
$ErrorActionPreference = 'Stop'
if($PerformanceProfile -and (!$VisualWorld -or !$WorldDlss -or !$GpuTimings)){throw 'PerformanceProfile requires VisualWorld, WorldDlss and GpuTimings'}
if($PerformanceProfile -and $ProfilePlan -notmatch '^((narrow-)?(static|motion|stream|travel|reload|resize)|settled|static-repeat|motion-repeat|off-(clouds|gi|reflections|water|shadows|denoising))(,((narrow-)?(static|motion|stream|travel|reload|resize)|settled|static-repeat|motion-repeat|off-(clouds|gi|reflections|water|shadows|denoising)))*$'){throw 'Invalid ProfilePlan'}
if($RoughDenoiseComparison -and (!$InteriorVisual -or !$NightOnly -or !$ToneMapComparison -or !$GpuTimings -or $RoughDenoise -ne 0 -or $EmissiveComparison)){throw 'RoughDenoiseComparison requires separate interior night 0/1/0'}
if($RoughNoiseSequence -and !$RoughDenoiseComparison){throw 'RoughNoiseSequence requires the paired rough denoising capture'}
if($EmissiveComparison -and (!$InteriorVisual -or !$NightOnly -or !$ToneMapComparison -or !$GpuTimings -or $EmissiveSampling -ne 0)){throw 'EmissiveComparison requires separate interior night 0/1/0 with display/cost capture'}
if($InteriorVisual -and (!$NightOnly -or $LeafIndirect -or $CloudPreview)){throw 'InteriorVisual requires NightOnly and does not request leaf/cloud coverage checks'}
if($NightOnly -and (!$VisualWorld -or !$NightSky -or !$ToneMapComparison -or $NightDisplay -or $LightingAudit -or $CloudMotionAudit -or $CloudCameraAudit -or $DrawDistance32)){throw 'NightOnly requires a separate natural NightSky display capture without camera paths or option comparisons'}
if($CloudSourceComparison -and (!$VisualWorld -or !$CloudPreview -or !$GpuTimings -or @($RoughReflectionComparison,$NightDisplay,$LeafSurfaceComparison,$SunIndirectComparison,$SunDiskComparison,$LeafBoundaryComparison,$CloudDepthComparison,$DistantCloudComparison,$CloudLightingComparison,$SolidShadowComparison,$HorizonComparison,$LeafOpticsComparison,$CloudColumnComparison,$CloudDensityComparison,$CloudAmbientComparison,$SamplingComparison,$AtmosphereComparison,$BounceComparison,$DrawDistance32,$CloudMotionAudit,$CloudCameraAudit,$ToneMapComparison | Where-Object {$_}).Count -gt 0)){throw 'CloudSourceComparison requires a separate natural cloud/GPU timing comparison'}
if($RoughReflectionComparison -and (!$VisualWorld -or !$Reflections -or !$LightingAudit -or @($NightDisplay,$LeafSurfaceComparison,$SunIndirectComparison,$SunDiskComparison,$LeafBoundaryComparison,$CloudDepthComparison,$DistantCloudComparison,$CloudLightingComparison,$SolidShadowComparison,$HorizonComparison,$LeafOpticsComparison,$CloudColumnComparison,$CloudDensityComparison,$CloudAmbientComparison,$SamplingComparison,$AtmosphereComparison,$BounceComparison,$DrawDistance32,$CloudMotionAudit,$CloudCameraAudit,$ToneMapComparison | Where-Object {$_}).Count -gt 0)){throw 'RoughReflectionComparison requires separate natural reflection lighting audit'}
if($LeafSurfaceComparison -and (!$VisualWorld -or !$LeafIndirect -or !$LightingAudit -or $LeafSurface -ne 0 -or @($NightDisplay,$SunIndirectComparison,$SunDiskComparison,$LeafBoundaryComparison,$CloudDepthComparison,$DistantCloudComparison,$CloudLightingComparison,$SolidShadowComparison,$HorizonComparison,$LeafOpticsComparison,$CloudColumnComparison,$CloudDensityComparison,$CloudAmbientComparison,$SamplingComparison,$AtmosphereComparison,$BounceComparison,$DrawDistance32,$CloudMotionAudit,$CloudCameraAudit,$ToneMapComparison | Where-Object {$_}).Count -gt 0)){throw 'LeafSurfaceComparison requires separate natural leaf lighting audit starting at 0'}
if($NightDisplay -and (!$VisualWorld -or !$ToneMapComparison -or @($SunIndirectComparison,$SunDiskComparison,$LeafBoundaryComparison,$CloudDepthComparison,$DistantCloudComparison,$CloudLightingComparison,$SolidShadowComparison,$HorizonComparison,$LeafOpticsComparison,$CloudColumnComparison,$CloudDensityComparison,$CloudAmbientComparison,$SamplingComparison,$AtmosphereComparison,$BounceComparison,$LightingAudit,$DrawDistance32,$CloudMotionAudit,$CloudCameraAudit | Where-Object {$_}).Count -gt 0)){throw 'NightDisplay requires a separate natural same-frame display comparison without camera paths or option-switch comparisons'}
if($DrawDistance32 -and (!$VisualWorld -or !$RayLighting -or !$DistantWorld -or $RenderDistance -ne 16 -or
    $CloudMotionAudit -or $CloudCameraAudit -or $LightingAudit -or $ToneMapComparison -or $BounceComparison -or
    $AtmosphereComparison -or $SamplingComparison -or $CloudAmbientComparison -or $CloudDensityComparison -or
    $CloudColumnComparison -or $LeafOpticsComparison -or $HorizonComparison -or $SolidShadowComparison -or
    $CloudLightingComparison -or $DistantCloudComparison)){throw 'DrawDistance32 requires natural RayLighting/DistantWorld, initial distance 16, without other comparisons or camera paths'}
if($LeafBoundaryComparison -and (!$VisualWorld -or !$LeafIndirect -or !$LightingAudit -or $LeafBoundary -ne 0 -or $DrawDistance32 -or $BounceComparison -or $AtmosphereComparison -or $SamplingComparison -or $CloudAmbientComparison -or $CloudDensityComparison -or $CloudColumnComparison -or $LeafOpticsComparison -or $HorizonComparison -or $SolidShadowComparison -or $CloudLightingComparison -or $DistantCloudComparison -or $CloudDepthComparison -or $CloudMotionAudit -or $CloudCameraAudit -or $ToneMapComparison)){throw 'LeafBoundaryComparison requires separate natural leaf lighting audit starting at 0'}
if($SunDiskComparison -and (!$VisualWorld -or !$LeafIndirect -or !$LightingAudit -or $SunShadowSamples -ne 1 -or $DrawDistance32 -or $BounceComparison -or $AtmosphereComparison -or $SamplingComparison -or $CloudAmbientComparison -or $CloudDensityComparison -or $CloudColumnComparison -or $LeafOpticsComparison -or $LeafBoundaryComparison -or $HorizonComparison -or $SolidShadowComparison -or $CloudLightingComparison -or $DistantCloudComparison -or $CloudDepthComparison -or $CloudMotionAudit -or $CloudCameraAudit -or $ToneMapComparison)){throw 'SunDiskComparison requires separate natural leaf lighting audit starting at one direction'}
if($GpuTimings -and !$VisualWorld){throw 'GpuTimings requires VisualWorld'}
if($SunIndirectComparison -and (!$VisualWorld -or !$LeafIndirect -or !$LightingAudit -or $SunShadowSamples -ne 8 -or $SunIndirectSamples -ne 8 -or $DrawDistance32 -or $BounceComparison -or $AtmosphereComparison -or $SamplingComparison -or $CloudAmbientComparison -or $CloudDensityComparison -or $CloudColumnComparison -or $LeafOpticsComparison -or $LeafBoundaryComparison -or $HorizonComparison -or $SolidShadowComparison -or $CloudLightingComparison -or $DistantCloudComparison -or $CloudDepthComparison -or $SunDiskComparison -or $CloudMotionAudit -or $CloudCameraAudit -or $ToneMapComparison)){throw 'SunIndirectComparison requires separate natural lighting audit with disk 8 and initial indirect samples 8'}
if($DistantCloudComparison -and (!$VisualWorld -or !$CloudPreview -or !$DistantWorld -or !$DistantWorldDiagnostic -or $DistantWorldMode -ne 1 -or $SolidShadowComparison -or $CloudLightingComparison -or $BounceComparison -or $AtmosphereComparison -or $SamplingComparison -or $LightingAudit -or $ToneMapComparison -or $CloudAmbientComparison -or $CloudDensityComparison -or $CloudColumnComparison -or $LeafOpticsComparison -or $HorizonComparison -or $CloudMotionAudit -or $CloudCameraAudit)){throw 'DistantCloudComparison requires visual clouds and active distant-world diagnostics, without other comparisons'}
if($CloudLightingVolume -and (!$CloudPreview -or $CloudLightingComparison)){throw 'CloudLightingVolume requires CloudPreview and a separate run from CloudLightingComparison'}
if($CloudCameraHeight -gt 0 -and (!$VisualWorld -or !$CloudPreview)){throw 'CloudCameraHeight requires the disposable visual cloud fixture'}
if($CloudInteriorTree -and $CloudCameraHeight -eq 0){throw 'CloudInteriorTree requires an explicit CloudCameraHeight in the disposable fixture'}
if($CloudLightingComparison -and (!$VisualWorld -or !$CloudPreview -or !$CloudDiagnostic -or !$GpuTimings -or $SolidShadowComparison -or $BounceComparison -or $AtmosphereComparison -or $SamplingComparison -or $LightingAudit -or $ToneMapComparison -or $CloudAmbientComparison -or $CloudDensityComparison -or $CloudColumnComparison -or $LeafOpticsComparison -or $HorizonComparison -or $CloudMotionAudit -or $CloudCameraAudit)){throw 'CloudLightingComparison requires visual cloud diagnostics/GPU timings and no other comparison'}
if($SolidShadowComparison -and (!$VisualWorld -or !$LeafIndirect -or !$GpuTimings -or $BounceComparison -or $AtmosphereComparison -or $SamplingComparison -or $LightingAudit -or $ToneMapComparison -or $CloudAmbientComparison -or $CloudDensityComparison -or $CloudColumnComparison -or $LeafOpticsComparison -or $HorizonComparison -or $CloudMotionAudit -or $CloudCameraAudit)){throw 'SolidShadowComparison requires visual leaf indirect/GPU timings and no other comparison or camera audit'}
if($DistantWorld -and !$Atmosphere){throw 'DistantWorld requires Atmosphere'}
if($DistantWorldDiagnostic -and (!$DistantWorld -or !$VisualWorld)){throw 'DistantWorldDiagnostic currently requires VisualWorld and DistantWorld'}
if($HorizonComparison -and (!$DistantWorldDiagnostic -or $DistantWorldMode -ne 0 -or $ToneMapComparison -or $CloudCameraAudit -or $CloudMotionAudit)){throw 'HorizonComparison requires DistantWorldDiagnostic, initial mode 0 and no camera/tone comparison'}
if($LeafOpticsComparison -and (!$VisualWorld -or !$LeafIndirect -or !$LightingAudit -or $FoliageOptics -ne 0 -or $ToneMapComparison -or $CloudMotionAudit -or $CloudCameraAudit)){throw 'LeafOpticsComparison requires natural leaf indirect/lighting audit, initial optics 0 and no camera/tone comparison'}
if(($CloudDepthAware -or $CloudDepthComparison) -and !$CloudPreview){throw 'Cloud depth composition requires CloudPreview'}
if($CloudDepthComparison -and ($CloudDepthAware -or $DistantCloudComparison -or $CloudLightingComparison -or $SolidShadowComparison -or $DrawDistance32 -or $HorizonComparison -or $LeafOpticsComparison -or $CloudColumnComparison -or $CloudDensityComparison -or $CloudAmbientComparison -or $SamplingComparison -or $AtmosphereComparison -or $BounceComparison -or $LightingAudit -or $ToneMapComparison -or $CloudMotionAudit -or $CloudCameraAudit)){throw 'CloudDepthComparison requires a separate visual comparison starting disabled'}
if($CloudPreview -and (!$VisualWorld -or !$Atmosphere)){throw 'CloudPreview requires VisualWorld and Atmosphere'}
if($CloudViewDensitySubsteps -ne 0 -and !$CloudPreview){throw 'CloudViewDensitySubsteps requires CloudPreview'}
if($CloudColumnComparison -and (!$CloudPreview -or !$CloudDiagnostic -or $LightingAudit -or $ToneMapComparison -or $CloudViewDensitySubsteps -ne 0)){throw 'CloudColumnComparison requires CloudPreview/CloudDiagnostic, initial substeps 0 and no lighting/tone comparison'}
if($CloudReconstructionGuides -and (!$CloudPreview -or !$WorldDlss)){throw 'CloudReconstructionGuides requires CloudPreview and WorldDlss'}
if($CloudMotionAudit -and (!$CloudPreview -or !$WorldDlss -or $CloudCameraAudit -or $BounceComparison -or $AtmosphereComparison -or $SamplingComparison -or $ToneMapComparison -or $LightingAudit -or $CloudAmbientComparison -or $CloudDensityComparison -or $CloudColumnComparison)){throw 'CloudMotionAudit requires CloudPreview/WorldDlss and no other camera/option/lighting comparisons'}
if($CloudMotionSequence -and !$CloudMotionAudit){throw 'CloudMotionSequence requires CloudMotionAudit'}
if($CameraBobAudit -and (!$VisualWorld -or !$CloudMotionAudit)){throw 'CameraBobAudit requires VisualWorld and CloudMotionAudit'}
if($CloudDensityComparison -and $CloudDensityProfile -ne 0){throw 'CloudDensityComparison must start on profile 0'}
if($CloudCameraAudit -and !$CloudPreview){throw 'CloudCameraAudit requires CloudPreview'}
if($CloudCameraAudit -and ($BounceComparison -or $AtmosphereComparison -or $SamplingComparison -or $ToneMapComparison -or $LightingAudit -or $CloudAmbientComparison -or $CloudDensityComparison -or $CloudColumnComparison)){throw 'CloudCameraAudit uses separate settled camera captures; do not combine with option or lighting comparisons'}
if($CloudDensityComparison -and (!$CloudPreview -or !$CloudDiagnostic -or $LightingAudit -or $ToneMapComparison)){throw 'CloudDensityComparison requires CloudPreview and CloudDiagnostic without lighting/tone comparisons'}
if($CloudAmbientComparison -and (!$CloudPreview -or !$CloudDiagnostic -or $LightingAudit -or $ToneMapComparison)){throw 'CloudAmbientComparison requires CloudPreview and CloudDiagnostic without lighting/tone comparisons'}
if($CloudDiagnostic -and !$VisualWorld){throw 'CloudDiagnostic currently requires VisualWorld'}
if($LightingAudit -and (!$VisualWorld -or !$LeafIndirect -or !$IndirectSpatial -or !$Atmosphere -or !$AutoExposure)){throw 'LightingAudit requires VisualWorld, LeafIndirect, IndirectSpatial, Atmosphere, AutoExposure'}
if($ToneMapComparison -and (!$VisualWorld -or !$AutoExposure -or !$WorldDlss -or $BounceComparison -or $AtmosphereComparison -or $SamplingComparison)){throw 'ToneMapComparison requires VisualWorld, AutoExposure, WorldDlss, without option-switch comparisons'}
if(@($BounceComparison,$AtmosphereComparison,$SamplingComparison,$CloudAmbientComparison,$CloudDensityComparison,$CloudColumnComparison,$LeafOpticsComparison,$HorizonComparison | Where-Object {$_}).Count -gt 1){throw 'Choose one option-switch comparison'}
if($SamplingComparison -and (!$VisualWorld -or !$LeafIndirect -or !$IndirectSpatial -or $IndirectSamples -ne 1)){throw 'SamplingComparison requires VisualWorld, LeafIndirect, IndirectSpatial and initial IndirectSamples 1'}
if($MultipleScattering -and !$Atmosphere){throw 'MultipleScattering requires Atmosphere'}
if($AtmosphereComparison -and (!$VisualWorld -or !$MultipleScattering -or !$LeafIndirect -or !$IndirectSpatial -or $BounceComparison)){throw 'AtmosphereComparison requires VisualWorld, MultipleScattering, LeafIndirect, IndirectSpatial, without BounceComparison'}
if($HeldItemCapture -and ($VisualWorld -or $RaySurfaceProbe)){throw 'HeldItemCapture requires the disposable synthetic world'}
if($SkyMotion -and (!$WorldDlss -or $VisualWorld)){throw 'SkyMotion requires synthetic WorldDlss QA'}
if($BounceComparison -and (!$VisualWorld -or !$LeafIndirect -or !$IndirectSpatial -or $IndirectBounces -ne $ComparisonLowBounces -or $ComparisonHighBounces -le $ComparisonLowBounces)){throw 'BounceComparison requires VisualWorld, LeafIndirect, IndirectSpatial, initial bounce count equal to the low count, and a higher comparison count'}
if($WaterHdr -and (!$RayLighting -or !$Reflections -or !$WorldDlss -or $RayHitMaterials)){throw 'WaterHdr QA requires RayLighting/Reflections/WorldDlss without RayHitMaterials'}
if($WaterHdr){$TemporalInputs=$true}
if($AutoExposure -and !$WorldDlss){throw 'AutoExposure QA requires WorldDlss'}
if($LeafIndirect){$IndirectTemporal=$true;$FoliageTransmission=$true}
if($IndirectBounces -gt 1 -or $IndirectSamples -gt 1){$IndirectTemporal=$true}
if($IndirectSpatial) {$IndirectTemporal=$true}
if($IndirectTemporal) {$DiffuseIndirect=$true;$TemporalInputs=$true}
if($DiffuseIndirect -and (!$RayLighting -or (!$VisualWorld -and !$IndirectDiagnostic))) {throw 'DiffuseIndirect QA requires RayLighting and either IndirectDiagnostic or VisualWorld'}
if($IndirectDiagnostic -and (!$RayLighting -or !$Reflections -or $VisualWorld -or $RaySurfaceProbe)) {throw 'IndirectDiagnostic requires synthetic RayLighting/Reflections without VisualWorld or RaySurfaceProbe'}
if($FoliageTransmission -and (!$RayLighting -or (!$VisualWorld -and !$AlphaTraversal))) {throw 'FoliageTransmission QA requires RayLighting and either VisualWorld or stationary AlphaTraversal'}
if($RaySurfaceProbe -and ($RayLighting -or $RayVisibility -or $Reflections -or $AlphaTraversal -or $RayOrigin -or $Atmosphere -or $VisualWorld -or $RayAttributes -or $RayHitMaterials -or $DlssFeatures -or $TemporalInputs -or $TemporalJitter -or $WorldDlss)) {
    throw 'RaySurfaceProbe is a separate world-free mode'
}
if($VisualWorld -and (!$RayLighting -or !$Reflections -or !$Atmosphere -or !$WorldDlss -or $AlphaTraversal -or $RayOrigin -or $RayHitMaterials -or $RayVisibility -or $RayAttributes)) {
    throw 'VisualWorld requires RayLighting/Reflections/Atmosphere/WorldDlss and is separate from synthetic diagnostic modes'
}
if ($Reflections -and (!$RayLighting -or $RayHitMaterials)) { throw 'Reflections requires RayLighting and a separate run from RayHitMaterials' }
if ($AlphaTraversal -and !$RayLighting) { throw 'AlphaTraversal requires RayLighting' }
if ($RayOrigin -and (!$RayLighting -or !$Reflections)) { throw 'RayOrigin requires RayLighting and Reflections' }
if ($Atmosphere -and (!$RayLighting -or !$WorldDlss -or $RayHitMaterials)) { throw 'Atmosphere QA requires RayLighting/WorldDlss and a separate run from RayHitMaterials' }
if($WorldDlss) {
    if(!$RayLighting) { throw 'WorldDlss requires RayLighting' }
    $TemporalInputs=$true; $TemporalJitter=$true; $DlssFeatures=$true
    $NativeLibrary=(Resolve-Path -LiteralPath $NativeLibrary).Path
    $RuntimeDirectory=(Resolve-Path -LiteralPath $RuntimeDirectory).Path
}
if ($RayVisibility -and $RayLighting) { throw 'Choose one ray QA mode' }
if($MotionPhaseSweep -and (!$TemporalInputs -or $VisualWorld -or $HeldItemCapture)){throw 'MotionPhaseSweep requires synthetic TemporalInputs QA'}
if ($RayHitMaterials -and !$RayLighting) { throw 'RayHitMaterials requires RayLighting' }
if ($TemporalInputs -and (!$RayLighting -or $RayHitMaterials)) { throw 'TemporalInputs requires RayLighting and a separate run from RayHitMaterials' }
if ($TemporalJitter -and !$TemporalInputs -and !$HeldItemCapture) { throw 'TemporalJitter requires TemporalInputs or the explicit held-item reproduction' }
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!$JavaRoot) { $JavaRoot = Join-Path $PrismRoot 'java/java-runtime-epsilon' }
if (!$Artifact) { $Artifact = Join-Path $projectRoot 'build/libs/sulkan-0.5.1-cinematic.0-26.3.jar' }
$Artifact = (Resolve-Path -LiteralPath $Artifact).Path
$qaPath = [IO.Path]::GetFullPath($QaDirectory)
if (Test-Path -LiteralPath $qaPath) { throw 'QA directory must be new; existing directories are never reused.' }
$javaPath = Join-Path $JavaRoot 'bin/java.exe'
if (!(Test-Path -LiteralPath $javaPath)) { throw "Java runtime missing: $javaPath" }
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'libraries.lock.json') -Raw | ConvertFrom-Json
$classpath = foreach ($library in $lock.libraries) {
    $path = Join-Path $PrismRoot ('libraries/' + $library.path)
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $library.sha256) {
        throw "Runtime library hash mismatch: $($library.path)"
    }
    $path
}
$mods = @{
    'fabric-api-0.162.0+26.3.jar' = '8626c189b702f466800b1c8ddac6203cf8a1e0bdaee75a5f9a33b18c2baa81b1'
    'sodium-fabric-0.9.2+mc26.3.jar' = '87a5bb39f7e6e41106e77f122f82fc93f9d3bc0b6ebcb746b314172a446a287a'
}
foreach ($mod in $mods.GetEnumerator()) {
    if ((Get-FileHash -LiteralPath (Join-Path $InstanceMods $mod.Key) -Algorithm SHA256).Hash -ne $mod.Value) {
        throw "Mod hash mismatch: $($mod.Key)"
    }
}
$voicePath = Join-Path $InstanceMods 'voicechat-fabric-2.6.24+26.3.jar'
$voiceHash = (Get-FileHash -LiteralPath $voicePath -Algorithm SHA256).Hash
if ($voiceHash -ne '97074fa1d094a22f17d5ac11282176965a45137950b66627d3813618810c3d00') {
    throw 'Voicechat mod hash mismatch'
}
New-Item -ItemType Directory -Path (Join-Path $qaPath 'mods') | Out-Null
Set-Content -LiteralPath (Join-Path $qaPath 'SULKAN_QA_DISPOSABLE') -Value 'sulkan-cinematic-disposable-v1' -NoNewline
foreach ($name in $mods.Keys) { Copy-Item -LiteralPath (Join-Path $InstanceMods $name) -Destination (Join-Path $qaPath 'mods') }
Copy-Item -LiteralPath $voicePath -Destination (Join-Path $qaPath 'mods')
Copy-Item -LiteralPath $Artifact -Destination (Join-Path $qaPath 'mods/sulkan-0.5.1-cinematic.0-26.3.jar')
$copiedArtifact = Join-Path $qaPath 'mods/sulkan-0.5.1-cinematic.0-26.3.jar'
$artifactHash = (Get-FileHash -LiteralPath $copiedArtifact -Algorithm SHA256).Hash
$runtimeModHashes = @{}
foreach ($name in (@($mods.Keys) + 'voicechat-fabric-2.6.24+26.3.jar')) {
    $runtimeModHashes[$name] = (Get-FileHash -LiteralPath (Join-Path $qaPath ('mods/' + $name)) -Algorithm SHA256).Hash
}
@('preferredGraphicsBackend:vulkan', "renderDistance:$RenderDistance", 'simulationDistance:5',
  "maxFps:$MaxFps", ('enableVsync:{0}' -f (!$DisableVsync).ToString().ToLowerInvariant()), 'pauseOnLostFocus:false',
  'soundCategory_master:0.0') | Set-Content -LiteralPath (Join-Path $qaPath 'options.txt')
if($VisualWorld) {
    # A scripted fixed camera receives no input; the vanilla AFK cap would skew
    # later phases. Keep the minimized-window limit and record throttle reasons.
    Add-Content -LiteralPath (Join-Path $qaPath 'options.txt') -Value 'inactivityFpsLimit:"minimized"'
}
if ($RayVisibility -or $RayLighting) {
    $packName = if ($RayLighting) { 'RayLighting' } else { 'RayVisibility' }
    $creator = if ($RayLighting) { 'Create-RayLightingPack.ps1' } else { 'Create-RayVisibilityPack.ps1' }
    $createArguments = @{ Destination = (Join-Path $qaPath ('shaders/' + $packName)) }
    if ($RayHitMaterials) { $createArguments.HitMaterials = $true }
    if ($TemporalInputs) { $createArguments.TemporalInputs = $true }
    if ($WorldDlss) { $createArguments.DlssReconstruction = $true }
    if ($Reflections) { $createArguments.Reflections = $true }
    if($RoughReflections){$createArguments.RoughReflections=$true}
    if($RoughReflectionComparison){$createArguments.RoughReflectionComparison=$true}
    if ($AlphaTraversal) { $createArguments.AlphaDiagnostic = $true }
    if ($Atmosphere) { $createArguments.Atmosphere = $true }
    if ($MultipleScattering) { $createArguments.MultipleScattering = $true }
    if($FoliageTransmission) {$createArguments.FoliageTransmission=$true;$createArguments.FoliageDiagnostic=!$VisualWorld}
    if($IndirectDiagnostic) {$createArguments.IndirectDiagnostic=$true}
    if($DiffuseIndirect) {$createArguments.DiffuseIndirect=$true;$createArguments.IndirectBounces=$IndirectBounces;$createArguments.IndirectSamples=$IndirectSamples}
    if($IndirectTemporal) {$createArguments.IndirectTemporal=$true}
    if($IndirectSpatial) {$createArguments.IndirectSpatial=$true}
    if($LeafIndirect) {$createArguments.LeafIndirect=$true}
    $createArguments.LeafBoundary=$LeafBoundary
    $createArguments.LeafSurface=$LeafSurface
    if($LeafSurfaceComparison){$createArguments.LeafSurfaceComparison=$true}
    $createArguments.SunShadowSamples=$SunShadowSamples
    $createArguments.SunIndirectSamples=$SunIndirectSamples
    if($SunIndirectComparison){$createArguments.SunIndirectComparison=$true}
    if($SunDiskComparison){$createArguments.SunDiskComparison=$true}
    if($LeafBoundaryComparison){$createArguments.LeafBoundaryComparison=$true}
    if($LightingAudit) {$createArguments.LightingAudit=$true}
    if($DistantWorld){$createArguments.DistantWorld=$true;$createArguments.DistantWorldMode=$DistantWorldMode}
    if($DistantWorldDiagnostic){$createArguments.DistantWorldDiagnostic=$true}
    if($CloudDiagnostic) {$createArguments.CloudDiagnostic=$true}
    if($CloudPreview) {$createArguments.CloudPreview=$true}
    if($ComputeExperiment -eq "cloud-3d"){$createArguments.CloudExactVolume=$true}
    if($ComputeExperiment -eq "cloud-3d-env"){$createArguments.CloudExactEnvironment=$true}
    if($SolidShadowComparison){$createArguments.SolidShadowComparison=$true}
    if($CloudLightingComparison){$createArguments.CloudLightingComparison=$true}
    if($CloudLightingVolume){$createArguments.CloudLightingVolume=$true}
    if($NightSky){$createArguments.NightSky=$true}
    if($EmissiveMaterials){$createArguments.EmissiveMaterials=$true}
    $createArguments.EmissiveSampling=$EmissiveSampling
    if($EmissiveComparison){$createArguments.EmissiveComparison=$true}
    $createArguments.RoughDenoise=$RoughDenoise
    if($RoughDenoiseComparison){$createArguments.RoughDenoiseComparison=$true}
    if($CloudSourceCache){$createArguments.CloudSourceCache=$true}
    if($CloudSourceComparison){$createArguments.CloudSourceComparison=$true}
    if($DistantCloudComparison){$createArguments.DistantCloudComparison=$true}
    if($CloudDepthAware){$createArguments.CloudDepthAware=$true}
    if($CloudDepthComparison){$createArguments.CloudDepthComparison=$true}
    if($CloudReconstructionGuides){$createArguments.CloudReconstructionGuides=$true}
    if($RayLighting){$createArguments.FoliageOptics=$FoliageOptics}
    if($RayLighting){$createArguments.CloudDensityProfile=$CloudDensityProfile;$createArguments.CloudViewDensitySubsteps=$CloudViewDensitySubsteps}
    if($AutoExposure) {$createArguments.AutoExposure=$true}
    if($RayLighting){$createArguments.DisplayToneMapper=$DisplayToneMapper}
    if($WaterHdr) {$createArguments.WaterHdr=$true;$createArguments.WaterDiagnostic=!$VisualWorld}
    & (Join-Path $PSScriptRoot $creator) @createArguments | Out-Null
    if($CloudAmbientComparison) {
        $path=Join-Path $qaPath ('shaders/'+$packName+'/sulkan.json')
        $manifest=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -AsHashtable
        $manifest.options.CLOUD_AMBIENT_OCCLUSION.default=0
        $manifest|ConvertTo-Json -Depth 32|Set-Content -LiteralPath $path
    }
    if($AtmosphereComparison) {
        $path=Join-Path $qaPath ('shaders/'+$packName+'/sulkan.json')
        $manifest=Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -AsHashtable
        $manifest.options.ATM_MULTIPLE_SCATTERING.default=0
        $manifest|ConvertTo-Json -Depth 32|Set-Content -LiteralPath $path
    }
    New-Item -ItemType Directory -Path (Join-Path $qaPath 'config') | Out-Null
    @{ enabled = $true; selectedPackId = $packName } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $qaPath 'config/sulkan-shaders.json')
}
$evidencePath = Join-Path $qaPath 'evidence'
$startInfo = [Diagnostics.ProcessStartInfo]::new($javaPath)
$startInfo.WorkingDirectory = $qaPath
$startInfo.UseShellExecute = $false
$startInfo.CreateNoWindow = $true
$startInfo.RedirectStandardOutput = $true
$startInfo.RedirectStandardError = $true
$arguments = @('--enable-native-access=ALL-UNNAMED', '-Dsulkan.rayTracing=true',
    "-Dsulkan.qaDirectory=$qaPath", "-Dsulkan.qaEvidence=$evidencePath", '-Xmx4G', '-XX:StackShadowPages=32',
    '-classpath', ($classpath -join ';'), 'net.fabricmc.loader.impl.launch.knot.KnotClient',
    '--username', 'SulkanQA', '--version', '26.3', '--gameDir', $qaPath,
    '--assetsDir', (Join-Path $PrismRoot 'assets'), '--assetIndex', '34', '--accessToken', '0',
    '--uuid', '00000000000000000000000000000001', '--width', $(if($WorldDlss -or $HeldItemCapture){'2560'}else{'854'}), '--height', $(if($WorldDlss -or $HeldItemCapture){'1440'}else{'480'}))
foreach ($argument in $arguments) { $startInfo.ArgumentList.Add($argument) }
if($RaySurfaceProbe) {
    [void]$startInfo.ArgumentList.Remove("-Dsulkan.qaDirectory=$qaPath")
    $startInfo.ArgumentList.Insert(0,'-Dsulkan.raySurfaceConformance=true')
}
$startInfo.ArgumentList.Insert(0,"-Dsulkan.qaFrameCap=$MaxFps")
$startInfo.ArgumentList.Insert(0,"-Dsulkan.qaTimeoutSeconds=$TimeoutSeconds")
if ($RayVisibility -or $RayLighting) { $startInfo.ArgumentList.Insert(0, '-Dsulkan.qaRayVisibility=true') }
if ($RayLighting) {
    $startInfo.ArgumentList.Insert(0, '-Dsulkan.qaRayLighting=true')
    $startInfo.ArgumentList.Insert(0, '-Dsulkan.qaReadback=true')
}
if ($RayAttributes) { $startInfo.ArgumentList.Insert(0, '-Dsulkan.qaRayAttributes=true') }
if ($Reflections) { $startInfo.ArgumentList.Insert(0, '-Dsulkan.qaReflections=true') }
if ($AlphaTraversal) { $startInfo.ArgumentList.Insert(0, '-Dsulkan.qaAlphaTraversal=true') }
if ($RayOrigin) { $startInfo.ArgumentList.Insert(0, '-Dsulkan.qaRayOrigin=true') }
if ($Atmosphere) { $startInfo.ArgumentList.Insert(0, '-Dsulkan.qaAtmosphere=true') }
if($MultipleScattering){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaMultipleScattering=true')}
if($FoliageTransmission) { $startInfo.ArgumentList.Insert(0,'-Dsulkan.qaFoliage=true') }
if($IndirectDiagnostic) { $startInfo.ArgumentList.Insert(0,'-Dsulkan.qaIndirectDiagnostic=true') }
if($DiffuseIndirect) { $startInfo.ArgumentList.Insert(0,'-Dsulkan.qaDiffuseIndirect=true') }
if($IndirectTemporal) { $startInfo.ArgumentList.Insert(0,'-Dsulkan.qaIndirectTemporal=true') }
$startInfo.ArgumentList.Insert(0,('-Dsulkan.qaIndirectBounces='+$IndirectBounces))
$startInfo.ArgumentList.Insert(0,('-Dsulkan.qaIndirectSamples='+$IndirectSamples))
$startInfo.ArgumentList.Insert(0,('-Dsulkan.qaIndirectReferenceStride='+$IndirectReferenceStride))
if($IndirectSpatial) { $startInfo.ArgumentList.Insert(0,'-Dsulkan.qaIndirectSpatial=true') }
if($BounceComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaBounceComparison=true')}
if($SamplingComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaSamplingComparison=true')}
if($CloudAmbientComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaCloudAmbientComparison=true')}
if($CloudDensityComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaCloudDensityComparison=true')}
if($LeafOpticsComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaLeafOpticsComparison=true');$startInfo.ArgumentList.Insert(0,"-Dsulkan.qaComparisonFoliageOptics=$ComparisonFoliageOptics")}
if($HorizonComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaHorizonComparison=true')}
if($DistantWorldDiagnostic){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaDistantWorldDiagnostic=true')}
if($CloudColumnComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaCloudColumnComparison=true')}
if($MotionPhaseSweep){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaMotionPhaseSweep=true')}
if($AtmosphereComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaAtmosphereComparison=true')}
if($BounceComparison){$startInfo.ArgumentList.Insert(0,"-Dsulkan.qaComparisonLowBounces=$ComparisonLowBounces");$startInfo.ArgumentList.Insert(0,"-Dsulkan.qaComparisonHighBounces=$ComparisonHighBounces")}
if($SkyMotion){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaSkyMotion=true')}
if($HeldItemCapture){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaHeldItemCapture=true')}
if($LeafIndirect) { $startInfo.ArgumentList.Insert(0,'-Dsulkan.qaLeafIndirect=true') }
if($AutoExposure) { $startInfo.ArgumentList.Insert(0,'-Dsulkan.qaAutoExposure=true') }
if($ToneMapComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaToneMapComparison=true')}
if($WaterHdr) { $startInfo.ArgumentList.Insert(0,'-Dsulkan.qaWaterHdr=true') }
if($LightingAudit){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaLightingAudit=true')}
if($LeafBoundaryComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaLeafBoundaryComparison=true')}
if($SunDiskComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaSunDiskComparison=true')}
if($RoughReflectionComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaRoughReflectionComparison=true')}
if($LeafSurfaceComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaLeafSurfaceComparison=true')}
if($SunIndirectComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaSunIndirectComparison=true')}
if($NightDisplay){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaNightDisplay=true')}
if($PSBoundParameters.ContainsKey('CameraPitch')){
    $startInfo.ArgumentList.Insert(0,'-Dsulkan.qaVisualPitch='+$CameraPitch.ToString([Globalization.CultureInfo]::InvariantCulture))
}
if($CloudDiagnostic){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaCloudDiagnostic=true')}
if($CloudPreview){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaCloudPreview=true')}
if($CloudCameraHeight -gt 0){$startInfo.ArgumentList.Insert(0,"-Dsulkan.qaCloudCameraHeight=$CloudCameraHeight")}
if($CloudInteriorTree){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaCloudInteriorTree=true')}
if($DistantCloudComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaDistantCloudComparison=true')}
if($CloudDepthComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaCloudDepthComparison=true')}
if($CloudCameraAudit){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaCloudCameraAudit=true')}
if($CloudMotionAudit){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaCloudMotionAudit=true')}
if($CloudMotionSequence){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaCloudMotionSequence=true')}
if($CameraBobAudit){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaCameraBob=true');Add-Content -LiteralPath (Join-Path $qaPath 'options.txt') -Value 'bobView:true'}
if($GpuTimings){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaGpuTimings=true')}
if($WorkgroupExperiment){$startInfo.ArgumentList.Insert(0,"-Dsulkan.qaWorkgroupExperiment=true");$startInfo.ArgumentList.Insert(0,"-Dsulkan.qaComputeExperiment=$ComputeExperiment")}
if($PerformanceProfile){$startInfo.ArgumentList.Insert(0,"-Dsulkan.qaProfileTime=$ProfileTime");$startInfo.ArgumentList.Insert(0,"-Dsulkan.qaPerformanceProfile=true");$startInfo.ArgumentList.Insert(0,"-Dsulkan.qaProfilePlan=$ProfilePlan")}
if($SolidShadowComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaSolidShadowComparison=true')}
if($CloudSourceComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaCloudSourceComparison=true')}
if($NightOnly){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaNightOnly=true')}
if($InteriorVisual){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaInteriorVisual=true')}
if($EmissiveComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaEmissiveComparison=true')}
if($RoughDenoiseComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaRoughDenoiseComparison=true')}
if($RoughNoiseSequence){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaRoughNoiseSequence=true')}
if($CloudLightingComparison){$startInfo.ArgumentList.Insert(0,'-Dsulkan.qaCloudLightingComparison=true')}
if ($VisualWorld) { $startInfo.ArgumentList.Insert(0, '-Dsulkan.qaVisualWorld=true') }
if ($DrawDistance32) { $startInfo.ArgumentList.Insert(0, '-Dsulkan.qaDrawDistance32=true') }
if ($RayHitMaterials) { $startInfo.ArgumentList.Insert(0, '-Dsulkan.qaRayHitMaterials=true') }
if ($DlssFeatures) { $startInfo.ArgumentList.Insert(0, '-Dsulkan.dlss=true') }
if ($TemporalInputs) { $startInfo.ArgumentList.Insert(0, '-Dsulkan.qaTemporalInputs=true') }
if ($TemporalJitter) { $startInfo.ArgumentList.Insert(0, '-Dsulkan.temporalJitter=true') }
if ($WorldDlss) {
    $startInfo.ArgumentList.Insert(0, '-Dsulkan.qaWorldDlss=true')
    $startInfo.ArgumentList.Insert(0, "-Dsulkan.dlssLibrary=$NativeLibrary")
    $startInfo.ArgumentList.Insert(0, "-Dsulkan.dlssRuntime=$RuntimeDirectory")
}
if($RayLighting) {
    $packRoot=Join-Path $qaPath 'shaders/RayLighting'
    foreach($shader in Get-ChildItem -LiteralPath (Join-Path $packRoot 'shaders') -File -Recurse) {
        foreach($include in [regex]::Matches([IO.File]::ReadAllText($shader.FullName),'#include\s+"(shaders/[^"\r\n]+)"')) {
            if(!(Test-Path -LiteralPath (Join-Path $packRoot $include.Groups[1].Value))){throw "Generated pack has a missing include: $($include.Groups[1].Value) referenced by $($shader.Name)"}
        }
    }
}
$process = [Diagnostics.Process]::Start($startInfo)
$output = $process.StandardOutput.ReadToEndAsync()
$errors = $process.StandardError.ReadToEndAsync()
$runWatch=[Diagnostics.Stopwatch]::StartNew();$activationFailure=$null;$expired=$false
while(!$process.WaitForExit(1000)) {
    $logPath=Join-Path $qaPath 'logs/latest.log'
    if(Test-Path -LiteralPath $logPath) {
        $liveLog=''
        try {
            $logStream=[IO.FileStream]::new($logPath,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
            $logReader=[IO.StreamReader]::new($logStream)
            try {$liveLog=$logReader.ReadToEnd()}finally{$logReader.Dispose()}
        }catch [IO.IOException] { } # A log rotation/read race is not a terminal game result.
        if($liveLog.Contains('Failed to load the selected Sulkan shader pack')) {
            $activationFailure='Selected Sulkan shader pack failed to activate; inspect logs/latest.log'
            break
        }
    }
    if($runWatch.Elapsed.TotalSeconds -ge $TimeoutSeconds){$expired=$true;break}
}
if ($expired -or $activationFailure) {
    # This handle belongs only to the disposable process created above.
    $process.Kill($true)
    $process.WaitForExit()
}
$stdout = $output.GetAwaiter().GetResult()
$stderr = $errors.GetAwaiter().GetResult()
Set-Content -LiteralPath (Join-Path $qaPath 'stdout.log') -Value $stdout
Set-Content -LiteralPath (Join-Path $qaPath 'stderr.log') -Value $stderr
$combined = $stdout + "`n" + $stderr
if($RaySurfaceProbe) {
    $required=@('SULKAN_RAY_SURFACE_GPU_PASS','SULKAN_RAY_BUDGET_RETIRE_GPU_PASS','SULKAN_RT_DEVICE_NEGOTIATION requested=true enabled=true extensionsCallable=true')
    $missing=@($required | Where-Object{!$combined.Contains($_)})
    if($combined.Contains('SULKAN_RAY_SURFACE_GPU_FAIL')) {$missing+='Ray surface GPU diagnostic failed'}
} elseif($PerformanceProfile) {
    $required=@('SULKAN_PERFORMANCE_PROFILE_PASS','SULKAN_DLSS_DEVICE_NEGOTIATION requested=true enabled=true','SULKAN_DLSS_WORLD_RETIRE_PASS lastContext=true liveContexts=0')
    $missing=@($required | Where-Object{!$combined.Contains($_)})
    foreach($phase in $ProfilePlan.Split(',')){if(!$combined.Contains("SULKAN_GPU_PASS_TIMINGS_PASS phase=profile-$phase ")){$missing+="GPU profile missing $phase"}}
} elseif($VisualWorld) {
    $required=@('SULKAN_QA_VISUAL_CAPTURE_PASS phase=day','SULKAN_QA_VISUAL_CAPTURE_PASS phase=sunset',
        'SULKAN_DLSS_DEVICE_NEGOTIATION requested=true enabled=true','SULKAN_DLSS_WORLD_RETIRE_PASS lastContext=true liveContexts=0')
    if($NightOnly){$required=@('SULKAN_QA_VISUAL_CAPTURE_PASS phase=night','SULKAN_DLSS_DEVICE_NEGOTIATION requested=true enabled=true','SULKAN_DLSS_WORLD_RETIRE_PASS lastContext=true liveContexts=0')}
    if($EmissiveComparison -or $RoughDenoiseComparison){$required=@('SULKAN_DLSS_DEVICE_NEGOTIATION requested=true enabled=true','SULKAN_DLSS_WORLD_RETIRE_PASS lastContext=true liveContexts=0')}
    $missing=@($required | Where-Object{!$combined.Contains($_)})
    $displayPhases=if($NightOnly){@('night')}elseif($NightDisplay){@('day','sunset','night')}else{@('day','sunset')}
    $captureNames=if($CloudSourceComparison){@('day','sunset')|ForEach-Object{"${_}-cloud-source-0-trial-1";"${_}-cloud-source-1-trial-2";"${_}-cloud-source-0-trial-3"}}elseif($RoughReflectionComparison){@('day','sunset')|ForEach-Object{"${_}-rough-specular-0-trial-1";"${_}-rough-specular-1-trial-2";"${_}-rough-specular-0-trial-3"}}elseif($LeafSurfaceComparison){@('day','sunset')|ForEach-Object{"${_}-leaf-surface-0-trial-1";"${_}-leaf-surface-1-trial-2";"${_}-leaf-surface-0-trial-3"}}elseif($SunIndirectComparison){@('day','sunset')|ForEach-Object{"${_}-sun-indirect-8-trial-1";"${_}-sun-indirect-1-trial-2";"${_}-sun-indirect-8-trial-3"}}elseif($SunDiskComparison){@('day','sunset')|ForEach-Object{"${_}-sun-disk-1-trial-1";"${_}-sun-disk-8-trial-2";"${_}-sun-disk-1-trial-3"}}elseif($LeafBoundaryComparison){@('day','sunset')|ForEach-Object{"${_}-leaf-boundary-0-trial-1";"${_}-leaf-boundary-1-trial-2";"${_}-leaf-boundary-0-trial-3"}}elseif($CloudDepthComparison){@('day','sunset')|ForEach-Object{"${_}-cloud-depth-0-trial-1";"${_}-cloud-depth-1-trial-2";"${_}-cloud-depth-0-trial-3"}}elseif($DistantCloudComparison){@('day','sunset')|ForEach-Object{"${_}-cloud-target-0-trial-1";"${_}-cloud-target-1-trial-2";"${_}-cloud-target-0-trial-3"}}elseif($CloudLightingComparison){@('day','sunset')|ForEach-Object{"${_}-cloud-lighting-0-trial-1";"${_}-cloud-lighting-1-trial-2";"${_}-cloud-lighting-0-trial-3"}}elseif($SolidShadowComparison){@('day','sunset')|ForEach-Object{"${_}-solid-shadow-0-trial-1";"${_}-solid-shadow-1-trial-2";"${_}-solid-shadow-0-trial-3"}}elseif($HorizonComparison){@('day','sunset')|ForEach-Object{"${_}-horizon-0-trial-1";"${_}-horizon-1-trial-2";"${_}-horizon-0-trial-3"}}elseif($LeafOpticsComparison){@('day','sunset')|ForEach-Object{"${_}-optics-0-trial-1";"${_}-optics-${ComparisonFoliageOptics}-trial-2";"${_}-optics-0-trial-3"}}elseif($CloudColumnComparison){@('day','sunset')|ForEach-Object{"${_}-column-0-trial-1";"${_}-column-1-trial-2";"${_}-column-0-trial-3"}}elseif($CloudDensityComparison){@('day','sunset')|ForEach-Object{"${_}-density-0-trial-1";"${_}-density-1-trial-2";"${_}-density-0-trial-3"}}elseif($CloudAmbientComparison){@('day','sunset')|ForEach-Object{"${_}-ambient-0-trial-1";"${_}-ambient-1-trial-2";"${_}-ambient-0-trial-3"}}elseif($SamplingComparison){@('day','sunset')|ForEach-Object{"${_}-samples-1-trial-1";"${_}-samples-16-trial-2";"${_}-samples-1-trial-3"}}elseif($AtmosphereComparison){@('day','sunset')|ForEach-Object{"${_}-multiple-0-trial-1";"${_}-multiple-1-trial-2";"${_}-multiple-0-trial-3"}}elseif($BounceComparison){@('day','sunset') | ForEach-Object {"${_}-bounces-${ComparisonLowBounces}-trial-1";"${_}-bounces-${ComparisonHighBounces}-trial-2";"${_}-bounces-${ComparisonLowBounces}-trial-3"}}else{@('day','sunset')}
    if($DrawDistance32){$captureNames=@('day','sunset','reload-32')}
    if($NightDisplay){$captureNames=@('day','sunset','night')}
    if($NightOnly){$captureNames=@('night')}
    if($EmissiveComparison){$captureNames=@('night-emissive-0-trial-1','night-emissive-1-trial-2','night-emissive-0-trial-3')
        if(!$combined.Contains('SULKAN_QA_EMISSIVE_COMPARISON_PASS trials=3 geometryMatched=true')){$missing+='Emissive comparison incomplete'}}
    if($ToneMapComparison -and !$RoughDenoiseComparison){foreach($phase in $captureNames){if(!$combined.Contains("SULKAN_QA_TONE_MAP_PASS phase=$phase")){$missing+="SULKAN_QA_TONE_MAP_PASS phase=$phase"}}}
    if($RoughDenoiseComparison){$captureNames=@('night-rough-denoise-0-trial-1','night-rough-denoise-1-trial-2','night-rough-denoise-0-trial-3')
        if(!$combined.Contains('SULKAN_QA_ROUGH_DENOISE_COMPARISON_PASS trials=3 geometryMatched=true')){$missing+='Rough denoise comparison incomplete'}
        if($ToneMapComparison){foreach($phase in $captureNames){if(!$combined.Contains("SULKAN_QA_TONE_MAP_PASS phase=$phase")){$missing+="Display missing for $phase"}}}
    }
    foreach($name in $captureNames) {
        if($GpuTimings -and !$combined.Contains("SULKAN_GPU_PASS_TIMINGS_PASS phase=$name ")){$missing+="GPU pass timings missing for $name"}
        $marker="SULKAN_QA_VISUAL_CAPTURE_PASS phase=$name"
        if([regex]::Matches($combined,[regex]::Escape($marker)).Count -ne 1) { $missing += "$marker must occur exactly once" }
    }
    if($CloudPreview){foreach($name in $captureNames){if(!$combined.Contains("SULKAN_QA_CLOUD_RECONSTRUCTION_PASS phase=$name")){$missing+="Cloud reconstruction missing for $name"}}}
    if($DrawDistance32){
        if(!$combined.Contains('SULKAN_QA_DRAW_DISTANCE32_PASS increased=true reloaded=true completeGeometry=true singleArena=true')){$missing+='32-chunk transition/reload incomplete'}
        if(!$combined.Contains('SULKAN_QA_DRAW_DISTANCE_CHANGED from=16 to=32')){$missing+='Actual draw-distance option change missing'}
        if($CloudPreview){foreach($prefix in @('SULKAN_QA_CLOUD_FIELD_PASS','SULKAN_QA_CLOUD_PREVIEW_PASS','SULKAN_QA_CLOUD_SHADOW_PASS','SULKAN_QA_CLOUD_SCATTERING_PASS')){
            if(!$combined.Contains("$prefix phase=reload-32")){$missing+="$prefix missing after 32-chunk reload"}
        }}
    }
    if($CloudPreview -and $CloudDiagnostic){foreach($name in $captureNames){if(!$combined.Contains("SULKAN_QA_CLOUD_COLUMN_PASS phase=$name")){$missing+="Cloud column check missing for $name"}}}
    if($CloudColumnComparison){
        foreach($name in $captureNames){foreach($prefix in @('SULKAN_QA_CLOUD_FIELD_PASS','SULKAN_QA_CLOUD_PREVIEW_PASS','SULKAN_QA_CLOUD_SHADOW_PASS','SULKAN_QA_CLOUD_SCATTERING_PASS')){
            if(!$combined.Contains("$prefix phase=$name")){$missing+="$prefix missing for $name"}
        }}
        if(!$combined.Contains('SULKAN_QA_CLOUD_COLUMN_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='Cloud column comparison incomplete'}
    }
    if($CloudMotionAudit){
        foreach($phase in @('camera-motion','motion-1','motion-2','motion-3','motion-4')){
            if(!$combined.Contains("SULKAN_QA_CLOUD_RECONSTRUCTION_PASS phase=$phase")){$missing+="Continuous cloud guide capture missing for $phase"}
        }
        if(!$combined.Contains('SULKAN_QA_CLOUD_CAMERA_MOTION_PASS')){$missing+='Continuous camera audit marker missing'}
        if(!$combined.Contains('SULKAN_QA_VISUAL_CAPTURE_PASS phase=camera-motion')){$missing+='Continuous camera end capture missing'}
    }
    if($CloudMotionSequence -and !$combined.Contains('SULKAN_QA_CLOUD_FRAME_SEQUENCE_CAPTURED frames=24 consecutive=true')){$missing+='Adjacent-frame cloud capture incomplete'}
    if($CameraBobAudit -and !$combined.Contains('SULKAN_QA_CAMERA_BOB_PASS')){$missing+='Actual camera bob projection audit missing'}
    if($CloudPreview){foreach($phase in $displayPhases){if(!$combined.Contains("SULKAN_QA_CLOUD_PREVIEW_PASS phase=$phase")){$missing+="Cloud preview missing for $phase"};if(!$combined.Contains("SULKAN_QA_CLOUD_SCATTERING_PASS phase=$phase")){$missing+="Cloud scattering missing for $phase"}}}
    if($CloudPreview){foreach($phase in $displayPhases){if(!$combined.Contains("SULKAN_QA_CLOUD_SHADOW_PASS phase=$phase")){$missing+="Cloud key-light shadow reference missing for $phase"}}}
    if($CloudDiagnostic){foreach($phase in @('day','sunset')){if(!$combined.Contains("SULKAN_QA_CLOUD_FIELD_PASS phase=$phase")){$missing+="Cloud field reference missing for $phase"}}}
    if($CloudCameraAudit){foreach($phase in @('camera-translation','camera-rotation','camera-return')){
        if(!$combined.Contains("SULKAN_QA_VISUAL_CAPTURE_PASS phase=$phase")){$missing+="Cloud camera capture missing for $phase"}
        if(!$combined.Contains("SULKAN_QA_CLOUD_SHADOW_PASS phase=$phase")){$missing+="Cloud camera shadow reference missing for $phase"}
    }}
    if($DistantWorldDiagnostic){foreach($phase in $captureNames){if(!$combined.Contains("SULKAN_QA_DISTANT_WORLD_PASS phase=$phase")){$missing+="Distant world missing for $phase"}}}
    if($HorizonComparison -and !$combined.Contains('SULKAN_QA_HORIZON_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='Horizon comparison incomplete'}
    if($SolidShadowComparison -and !$combined.Contains('SULKAN_QA_SOLID_SHADOW_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='Solid shadow comparison incomplete'}
    if($CloudSourceComparison -and !$combined.Contains('SULKAN_QA_CLOUD_SOURCE_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='Cloud source comparison incomplete'}
    if($CloudLightingComparison -and !$combined.Contains('SULKAN_QA_CLOUD_LIGHTING_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='Cloud lighting comparison incomplete'}
    if($CloudDepthComparison -and !$combined.Contains('SULKAN_QA_CLOUD_DEPTH_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='Cloud depth comparison incomplete'}
    if($DistantCloudComparison -and !$combined.Contains('SULKAN_QA_DISTANT_CLOUD_TARGET_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='Distant cloud target comparison incomplete'}
    if($RoughReflectionComparison -and !$combined.Contains('SULKAN_QA_ROUGH_SPECULAR_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='Rough reflection comparison incomplete'}
    if($RoughReflectionComparison){foreach($name in $captureNames){if(!$combined.Contains("SULKAN_QA_ROUGH_REFLECTION_PASS phase=$name ")){$missing+="Rough reflection capture missing for $name"}}}
    if($LeafSurfaceComparison -and !$combined.Contains('SULKAN_QA_LEAF_SURFACE_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='Leaf surface comparison incomplete'}
    if($SunIndirectComparison -and !$combined.Contains('SULKAN_QA_SUN_INDIRECT_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='Sun indirect comparison incomplete'}
    if($SunDiskComparison -and !$combined.Contains('SULKAN_QA_SUN_DISK_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='Sun disk comparison incomplete'}
    if($LeafBoundaryComparison -and !$combined.Contains('SULKAN_QA_LEAF_BOUNDARY_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='Leaf boundary comparison incomplete'}
    if($LeafOpticsComparison -and !$combined.Contains('SULKAN_QA_LEAF_OPTICS_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='Leaf optics comparison incomplete'}
    if($LightingAudit){foreach($phase in $captureNames){if(!$combined.Contains("SULKAN_QA_LIGHTING_AUDIT_PASS phase=$phase")){$missing+="Lighting audit missing for $phase"}}}
    if($CloudAmbientComparison){foreach($phase in @('day','sunset')){foreach($trial in @(1,2,3)){
        $value=if($trial -eq 2){1}else{0};$capture="${phase}-ambient-${value}-trial-${trial}"
        foreach($prefix in @('SULKAN_QA_VISUAL_CAPTURE_PASS','SULKAN_QA_CLOUD_FIELD_PASS','SULKAN_QA_CLOUD_PREVIEW_PASS','SULKAN_QA_CLOUD_SHADOW_PASS','SULKAN_QA_CLOUD_SCATTERING_PASS')) {
            if(!$combined.Contains("$prefix phase=$capture")){$missing+="$prefix missing for $capture"}
        }
    }};if(!$combined.Contains('SULKAN_QA_CLOUD_AMBIENT_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='Cloud ambient comparison marker missing'}}
    if($CloudDensityComparison){foreach($phase in @('day','sunset')){foreach($trial in @(1,2,3)){
        $value=if($trial -eq 2){1}else{0};$capture="${phase}-density-${value}-trial-${trial}"
        foreach($prefix in @('SULKAN_QA_VISUAL_CAPTURE_PASS','SULKAN_QA_CLOUD_FIELD_PASS','SULKAN_QA_CLOUD_PREVIEW_PASS','SULKAN_QA_CLOUD_SHADOW_PASS','SULKAN_QA_CLOUD_SCATTERING_PASS')) {
            if(!$combined.Contains("$prefix phase=$capture")){$missing+="$prefix missing for $capture"}
        }
    }};if(!$combined.Contains('SULKAN_QA_CLOUD_DENSITY_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='Cloud density comparison marker missing'}}
    if($SamplingComparison -and !$combined.Contains('SULKAN_QA_SAMPLING_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='SULKAN_QA_SAMPLING_COMPARISON_PASS trials=6 geometryMatched=true'}
    if($BounceComparison -and !$combined.Contains('SULKAN_QA_BOUNCE_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='SULKAN_QA_BOUNCE_COMPARISON_PASS trials=6 geometryMatched=true'}
    if($AtmosphereComparison -and !$combined.Contains('SULKAN_QA_ATMOSPHERE_COMPARISON_PASS trials=6 geometryMatched=true')){$missing+='SULKAN_QA_ATMOSPHERE_COMPARISON_PASS'}
} elseif($HeldItemCapture) {
$missing=@('SULKAN_QA_HELD_ITEM_CAPTURE_COMPLETE' | Where-Object {!$combined.Contains($_)})
} else {
$required = @('SULKAN_QA_WORLD_PASS', 'SULKAN_QA_GEOMETRY_PASS', 'SULKAN_QA_GEOMETRY_REBUILD_PASS',
    'SULKAN_RT_TERRAIN_CODEC_PASS', 'SULKAN_QA_RESOURCE_RELOAD_PASS', 'SULKAN_QA_RESIZE_PASS',
    'SULKAN_QA_WORLD_RAYS_PASS removed=false', 'SULKAN_QA_WORLD_RAYS_PASS removed=true',
    'SULKAN_QA_INCREMENTAL_SCENE_PASS removed=false', 'SULKAN_QA_INCREMENTAL_SCENE_PASS removed=true',
    'SULKAN_QA_SCENE_RETIRE_RECORDED', 'SULKAN_QA_SCENE_RETIRE_PASS liveBuffers=0')
$missing = @($required | Where-Object { !$combined.Contains($_) })
if (($RayVisibility -or $RayLighting) -and !$combined.Contains('SULKAN_QA_RAY_VISIBILITY_DISPATCHED')) { $missing += 'SULKAN_QA_RAY_VISIBILITY_DISPATCHED' }
if ($RayLighting -and !$Reflections -and !$Atmosphere -and !$combined.Contains('SULKAN_QA_DEFERRED_LIGHTING_PASS')) { $missing += 'SULKAN_QA_DEFERRED_LIGHTING_PASS' }
if ($Atmosphere) {
    if($MultipleScattering){foreach($phase in @('day','sunset')){if(!$combined.Contains("SULKAN_QA_MULTIPLE_SCATTERING_PASS phase=$phase")){$missing+="Multiple scattering reference missing for $phase"}}}
    if(!$combined.Contains('SULKAN_QA_ATMOSPHERIC_LIGHTING_PHASES_PASS phases=32 endOfWorldFrame=true')) {
        $missing += 'SULKAN_QA_ATMOSPHERIC_LIGHTING_PHASES_PASS phases=32 endOfWorldFrame=true'
    }
    foreach($marker in @('SULKAN_QA_ATMOSPHERE_PASS phase=day','SULKAN_QA_ATMOSPHERE_PASS phase=sunset')) {
        if(!$combined.Contains($marker)) { $missing += $marker }
        elseif([regex]::Matches($combined,[regex]::Escape($marker)).Count -ne 1) { $missing += "$marker must occur exactly once" }
    }
}
if ($AlphaTraversal -and !$combined.Contains('SULKAN_QA_ALPHA_TRAVERSAL_PASS')) { $missing += 'SULKAN_QA_ALPHA_TRAVERSAL_PASS' }
if($FoliageTransmission -and !$combined.Contains('SULKAN_QA_FOLIAGE_PASS')) { $missing += 'SULKAN_QA_FOLIAGE_PASS' }
if($IndirectDiagnostic) {
    foreach($marker in @('SULKAN_QA_INDIRECT_RAW_PASS removed=false','SULKAN_QA_INDIRECT_RAW_PASS removed=true')) {
        if(!$combined.Contains($marker)) { $missing += $marker }
    }
}
if($IndirectDiagnostic -and !$combined.Contains('SULKAN_QA_INDIRECT_SAMPLING_PASS')) {$missing+='SULKAN_QA_INDIRECT_SAMPLING_PASS'}
if($IndirectTemporal -and !$VisualWorld) {
    foreach($marker in @('SULKAN_QA_INDIRECT_TEMPORAL_PASS phase=steady','SULKAN_QA_INDIRECT_TEMPORAL_PASS phase=scene-change','SULKAN_QA_INDIRECT_TEMPORAL_PASS phase=motion')) {
        if(!$combined.Contains($marker)) {$missing+=$marker}
    }
}
if($IndirectSpatial -and !$VisualWorld -and !$combined.Contains('SULKAN_QA_INDIRECT_SPATIAL_PASS')) {$missing+='SULKAN_QA_INDIRECT_SPATIAL_PASS'}
if($LeafIndirect -and !$VisualWorld) {
    foreach($marker in @('SULKAN_QA_LEAF_INDIRECT_RAW_PASS removed=false','SULKAN_QA_LEAF_INDIRECT_RAW_PASS removed=true','SULKAN_QA_LEAF_INDIRECT_TEMPORAL_PASS phase=steady','SULKAN_QA_LEAF_INDIRECT_TEMPORAL_PASS phase=motion','SULKAN_QA_LEAF_INDIRECT_TEMPORAL_PASS phase=scene-change')) {
        if(!$combined.Contains($marker)){$missing+=$marker}
    }
}
if($AutoExposure -and !$VisualWorld) {
    foreach($marker in @('SULKAN_QA_EXPOSURE_PASS phase=reset','SULKAN_QA_EXPOSURE_PASS phase=steady')) {
        if(!$combined.Contains($marker)){$missing+=$marker}
    }
}
if($WaterHdr -and !$VisualWorld -and !$combined.Contains('SULKAN_QA_WATER_PASS')){$missing+='SULKAN_QA_WATER_PASS'}
if ($RayOrigin) {
    foreach($marker in @('SULKAN_QA_RAY_ORIGIN_PRECISION_PASS','SULKAN_QA_FAR_REFLECTION_PASS removed=false','SULKAN_QA_FAR_REFLECTION_PASS removed=true','SULKAN_QA_RAY_ORIGIN_RETURN_PASS')) {
        if(!$combined.Contains($marker)) { $missing += $marker }
    }
    if($WorldDlss -and !$combined.Contains('SULKAN_QA_FAR_WORLD_DLSS_PASS')) { $missing += 'SULKAN_QA_FAR_WORLD_DLSS_PASS' }
}
if ($Reflections) {
    foreach($marker in @('SULKAN_QA_REFLECTION_PASS removed=false','SULKAN_QA_REFLECTION_PASS removed=true')) {
        if(!$combined.Contains($marker)) { $missing += $marker }
    }
}
if ($RayHitMaterials -and !$combined.Contains('SULKAN_QA_RAY_HIT_MATERIALS_PASS')) { $missing += 'SULKAN_QA_RAY_HIT_MATERIALS_PASS' }
if ($RayHitMaterials -and !$combined.Contains('SULKAN_QA_RAY_ATLAS_RELOAD_PASS')) { $missing += 'SULKAN_QA_RAY_ATLAS_RELOAD_PASS' }
if ($DlssFeatures -and !$combined.Contains('SULKAN_DLSS_DEVICE_NEGOTIATION requested=true enabled=true')) { $missing += 'SULKAN_DLSS_DEVICE_NEGOTIATION requested=true enabled=true' }
if ($TemporalInputs -and !$combined.Contains('SULKAN_QA_CAMERA_MOTION_PASS')) { $missing += 'SULKAN_QA_CAMERA_MOTION_PASS' }
if($MotionPhaseSweep){foreach($sign in @(1,-1)){if(!$combined.Contains("SULKAN_QA_CAMERA_SWEEP_PASS sign=$sign phases=32")){$missing+="Camera sweep missing for sign=$sign"}}}
if ($TemporalJitter -and !$combined.Contains('SULKAN_QA_JITTER_ONLY_PASS')) { $missing += 'SULKAN_QA_JITTER_ONLY_PASS' }
if ($WorldDlss -and !$combined.Contains('SULKAN_QA_WORLD_DLSS_PASS')) { $missing += 'SULKAN_QA_WORLD_DLSS_PASS' }
if($SkyMotion){foreach($marker in @('SULKAN_QA_SKY_MOTION_PASS sign=1','SULKAN_QA_SKY_MOTION_PASS sign=-1')){if(!$combined.Contains($marker)){$missing+=$marker}}}
if ($WorldDlss) {
    foreach($marker in @('SULKAN_QA_WORLD_DLSS_RESIZE_PASS','SULKAN_DLSS_WORLD_RETIRE_PASS lastContext=true liveContexts=0')) {
        if(!$combined.Contains($marker)) { $missing += $marker }
    }
}
if ($RayAttributes) {
    foreach ($marker in @('SULKAN_QA_RAY_ATTRIBUTE_ARENA_PASS removed=false','SULKAN_QA_RAY_ATTRIBUTE_ARENA_PASS removed=true')) {
        if (!$combined.Contains($marker)) { $missing += $marker }
    }
}
}
$passed = !$expired -and $process.ExitCode -eq 0 -and $missing.Count -eq 0 -and $combined -notmatch 'SULKAN_(QA|DLSS)_\w*FAIL'
$modsUnchanged = $true
foreach ($name in $runtimeModHashes.Keys) {
    if ((Get-FileHash -LiteralPath (Join-Path $qaPath ('mods/' + $name)) -Algorithm SHA256).Hash -ne $runtimeModHashes[$name]) { $modsUnchanged = $false }
}
if ((Get-FileHash -LiteralPath $copiedArtifact -Algorithm SHA256).Hash -ne $artifactHash) { $modsUnchanged = $false }
$passed = $passed -and $modsUnchanged -and !$activationFailure
$result = [ordered]@{
    passed = $passed; pid = $process.Id; exitCode = $process.ExitCode; timedOut = $expired
    activationFailure = $activationFailure
    missingMarkers = $missing; artifactSha256 = $artifactHash; runtimeModsUnchanged = $modsUnchanged
    runtimeModHashes = $runtimeModHashes; rayVisibility = [bool]$RayVisibility; rayLighting = [bool]$RayLighting; rayAttributes = [bool]$RayAttributes; rayHitMaterials = [bool]$RayHitMaterials; dlssFeatures = [bool]$DlssFeatures; temporalInputs = [bool]$TemporalInputs
    voicechatSha256 = $voiceHash
    scope = $(if($VisualWorld){'Natural forest/river execution, actual camera validation and wall cadence; not cinematic acceptance or final performance'}elseif($DiffuseIndirect){'Single-sample diffuse HDR transport, source CPU/PNG comparison, optional atmosphere, offscreen emitter/removal and world lifecycle; no temporal filtering or finished GI'}elseif($Reflections){'Offscreen smooth-metal opaque reflection fixture, HDR composition, removal and world lifecycle; not cinematic quality or performance'}else{'Opaque fixture rays and world lifecycle; not cinematic quality or performance'})
    temporalJitter = [bool]$TemporalJitter
    worldDlss = [bool]$WorldDlss
    reflections = [bool]$Reflections
    roughReflections = [bool]$RoughReflections
    roughReflectionComparison = [bool]$RoughReflectionComparison
    alphaTraversal = [bool]$AlphaTraversal
    rayOrigin = [bool]$RayOrigin
    atmosphere = [bool]$Atmosphere
    multipleScattering = [bool]$MultipleScattering
    foliageTransmission = [bool]$FoliageTransmission
    indirectDiagnostic = [bool]$IndirectDiagnostic
    diffuseIndirect = [bool]$DiffuseIndirect
    indirectTemporal = [bool]$IndirectTemporal
    indirectSpatial = [bool]$IndirectSpatial
    indirectBounces = $IndirectBounces
    indirectSamples = $IndirectSamples
    indirectReferenceStride = $IndirectReferenceStride
    leafIndirect = [bool]$LeafIndirect
    foliageOptics = $FoliageOptics
    leafOpticsComparison = [bool]$LeafOpticsComparison
    leafSurface = $LeafSurface
    leafSurfaceComparison = [bool]$LeafSurfaceComparison
    leafBoundary = $LeafBoundary
    leafBoundaryComparison = [bool]$LeafBoundaryComparison
    sunShadowSamples = $SunShadowSamples
    sunDiskComparison = [bool]$SunDiskComparison
    sunIndirectSamples = $SunIndirectSamples
    sunIndirectComparison = [bool]$SunIndirectComparison
    comparisonFoliageOptics = $ComparisonFoliageOptics
    autoExposure = [bool]$AutoExposure
    displayToneMapper = $DisplayToneMapper
    toneMapComparison = [bool]$ToneMapComparison
    nightDisplay = [bool]$NightDisplay
    nightSky = [bool]$NightSky
    nightOnly = [bool]$NightOnly
    interiorVisual = [bool]$InteriorVisual
    emissiveMaterials = [bool]$EmissiveMaterials
    emissiveSampling = $EmissiveSampling
    emissiveComparison = [bool]$EmissiveComparison
    roughDenoise = $RoughDenoise
    roughDenoiseComparison = [bool]$RoughDenoiseComparison
    roughNoiseSequence = [bool]$RoughNoiseSequence
    waterHdr = [bool]$WaterHdr
    skyMotion = [bool]$SkyMotion
    heldItemCapture = [bool]$HeldItemCapture
    visualWorld = [bool]$VisualWorld
    requestedCameraPitch = $(if($PSBoundParameters.ContainsKey('CameraPitch')){$CameraPitch}else{$null})
    bounceComparison = [bool]$BounceComparison
    atmosphereComparison = [bool]$AtmosphereComparison
    samplingComparison = [bool]$SamplingComparison
    lightingAudit = [bool]$LightingAudit
    distantWorld = [bool]$DistantWorld
    distantWorldDiagnostic = [bool]$DistantWorldDiagnostic
    distantWorldMode = $DistantWorldMode
    horizonComparison = [bool]$HorizonComparison
    cloudDiagnostic = [bool]$CloudDiagnostic
    cloudPreview = [bool]$CloudPreview
    cloudCameraAudit = [bool]$CloudCameraAudit
    cloudMotionAudit = [bool]$CloudMotionAudit
    cloudMotionSequence = [bool]$CloudMotionSequence
    cameraBobAudit = [bool]$CameraBobAudit
    computeExperiment = $ComputeExperiment; workgroupExperiment = [bool]$WorkgroupExperiment; performanceProfile = [bool]$PerformanceProfile; profilePlan = $ProfilePlan
    gpuTimings = [bool]$GpuTimings
    solidShadowComparison = [bool]$SolidShadowComparison
    cloudLightingComparison = [bool]$CloudLightingComparison
    cloudLightingVolume = [bool]$CloudLightingVolume
    cloudSourceCache = [bool]$CloudSourceCache
    cloudSourceComparison = [bool]$CloudSourceComparison
    cloudCameraHeight = $CloudCameraHeight
    cloudInteriorTree = [bool]$CloudInteriorTree
    distantCloudComparison = [bool]$DistantCloudComparison
    cloudDepthAware = [bool]$CloudDepthAware
    cloudDepthComparison = [bool]$CloudDepthComparison
    cloudAmbientComparison = [bool]$CloudAmbientComparison
    cloudDensityComparison = [bool]$CloudDensityComparison
    cloudColumnComparison = [bool]$CloudColumnComparison
    cloudViewDensitySubsteps = $CloudViewDensitySubsteps
    cloudReconstructionGuides = [bool]$CloudReconstructionGuides
    cloudDensityProfile = $CloudDensityProfile
    motionPhaseSweep = [bool]$MotionPhaseSweep
    comparisonLowBounces = $ComparisonLowBounces
    comparisonHighBounces = $ComparisonHighBounces
    raySurfaceProbe = [bool]$RaySurfaceProbe
    renderDistance = $RenderDistance
    drawDistance32 = [bool]$DrawDistance32
    maxFpsOption = $MaxFps
    vsyncRequested = !$DisableVsync
}
if($IndirectTemporal -and !$VisualWorld) { $result.scope='Diffuse raw CPU/source transport, temporal recurrence/rejection at rest, on camera translation and scene change, plus world lifecycle; not high-sample convergence, spatial denoising or finished GI' }
if($IndirectSpatial -and !$VisualWorld) {$result.scope='Diffuse raw transport, temporal recurrence/rejection, independent three-pass spatial filter arithmetic and HDR composition, plus world lifecycle; not high-sample convergence or finished GI'}
if($LeafIndirect -and !$VisualWorld){$result.scope='Static leaf front/back raw CPU/source transport, separate temporal recurrence, optional filtering and leaf HDR composition, source-based camera motion and world lifecycle; not wind, volumetric density, high-sample convergence or finished GI'}
if($VisualWorld) { $result.scope='Natural forest/river capture, finite HDR/NGX and fixed-camera wall cadence; optional post-timing history histogram; not visual acceptance, full cinematic quality or complete performance target' }
if($RaySurfaceProbe) { $result.scope='World-free GPU hit decoding through two payload pages, legacy shared BLAS and compact canonical quads with transient build inputs; not world rendering or performance' }
if($HeldItemCapture){$result.scope='Six first-person screenshots with verified held-item state against stone, foliage and water; capture completion only, requires visual inspection and does not run transport/lifecycle conformance'}
$result | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $qaPath 'result.json')
$process.Dispose()
if (!$passed) { throw "QA world failed; inspect $qaPath/result.json and logs" }
Write-Output "QA world passed. Evidence: $qaPath"
