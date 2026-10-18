#Requires -Version 7.0
param([Parameter(Mandatory)][string] $PackDirectory)
$ErrorActionPreference = 'Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$target=[IO.Path]::GetFullPath($PackDirectory)
$manifestPath=Join-Path $target 'sulkan.json'
$manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -AsHashtable
if($manifest.name -ne 'Sulkan experimental deferred ray lighting') {throw 'Ray counting requires an unmodified disposable Create-RayLightingPack output'}
if($manifest.options.Contains('SULKAN_QA_RAY_QUERY_COUNTS')) {throw 'Ray query counting is already enabled'}
$selected=@($manifest.passes | Where-Object {$_.name -in @('indirect.trace','leaf.indirect.trace')})
if(@($selected | Where-Object {$_.name -eq 'indirect.trace'}).Count -ne 1 -or $selected.Count -gt 2) {throw 'Expected one indirect.trace pass and at most one leaf.indirect.trace pass'}
function Read-Shader([string] $Relative) {
    return (Get-Content -LiteralPath (Join-Path $target $Relative) -Raw).Replace("`r`n","`n")
}
function Replace-Once([string] $Source,[string] $Before,[string] $After,[string] $Label) {
    $occurrences=[regex]::Matches($Source,[regex]::Escape($Before)).Count
    if($occurrences -ne 1) {throw "Ray counter transform $Label expected one occurrence, found $occurrences"}
    return $Source.Replace($Before,$After)
}
$pending=[ordered]@{}
$sourceHashes=[ordered]@{}
function Source-Hash([string] $Text) {
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($Text))).ToLowerInvariant()
}
# Clone loader/shared includes under new pack paths. Normal shader sources and
# the loader's reserved sulkan/* includes are never replaced by this helper.
$terrain=(Get-Content -LiteralPath (Join-Path $projectRoot 'src/client/resources/assets/sulkan/shaders/include/ray_terrain_traversal.glsl') -Raw).Replace("`r`n","`n")
$sourceHashes['loaderTerrain']=Source-Hash $terrain
if([regex]::Matches($terrain,'rayQueryInitializeEXT\(').Count -ne 1) {throw 'Unexpected loader query initialization count'}
$terrain=Replace-Once $terrain '    rayQueryInitializeEXT(query, SulkanRayScene,' "    sulkanQaCountRay(anyHit ? 2u : sulkanQaPathRole);`n    rayQueryInitializeEXT(query, SulkanRayScene," 'terrain initialization'
$pending['shaders/qa-ray-terrain.glsl']=$terrain

$foliage=Read-Shader 'shaders/foliage.glsl'
$sourceHashes['foliage']=Source-Hash $foliage
if([regex]::Matches($foliage,'rayQueryInitializeEXT\(').Count -ne 3) {throw 'Unexpected foliage query initialization count'}
$foliage=Replace-Once $foliage '    rayQueryInitializeEXT(query,SulkanRayScene,gl_RayFlagsNoOpaqueEXT,3,origin,minimum,direction,maximum);' "    sulkanQaCountRay(sulkanQaPathRole);`n    rayQueryInitializeEXT(query,SulkanRayScene,gl_RayFlagsNoOpaqueEXT,3,origin,minimum,direction,maximum);" 'boundary radiance initialization'
$foliage=Replace-Once $foliage '    rayQueryInitializeEXT(solid,SulkanRayScene,gl_RayFlagsOpaqueEXT|gl_RayFlagsTerminateOnFirstHitEXT,' "    sulkanQaCountRay(2u);`n    rayQueryInitializeEXT(solid,SulkanRayScene,gl_RayFlagsOpaqueEXT|gl_RayFlagsTerminateOnFirstHitEXT," 'solid shadow preflight initialization'
$foliage=Replace-Once $foliage '    rayQueryInitializeEXT(query,SulkanRayScene,gl_RayFlagsNoOpaqueEXT,3,origin,.002,direction,maximum);' "    sulkanQaCountRay(2u);`n    rayQueryInitializeEXT(query,SulkanRayScene,gl_RayFlagsNoOpaqueEXT,3,origin,.002,direction,maximum);" 'foliage solar enumeration initialization'
$pending['shaders/qa-foliage.glsl']=$foliage

$emission=Read-Shader 'shaders/emission-sampling.glsl'
$sourceHashes['emissionSampling']=Source-Hash $emission
$emitterQuery='    rayQueryEXT query;sulkanTraceTerrain(query,Atlas,origin,.002,direction,distance+.01,false);'
$emission=Replace-Once $emission $emitterQuery "    uint sulkanQaSavedRole=sulkanQaPathRole;sulkanQaPathRole=3u;`n$emitterQuery`n    sulkanQaPathRole=sulkanQaSavedRole;" 'emitter visibility role'
$pending['shaders/qa-emission-sampling.glsl']=$emission
$pending['shaders/qa-ray-query-counts.glsl']=(Get-Content -LiteralPath (Join-Path $projectRoot 'examples/ray-lighting/qa-ray-query-counts.glsl') -Raw).Replace("`r`n","`n")
$passDetails=@()
foreach($pass in $selected) {
    if(!$pass.rayScene -or !$pass.rayAttributes -or @($pass.writes).Count -lt 1 -or @($pass.writes).Count -ge 8) {throw "Ray counter output cannot be added to $($pass.name)"}
    $expected=if($pass.name -eq 'indirect.trace'){'shaders/indirect.comp'}else{'shaders/leaf-indirect.comp'}
    if($pass.compute -ne $expected -or @($pass.localSize).Count -ne 3 -or $pass.localSize[2] -ne 1 -or $pass.Contains('dispatch')) {throw "Unexpected counted shader/dispatch in $($pass.name)"}
    $source=Read-Shader $expected
    $sourceHashes[$pass.name]=Source-Hash $source
    if([regex]::Matches($source,'rayQueryInitializeEXT\(').Count -ne 0) {throw "Unexpected direct query initialization in $expected"}
    $output='Output'+@($pass.writes).Count
    $source=Replace-Once $source '#version 460' "#version 460`n#define SULKAN_QA_COUNT_OUTPUT $output`n#include `"shaders/qa-ray-query-counts.glsl`"" "$($pass.name) header"
    $source=Replace-Once $source '#include "sulkan/ray-terrain.glsl"' '#include "shaders/qa-ray-terrain.glsl"' "$($pass.name) terrain include"
    $source=Replace-Once $source '#include "shaders/foliage.glsl"' '#include "shaders/qa-foliage.glsl"' "$($pass.name) foliage include"
    $source=Replace-Once $source '#include "shaders/emission-sampling.glsl"' '#include "shaders/qa-emission-sampling.glsl"' "$($pass.name) emission include"
    $source=Replace-Once $source '        rayQueryEXT query;foliageTraceRadiance(query,Atlas,origin,0.002,direction,128.0);' "        sulkanQaPathRole=1u;`n        rayQueryEXT query;foliageTraceRadiance(query,Atlas,origin,0.002,direction,128.0);" "$($pass.name) continuation role"
    $source=Replace-Once $source '        foliageTraceRadiance(query,Atlas,origin,0.002,direction,128.0);' "        sulkanQaPathRole=0u;`n        foliageTraceRadiance(query,Atlas,origin,0.002,direction,128.0);" "$($pass.name) primary role"
    $source=Replace-Once $source 'void main() {' 'void sulkanQaOriginalMain() {' "$($pass.name) entry point"
    $source+="`nvoid main() {`n    sulkanQaRayQueries=uvec4(0u);sulkanQaPathRole=0u;`n    sulkanQaOriginalMain();`n    ivec2 pixel=ivec2(gl_GlobalInvocationID.xy);`n    if(all(lessThan(pixel,imageSize(Output0))))imageStore(SULKAN_QA_COUNT_OUTPUT,pixel,sulkanQaRayQueries);`n}`n"
    $pending[$expected]=$source
    $resource=if($pass.name -eq 'indirect.trace'){'qa.ray.queries.indirect'}else{'qa.ray.queries.leaf'}
    if($manifest.resources.Contains($resource)) {throw "Counter resource already exists: $resource"}
    # History prohibits graph lifetime aliasing before the asynchronous readback.
    # Each selected pass adds 32 bytes/input pixel (two RGBA32_UINT images).
    $manifest.resources[$resource]=@{format='RGBA32_UINT';history=$true}
    $pass.writes+=@($resource)
    $passDetails+=@{pass=$pass.name;resource=$resource;output=$output;bytesPerInputPixel=32;source=$expected}
}
$manifest.name+=' (QA ray-query counting; untimed)'
$manifest.options['SULKAN_QA_RAY_QUERY_COUNTS']=@{default=1;values=@(1)}
# Use existing headroom when the pack already requests the supported maximum.
# Graph allocation validation still rejects the pack if its actual resources do
# not fit; do not raise the renderer's 2048 MiB safety limit for instrumentation.
$previousBudget=if($manifest.Contains('budgetMiB')){[int]$manifest.budgetMiB}else{512}
$manifest.budgetMiB=[Math]::Min(2048,$previousBudget+128)
$details=@{
    version=1;passes=$passDetails;lanes=@('primaryRadiance','continuationRadiance','solarVisibility','emissiveVisibility')
    initializationSites=@{loaderTerrain=1;foliageBoundaryRadiance=1;solidShadowPreflight=1;foliageShadowEnumeration=1}
    originalSourceSha256=$sourceHashes;originalBudgetMiB=$previousBudget;diagnosticBudgetMiB=$manifest.budgetMiB
    scope='Actual rayQueryInitializeEXT executions in diffuse and leaf diffuse passes only; not configured estimates, hardware triangle/BVH work, other passes or performance. Private per-invocation counters, no atomics. History counter images add 32 bytes/input pixel/selected pass; readback memory is additional. Exclude counted frames from FPS, GPU timing and baseline memory claims.'
}
# Validate every transform before mutating the disposable pack. No normal
# repository shader file or other mod is changed, and failed transforms throw.
foreach($entry in $pending.GetEnumerator()) {
    Set-Content -LiteralPath (Join-Path $target $entry.Key) -Value $entry.Value -Encoding utf8NoBOM
}
$manifest | ConvertTo-Json -Depth 32 | Set-Content -LiteralPath $manifestPath -Encoding utf8NoBOM
$details | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath (Join-Path $target 'qa-ray-query-counts.json') -Encoding utf8NoBOM
Write-Output "Untimed actual diffuse ray-query counters enabled: $target"
