#Requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet(0,1,2,3,5)][int] $RealtimeStep = 0,
    [Parameter(Mandatory)][string]$QaDirectory,
    [string]$InstanceMods = 'work/qa-world-dlss-5/mods',
    [ValidateSet(3000,12600,18000)][int]$ProfileTime = 3000,
    [string]$ProfilePlan = 'static,motion,off-clouds,off-gi,off-reflections,off-water,off-shadows,off-denoising,static-repeat,stream,settled',
    [switch]$WorkgroupExperiment,
    [switch]$NativeFrameComparison,
    [switch]$SkipImageComparison,
    [switch]$PrepareGeometry,
    [switch]$CanonicalPackComparison,
    [switch]$RayQueryCounts,
    [switch]$CloudNoisePairs,
    [switch]$CloudNoisePairsPerturb,
    [switch]$CloudEnvironmentInputs,
    [switch]$TemporalProfile,
    [switch]$RayReconstruction,
    [ValidateSet('none','sr','native','native-direct','fixed-sr','rr')][string]$FoliageSequence='none',
    [ValidateSet("workgroup","cloud-cell","cloud-3d","cloud-3d-env","cloud-3d-env32","shadow-material","indirect-lazy-sky")][string]$ComputeExperiment = "workgroup",
    [ValidateRange(2,32)][int]$RenderDistance = 6,
    [ValidateRange(180,3600)][int]$TimeoutSeconds = 1800
)
$ErrorActionPreference='Stop'
if($CloudEnvironmentInputs -and (!$WorkgroupExperiment -or $ComputeExperiment -ne 'cloud-3d-env' -or $CloudNoisePairs -or $RayQueryCounts -or $TemporalProfile -or $NativeFrameComparison -or $CanonicalPackComparison -or $SkipImageComparison -or $ProfilePlan -notin @('static','motion'))){throw 'CloudEnvironmentInputs requires one separate cloud-3d-env A/A/B image comparison'}
if($CloudNoisePairsPerturb -and !$CloudNoisePairs){throw 'CloudNoisePairsPerturb requires CloudNoisePairs'}
if($CloudNoisePairs -and ($ComputeExperiment -ne 'cloud-3d-env' -or $WorkgroupExperiment -or $NativeFrameComparison -or $CanonicalPackComparison -or $TemporalProfile -or $RayQueryCounts)){throw 'CloudNoisePairs requires separate cloud-3d-env diagnostic mode'}
$workspace=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$workspace=Split-Path $workspace -Parent
$target=[IO.Path]::GetFullPath($QaDirectory,$workspace)
if(Test-Path -LiteralPath $target){throw 'Use a fresh disposable profiling directory'}
$telemetry=$target+'-gpu.csv'
$gpuInfo=$target+'-gpu-info.txt'
$sourceInfo=$target+'-source.json'
@{rayReconstruction=[bool]$RayReconstruction;foliageSequence=$FoliageSequence;realtimeStep=$RealtimeStep;commit=(& git -C (Split-Path $PSScriptRoot -Parent) rev-parse HEAD);dirty=(@(& git -C (Split-Path $PSScriptRoot -Parent) status --porcelain)).Count -gt 0;
  profilePlan=$ProfilePlan;profileTime=$ProfileTime;renderDistance=$RenderDistance;experiment=$ComputeExperiment;
  alternatePipeline=[bool]$WorkgroupExperiment;canonicalPackComparison=[bool]$CanonicalPackComparison;rayQueryCounts=[bool]$RayQueryCounts;temporalProfile=[bool]$TemporalProfile;nativeFrameComparison=[bool]$NativeFrameComparison;skipImageComparison=[bool]$SkipImageComparison;prepareGeometry=[bool]$PrepareGeometry;
  timeoutSeconds=$TimeoutSeconds;cloudNoisePairs=[bool]$CloudNoisePairs;cloudNoisePairsPerturb=[bool]$CloudNoisePairsPerturb;cloudEnvironmentInputs=[bool]$CloudEnvironmentInputs} | ConvertTo-Json | Set-Content -LiteralPath $sourceInfo
& nvidia-smi -q | Set-Content -LiteralPath $gpuInfo
# Owned, read-only telemetry helper; WDDM reports device-wide usage, not process residency.
$stopFile=$target+'-gpu.stop'
$monitor=Start-Process -FilePath (Get-Command pwsh).Source -ArgumentList @('-NoProfile','-File',('"'+(Join-Path $PSScriptRoot 'Sample-Gpu.ps1')+'"'),'-OutputPath',('"'+$telemetry+'"'),'-StopFile',('"'+$stopFile+'"'),'-ParentPid',$PID) -WindowStyle Hidden -PassThru
try {
    & (Join-Path $PSScriptRoot 'Run-QaWorld.ps1') -RealtimeStep $RealtimeStep -CloudEnvironmentInputs:$CloudEnvironmentInputs -CloudNoisePairs:$CloudNoisePairs -CloudNoisePairsPerturb:$CloudNoisePairsPerturb -InstanceMods ([IO.Path]::GetFullPath($InstanceMods,$workspace)) -QaDirectory $target `
        -RayReconstruction:$RayReconstruction -FoliageSequence $FoliageSequence -TemporalProfile:$TemporalProfile -RayQueryCounts:$RayQueryCounts -CanonicalPackComparison:$CanonicalPackComparison -PrepareGeometry:$PrepareGeometry -SkipImageComparison:$SkipImageComparison -NativeFrameComparison:$NativeFrameComparison -ProfileTime $ProfileTime -ComputeExperiment $ComputeExperiment -WorkgroupExperiment:$WorkgroupExperiment -RayLighting -VisualWorld -PerformanceProfile -ProfilePlan $ProfilePlan -RenderDistance $RenderDistance -CameraPitch -10 `
        -Reflections -RoughReflections -RoughDenoise 1 -Atmosphere -MultipleScattering -FoliageTransmission `
        -DiffuseIndirect -LeafIndirect -IndirectTemporal -IndirectSpatial -IndirectBounces 8 -IndirectSamples 4 -FoliageOptics 2 `
        -AutoExposure -DisplayToneMapper 2 -NightSky -WaterHdr -DistantWorld -CloudPreview `
        -CloudDensityProfile 1 -CloudViewDensitySubsteps 1 -CloudReconstructionGuides -WorldDlss -GpuTimings `
        -NativeLibrary (Join-Path $workspace 'outputs/sulkan-cinematic/build/native/dlss/sulkan_ngx_probe.dll') `
        -RuntimeDirectory (Join-Path $workspace 'work/dlss-sdk/lib/Windows_x86_64/rel') -MaxFps 260 -DisableVsync -TimeoutSeconds $TimeoutSeconds
} finally {
    Set-Content -LiteralPath $stopFile -Value 'stop'
    if(!$monitor.WaitForExit(5000)){$monitor.Kill();$monitor.WaitForExit()}
    if(Test-Path -LiteralPath $target){Copy-Item -LiteralPath $telemetry,$gpuInfo,$sourceInfo -Destination $target}
}
