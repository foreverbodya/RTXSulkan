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
    $manifest.options['REALTIME_RAY_LOD']=@{default=1;values=@(0,1)}
    $manifest.options['VEGETATION_WIND'].default=0
    foreach($pass in $manifest.passes|Where-Object{$_.name -eq 'indirect.trace'}){$pass.reads['AtmosphereSky']='atmosphere.sky'}
}
if($Step -ge 5){
    # Cloud transport belongs to the view layer. Every lighting/environment miss
    # reads the ready clear-atmosphere LUT, rather than launching another cloud
    # globe integration to reconstruct indirect sky illumination.
    foreach($pass in $manifest.passes){
        foreach($key in @($pass.reads.Keys)){
            if($pass.reads[$key] -eq 'cloud.environment'){$pass.reads[$key]='atmosphere.sky'}
            if($pass.reads[$key] -eq 'cloud.lighting'){$pass.reads[$key]='atmosphere.lighting'}
        }
    }
    $manifest.options['REALTIME_ATMOSPHERE_CACHE']=@{default=1;values=@(1)}
    ($manifest.passes|Where-Object{$_.name -eq 'atmosphere.multiple'}).reads['PreviousMultiple']='atmosphere.multiple@previous'
    $manifest.options['REALTIME_CLOUDS']=@{default=1;values=@(1)}
    $manifest.resources['cloud.clear.base']=@{format='RGBA16_FLOAT'}
    ($manifest.passes|Where-Object{$_.name -eq 'cloud.composite'}).writes+=@('cloud.clear.base')
    ($manifest.passes|Where-Object{$_.name -eq 'distant.world'}).reads['CloudClearBase']='cloud.clear.base'
    foreach($pass in $manifest.passes|Where-Object{$_.name -in @('display.meter','display.histogram')}){$pass.reads['CloudMeter']='cloud.compose.coefficients'}
    $manifest.reconstruction.displayReads['CloudDisplay']='cloud.compose.coefficients'
    $manifest.reconstruction.displayReads['CloudDisplayDepth']='reconstruction.depth'
    $manifest.resources['cloud.art.noise']=@{format='RGBA8_UNORM';size=@(130,130,130)}
    $manifest.passes+=@{name='cloud.art.noise';compute='shaders/cloud-lighting-noise.comp';localSize=@(8,8,8);reads=@{CloudNoise='$texture/cloud-cellular'};writes=@('cloud.art.noise')}
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot '../examples/ray-lighting/cloud-lighting-noise.comp') -Destination (Join-Path $PackDirectory 'shaders/cloud-lighting-noise.comp')
    foreach($pass in $manifest.passes|Where-Object{$_.reads.ContainsKey('CloudLayout') -and $_.reads.ContainsKey('CloudNoise')}){
        $pass.reads['CloudLightingNoise']='cloud.art.noise';$pass.linearReads=@(@($pass.linearReads)+@('CloudLightingNoise')|Where-Object{$_})
    }
    $manifest.options['CLOUD_LIGHTING_VOLUME']=@{default=1;values=@(1)}
    $manifest.resources['cloud.source.light']=@{format='RGBA16_FLOAT';size=@(96,32,96);history=$true}
    $manifest.resources['cloud.source.ambient']=@{format='RG16_FLOAT';size=@(96,32,96);history=$true}
    $manifest.resources['cloud.source.state']=@{format='RGBA32_FLOAT';size=@(1,1,1);history=$true}
    $manifest.passes+=@{name='cloud.source.state';compute='shaders/realtime-cloud-state.comp';localSize=@(1,1,1);reads=@{};writes=@('cloud.source.state')}
    foreach($pass in $manifest.passes|Where-Object{$_.name -in @('cloud.view','cloud.environment','cloud.composite')}){
        $pass.reads['CloudLightingVolume']='cloud.source.light';$pass.reads['CloudAmbientVolume']='cloud.source.ambient'
        $pass.linearReads=@(@($pass.linearReads)+@('CloudLightingVolume','CloudAmbientVolume')|Where-Object{$_})
    }
    $manifest.passes+=@{name='cloud.source.light';compute='shaders/realtime-cloud-light.comp';localSize=@(8,4,8);linearReads=@('CloudLightingNoise');
        reads=@{CloudLayout='$texture/vanilla-clouds';CloudNoise='$texture/cloud-cellular';CloudLightingNoise='cloud.art.noise';State='cloud.source.state';PreviousLight='cloud.source.light@previous';PreviousAmbient='cloud.source.ambient@previous';PreviousState='cloud.source.state@previous'};writes=@('cloud.source.light','cloud.source.ambient')}
    # Explicit quarter width/height: 427x240 for DLSS Quality input 1707x960.
    # Only the cloud layer uses this scale; world reconstruction stays 1440p.
    foreach($name in @('cloud.view','cloud.accum')){$manifest.resources[$name]=@{format='RGBA16_FLOAT';scale=0.25;history=$true}}
    $manifest.resources['cloud.geometry'].scale=0.25;$manifest.resources['cloud.geometry'].history=$true
    $manifest.resources['cloud.environment'].size=@(128,64,1)
    $environmentPass=$manifest.passes|Where-Object{$_.name -eq 'cloud.environment'}
    $environmentPass.reads['PreviousSky']='cloud.environment@previous'
    $environmentPass.reads['PreviousCloudState']='cloud.source.state@previous'
    $manifest.passes+=@{name='cloud.temporal';compute='shaders/realtime-cloud-temporal.comp';localSize=@(8,8,1);linearReads=@('Previous');
        reads=@{Current='cloud.view';Geometry='cloud.geometry';Previous='cloud.accum@previous';PreviousGeometry='cloud.geometry@previous'};writes=@('cloud.accum')}
    ($manifest.passes|Where-Object{$_.name -eq 'cloud.composite'}).reads.CloudView='cloud.accum'
    foreach($name in @('realtime-cloud-light.comp','realtime-cloud-temporal.comp','realtime-cloud-state.comp')){
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot "../examples/ray-lighting/$name") -Destination (Join-Path $PackDirectory "shaders/$name")
    }
}
$manifest|ConvertTo-Json -Depth 32|Set-Content -LiteralPath $manifestPath
