#Requires -Version 7.0
param([Parameter(Mandatory)][string]$PackDirectory)
$ErrorActionPreference='Stop'
$path=Join-Path $PackDirectory 'sulkan.json'
$m=Get-Content -LiteralPath $path -Raw|ConvertFrom-Json -AsHashtable
if(!$m.options.ContainsKey('REALTIME_DIFFUSE')){throw 'RR currently requires the realtime G-buffer lighting pack'}
$m.reconstruction.type='dlss_rr_quality'
$m.reconstruction.guides=@{diffuse='rr.diffuse';specular='rr.specular';normalRoughness='rr.geometry';specularDistance='rr.distance'}
foreach($n in @('rr.diffuse','rr.specular','rr.geometry')){$m.resources[$n]=@{format='RGBA16_FLOAT'}}
$m.resources['rr.distance']=@{format='R32_FLOAT'}
$m.passes+=@{name='rr.guides';compute='shaders/rr-guides.comp';localSize=@(8,8,1);reads=@{Surface='surface.data';Depth='$depth';Albedo='gbuffer.albedo';NormalRoughness='gbuffer.normal';EmissionMetal='gbuffer.emission';SpecularHit='rough.reflection.hit';MirrorHit='ray.reflection.hit'};writes=@('rr.diffuse','rr.specular','rr.geometry','rr.distance')}
# RR consumes raw noisy lighting. Cull custom diffuse/specular/sun filters by
# removing their consumers; keep the independent cloud cache/composite intact.
($m.passes|Where-Object name -eq 'ray.direct').reads.Indirect='indirect.raw'
($m.passes|Where-Object name -eq 'ray.direct').reads.SunVisibility='sun.raw'
($m.passes|Where-Object name -eq 'rough.composite').reads.Reflection='rough.reflection.hdr'
Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../examples/ray-lighting/rr-guides.comp') -Destination (Join-Path $PackDirectory 'shaders/rr-guides.comp')
$m|ConvertTo-Json -Depth 32|Set-Content -LiteralPath $path
