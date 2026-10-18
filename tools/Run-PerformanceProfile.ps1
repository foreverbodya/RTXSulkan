#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$QaDirectory,
    [string]$InstanceMods = 'work/qa-world-dlss-5/mods',
    [ValidateSet(3000,12600,18000)][int]$ProfileTime = 3000,
    [string]$ProfilePlan = 'static,motion,off-clouds,off-gi,off-reflections,off-water,off-shadows,off-denoising,static-repeat,stream,settled',
    [switch]$WorkgroupExperiment,
    [switch]$NativeFrameComparison,
    [switch]$SkipImageComparison,
    [ValidateSet("workgroup","cloud-cell","cloud-3d","cloud-3d-env","cloud-3d-env32")][string]$ComputeExperiment = "workgroup",
    [ValidateRange(2,32)][int]$RenderDistance = 6
)
$ErrorActionPreference='Stop'
$workspace=Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$workspace=Split-Path $workspace -Parent
$target=[IO.Path]::GetFullPath($QaDirectory,$workspace)
if(Test-Path -LiteralPath $target){throw 'Use a fresh disposable profiling directory'}
$telemetry=$target+'-gpu.csv'
$gpuInfo=$target+'-gpu-info.txt'
& nvidia-smi -q | Set-Content -LiteralPath $gpuInfo
# Owned, read-only telemetry helper; WDDM reports device-wide usage, not process residency.
$monitor=Start-Process -FilePath (Get-Command nvidia-smi).Source -ArgumentList @('--query-gpu=timestamp,utilization.gpu,utilization.memory,memory.used,memory.total,power.draw,clocks.gr,clocks.mem','--format=csv,noheader,nounits','-lms','500','-f',('"'+$telemetry+'"')) -WindowStyle Hidden -PassThru
try {
    & (Join-Path $PSScriptRoot 'Run-QaWorld.ps1') -InstanceMods ([IO.Path]::GetFullPath($InstanceMods,$workspace)) -QaDirectory $target `
        -SkipImageComparison:$SkipImageComparison -NativeFrameComparison:$NativeFrameComparison -ProfileTime $ProfileTime -ComputeExperiment $ComputeExperiment -WorkgroupExperiment:$WorkgroupExperiment -RayLighting -VisualWorld -PerformanceProfile -ProfilePlan $ProfilePlan -RenderDistance $RenderDistance -CameraPitch -10 `
        -Reflections -RoughReflections -RoughDenoise 1 -Atmosphere -MultipleScattering -FoliageTransmission `
        -DiffuseIndirect -LeafIndirect -IndirectTemporal -IndirectSpatial -IndirectBounces 8 -IndirectSamples 4 -FoliageOptics 2 `
        -AutoExposure -DisplayToneMapper 2 -NightSky -WaterHdr -DistantWorld -CloudPreview `
        -CloudDensityProfile 1 -CloudViewDensitySubsteps 1 -CloudReconstructionGuides -WorldDlss -GpuTimings `
        -NativeLibrary (Join-Path $workspace 'outputs/sulkan-cinematic/build/native/dlss/sulkan_ngx_probe.dll') `
        -RuntimeDirectory (Join-Path $workspace 'work/dlss-sdk/lib/Windows_x86_64/rel') -MaxFps 260 -DisableVsync -TimeoutSeconds 1800
} finally {
    if(!$monitor.HasExited){$monitor.Kill();$monitor.WaitForExit()}
    if(Test-Path -LiteralPath $target){Copy-Item -LiteralPath $telemetry,$gpuInfo -Destination $target}
}
