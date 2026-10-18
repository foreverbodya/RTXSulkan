#Requires -Version 7.0
param([Parameter(Mandatory)][string]$PackDirectory,[ValidateSet(1,2,3)][int]$Step=1)
$ErrorActionPreference='Stop'
$manifestPath=Join-Path $PackDirectory 'sulkan.json'
$manifest=Get-Content -LiteralPath $manifestPath -Raw|ConvertFrom-Json -AsHashtable
$manifest.name="Sulkan Realtime — step $Step"
if($Step -ge 2){
    $manifest.options['REALTIME_DIRECT_SUN']=@{default=1;values=@(1)}
    $manifest.options['SULKAN_SOLID_SHADOW_PREFLIGHT']=@{default=0;values=@(0)}
    foreach($name in @('sun.raw','sun.accum','sun.normal')){$manifest.resources[$name]=@{format='RGBA16_FLOAT';history=$true}}
    $manifest.resources['sun.position']=@{format='RGBA32_FLOAT';history=$true}
    $manifest.passes+=@{name='sun.trace';compute='shaders/realtime-sun.comp';rayScene=$true;rayAttributes=$true;localSize=@(8,8,1);
        reads=@{Depth='$depth';Surface='surface.data';NormalRoughness='gbuffer.normal';Material='gbuffer.material';Atlas='$terrainAtlas'};writes=@('sun.raw')}
    $temporal=($manifest.passes|Where-Object{$_.name -eq 'indirect.temporal'})|ConvertTo-Json -Depth 20|ConvertFrom-Json -AsHashtable
    if(!$temporal){throw 'Realtime sun accumulation requires temporal guide/state passes'}
    $temporal.name='sun.temporal';$temporal.reads.Sample='sun.raw';$temporal.reads.PreviousLighting='sun.accum@previous'
    $temporal.reads.PreviousPosition='sun.position@previous';$temporal.reads.PreviousNormal='sun.normal@previous'
    $temporal.writes=@('sun.accum','sun.position','sun.normal');$manifest.passes+=@($temporal)
    ($manifest.passes|Where-Object{$_.name -eq 'ray.direct'}).reads['SunVisibility']='sun.accum'
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../examples/ray-lighting/realtime-sun.comp') -Destination (Join-Path $PackDirectory 'shaders/realtime-sun.comp')
}
if($Step -ge 3){
    $manifest.options['REALTIME_DIFFUSE']=@{default=1;values=@(1)}
    foreach($pass in $manifest.passes|Where-Object{$_.name -eq 'indirect.trace'}){$pass.reads['AtmosphereSky']='atmosphere.sky'}
}
$manifest|ConvertTo-Json -Depth 32|Set-Content -LiteralPath $manifestPath
