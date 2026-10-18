#Requires -Version 7.0
param([Parameter(Mandatory)][string]$PackDirectory,[ValidateSet(1,2,3,5)][int]$Step=1)
$ErrorActionPreference='Stop'
$manifestPath=Join-Path $PackDirectory 'sulkan.json'
$manifest=Get-Content -LiteralPath $manifestPath -Raw|ConvertFrom-Json -AsHashtable
$manifest.name="Sulkan Realtime — step $Step"
if($Step -ge 2){
    $manifest.options['EMISSIVE_SAMPLING']=@{default=1;values=@(1)}
    $manifest.options['REALTIME_EMITTER_TREE']=@{default=1;values=@(1)}
    $manifest.materials['minecraft:lantern']=15
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
if($Step -ge 5){
    $manifest.options['REALTIME_CLOUDS']=@{default=1;values=@(1)}
    $manifest.options['CLOUD_LIGHTING_VOLUME']=@{default=1;values=@(1)}
    $manifest.resources['cloud.source.light']=@{format='RGBA16_FLOAT';size=@(96,32,96)}
    $manifest.resources['cloud.source.ambient']=@{format='RG16_FLOAT';size=@(96,32,96)}
    foreach($pass in $manifest.passes|Where-Object{$_.name -in @('cloud.view','cloud.environment','cloud.composite')}){
        $pass.reads['CloudLightingVolume']='cloud.source.light';$pass.reads['CloudAmbientVolume']='cloud.source.ambient'
        $pass.linearReads=@(@($pass.linearReads)+@('CloudLightingVolume','CloudAmbientVolume')|Where-Object{$_})
    }
    $manifest.passes+=@{name='cloud.source.light';compute='shaders/realtime-cloud-light.comp';localSize=@(8,4,8);
        reads=@{CloudLayout='$texture/vanilla-clouds';CloudNoise='$texture/cloud-cellular'};writes=@('cloud.source.light','cloud.source.ambient')}
    # Half width/height means one quarter of input pixels (854x480 at DLSS
    # Quality input 1707x960). The reconstruction itself remains full output.
    foreach($name in @('cloud.view','cloud.accum')){$manifest.resources[$name]=@{format='RGBA16_FLOAT';scale=0.5;history=$true}}
    $manifest.resources['cloud.geometry'].scale=0.5;$manifest.resources['cloud.geometry'].history=$true
    $manifest.resources['cloud.environment'].size=@(128,64,1)
    $manifest.passes+=@{name='cloud.temporal';compute='shaders/realtime-cloud-temporal.comp';localSize=@(8,8,1);
        reads=@{Current='cloud.view';Geometry='cloud.geometry';Previous='cloud.accum@previous';PreviousGeometry='cloud.geometry@previous'};writes=@('cloud.accum')}
    ($manifest.passes|Where-Object{$_.name -eq 'cloud.composite'}).reads.CloudView='cloud.accum'
    foreach($name in @('realtime-cloud-light.comp','realtime-cloud-temporal.comp')){
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot "../examples/ray-lighting/$name") -Destination (Join-Path $PackDirectory "shaders/$name")
    }
}
$manifest|ConvertTo-Json -Depth 32|Set-Content -LiteralPath $manifestPath
