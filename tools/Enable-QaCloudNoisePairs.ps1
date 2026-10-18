#Requires -Version 7.0
param([Parameter(Mandatory)][string]$PackDirectory)
$ErrorActionPreference='Stop'
$manifestPath=Join-Path $PackDirectory 'sulkan.json'
$manifest=Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -AsHashtable
$pass=@($manifest.passes | Where-Object {$_.name -eq 'cloud.environment'})
if($pass.Count -ne 1 -or !$pass[0].reads.ContainsKey('CloudExactNoise') -or @($pass[0].writes).Count -ne 1){throw 'Cloud noise pairs require one environment-only 3D cloud pass'}
$pass=$pass[0]
$cellPath=Join-Path $PackDirectory 'shaders/cloud-cellular.glsl'
$source=(Get-Content -LiteralPath $cellPath -Raw).Replace("`r`n","`n")
$hash=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($source)))
$pattern='(?s)#if defined\(SULKAN_CLOUD_NOISE_EXACT_3D\) && SULKAN_CLOUD_NOISE_EXACT_3D == 1\n(.*?)#else\n(.*?)#endif'
$matches=[regex]::Matches($source,$pattern)
if($matches.Count -ne 1){throw 'Cloud cellular branch changed'}
$match=$matches[0]
$paired="precise vec4 qaReference=vec4(0),qaCandidate=vec4(0);`n"+$match.Groups[2].Value.Replace('result+=','qaReference+=')+$match.Groups[1].Value.Replace('result+=','qaCandidate+=')+@'
    vec4 qaDifference=abs(qaReference-qaCandidate);
    float qaMaximum=max(max(qaDifference.x,qaDifference.y),max(qaDifference.z,qaDifference.w));
    if(qaMaximum>qaCloudNoiseMetrics.x) {
        qaCloudNoisePoint=vec4(p,scale);qaCloudNoiseReference=qaReference;qaCloudNoiseCandidate=qaCandidate;
    }
    qaCloudNoiseMetrics.x=max(qaCloudNoiseMetrics.x,qaMaximum);
    qaCloudNoiseMetrics.y+=dot(qaDifference,vec4(1));
    qaCloudNoiseMetrics.z+=1.0;
    qaCloudNoiseMetrics.w+=any(notEqual(qaReference,qaCandidate))?1.0:0.0;
    result=qaReference;
'@
$source=$source.Replace($match.Value,$paired)
$globals="vec4 qaCloudNoiseMetrics=vec4(0),qaCloudNoisePoint=vec4(0),qaCloudNoiseReference=vec4(0),qaCloudNoiseCandidate=vec4(0);`n"
$source=$source.Replace('#define SULKAN_CINEMATIC_CLOUD_CELLULAR',"#define SULKAN_CINEMATIC_CLOUD_CELLULAR`n$globals")
$environmentPath=Join-Path $PackDirectory $pass.compute
$environment=(Get-Content -LiteralPath $environmentPath -Raw).Replace("`r`n","`n")
$end=$environment.LastIndexOf('}')
if($end -lt 0){throw 'Environment entry point changed'}
$environment=$environment.Insert($end,"imageStore(Output1,p,qaCloudNoiseMetrics);`nimageStore(Output2,p,qaCloudNoisePoint);`nimageStore(Output3,p,qaCloudNoiseReference);`nimageStore(Output4,p,qaCloudNoiseCandidate);`n")
$template=$manifest.resources[$pass.writes[0]] | ConvertTo-Json -Depth 12
foreach($name in @('metrics','point','reference','candidate')) {
    $resource='qa.cloud.noise.'+$name
    $definition=$template | ConvertFrom-Json -AsHashtable
    $definition.format='RGBA32_FLOAT';$definition.history=$false
    $manifest.resources[$resource]=$definition;$pass.writes+=@($resource)
}
$manifest.options['SULKAN_QA_CLOUD_NOISE_PAIRS']=@{default=1;values=@(1)}
Set-Content -LiteralPath $cellPath -Value $source -NoNewline
Set-Content -LiteralPath $environmentPath -Value $environment -NoNewline
$manifest | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $manifestPath
@{sourceCellularSha256=$hash;scope='Disposable paired sampler instrumentation only. Both eight-fetch results receive the same actual cloud-march coordinate and weights. Radiance follows the atlas reference. No performance claim.'} | ConvertTo-Json | Set-Content (Join-Path $PackDirectory 'qa-cloud-noise-pairs.json')
