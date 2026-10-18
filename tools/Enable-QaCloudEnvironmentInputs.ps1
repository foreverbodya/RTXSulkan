#Requires -Version 7.0
param([Parameter(Mandatory)][string]$PackDirectory)
$ErrorActionPreference='Stop'
$manifestPath=Join-Path $PackDirectory 'sulkan.json'
$manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -AsHashtable
$passes=@($manifest.passes|Where-Object{$_.name -eq 'cloud.environment'})
if($passes.Count -ne 1 -or @($passes[0].writes).Count -ne 1 -or !$passes[0].reads.ContainsKey('CloudExactNoise')){throw 'Expected environment-only volume comparison'}
$pass=$passes[0];$path=Join-Path $PackDirectory $pass.compute
$source=(Get-Content -LiteralPath $path -Raw).Replace("`r`n","`n")
$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($source)))
$call='cinematicCloudTransport(CloudLayout,ClearLighting,cinematicCloudOrigin(CloudLayout),direction,8000.0,64,proxy)'
if([regex]::Matches($source,[regex]::Escape($call)).Count -ne 1){throw 'Environment transport call changed'}
$source=$source.Replace($call,'cinematicCloudTransport(CloudLayout,ClearLighting,qaEnvironmentOrigin,direction,8000.0,64,proxy)')
$source=$source.Replace('    float blend=atmBlend();',"    vec3 qaEnvironmentOrigin=cinematicCloudOrigin(CloudLayout);`n    float blend=atmBlend();")
$end=$source.LastIndexOf('}')
$source=$source.Insert($end,"imageStore(Output1,p,vec4(direction,1));`nimageStore(Output2,p,vec4(qaEnvironmentOrigin,1));`nimageStore(Output3,p,transport);`nimageStore(Output4,p,vec4(clear,blend));`n")
$template=$manifest.resources[$pass.writes[0]]|ConvertTo-Json -Depth 12
foreach($name in @('direction','origin','transport','clear')) {
    $resource='qa.cloud.environment.'+$name;$definition=$template|ConvertFrom-Json -AsHashtable
    $definition.format='RGBA32_FLOAT';$definition.history=$false
    $manifest.resources[$resource]=$definition;$pass.writes+=@($resource)
}
Set-Content -LiteralPath $path -Value $source -NoNewline
$manifest|ConvertTo-Json -Depth 30|Set-Content -LiteralPath $manifestPath
@{sourceEnvironmentSha256=$hash;scope='Disposable A/A/B environment inputs and transport witness. Added outputs can affect compilation; this is not production performance or image acceptance. Existing strict output mismatch guard is intentionally retained.'}|ConvertTo-Json|Set-Content (Join-Path $PackDirectory 'qa-cloud-environment-inputs.json')
