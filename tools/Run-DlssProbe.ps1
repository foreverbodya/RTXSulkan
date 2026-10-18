#Requires -Version 7.0
param(
    [Parameter(Mandatory)][string] $InstanceMods,
    [Parameter(Mandatory)][string] $QaDirectory,
    [Parameter(Mandatory)][string] $RuntimeDirectory,
    [string] $NativeLibrary,
    [switch] $EnableDlss,
    [switch] $RayTracing,
    [switch] $FrameEvaluation,
    [string] $PrismRoot = (Join-Path $env:APPDATA 'PrismLauncher')
)
$ErrorActionPreference = 'Stop'
if ($FrameEvaluation -and !$EnableDlss) { throw 'FrameEvaluation requires EnableDlss' }
$projectRoot = Split-Path $PSScriptRoot -Parent
if (!$NativeLibrary) { $NativeLibrary = Join-Path $projectRoot 'build/native/dlss/sulkan_ngx_probe.dll' }
$NativeLibrary = (Resolve-Path -LiteralPath $NativeLibrary).Path
$RuntimeDirectory = (Resolve-Path -LiteralPath $RuntimeDirectory).Path
$runtimeFile = Join-Path $RuntimeDirectory 'nvngx_dlss.dll'
if (!(Test-Path -LiteralPath $runtimeFile)) { throw 'DLSS SDK runtime missing' }
$qaPath = [IO.Path]::GetFullPath($QaDirectory)
if (Test-Path -LiteralPath $qaPath) { throw 'Diagnostic directory must be new' }
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'libraries.lock.json') -Raw | ConvertFrom-Json
$classpath = foreach ($library in $lock.libraries) {
    $path = Join-Path $PrismRoot ('libraries/' + $library.path)
    if ((Get-FileHash -LiteralPath $path).Hash -ne $library.sha256) { throw "Runtime dependency hash mismatch: $($library.path)" }
    $path
}
$mods = @{
    'fabric-api-0.162.0+26.3.jar' = '8626c189b702f466800b1c8ddac6203cf8a1e0bdaee75a5f9a33b18c2baa81b1'
    'sodium-fabric-0.9.2+mc26.3.jar' = '87a5bb39f7e6e41106e77f122f82fc93f9d3bc0b6ebcb746b314172a446a287a'
    'voicechat-fabric-2.6.24+26.3.jar' = '97074fa1d094a22f17d5ac11282176965a45137950b66627d3813618810c3d00'
}
foreach ($mod in $mods.GetEnumerator()) {
    if ((Get-FileHash -LiteralPath (Join-Path $InstanceMods $mod.Key)).Hash -ne $mod.Value) { throw "Mod hash mismatch: $($mod.Key)" }
}
New-Item -ItemType Directory -Path (Join-Path $qaPath 'mods') | Out-Null
Set-Content -LiteralPath (Join-Path $qaPath 'SULKAN_QA_DISPOSABLE') -Value 'sulkan-cinematic-disposable-v1' -NoNewline
foreach ($name in $mods.Keys) { Copy-Item -LiteralPath (Join-Path $InstanceMods $name) -Destination (Join-Path $qaPath 'mods') }
$artifact = Join-Path $projectRoot 'build/libs/sulkan-0.5.1-cinematic.0-26.3.jar'
if ($FrameEvaluation) {
    $jar = [IO.Compression.ZipFile]::OpenRead($artifact)
    try {
        if (!$jar.GetEntry('com/sulkan/shaders/diagnostics/DlssFrameConformance.class')) { throw 'Artifact lacks the frame diagnostic; build the current source successfully first' }
    } finally { $jar.Dispose() }
}
$copiedArtifact = Join-Path $qaPath 'mods/sulkan-0.5.1-cinematic.0-26.3.jar'
Copy-Item -LiteralPath $artifact -Destination $copiedArtifact
$artifactHash = (Get-FileHash -LiteralPath $copiedArtifact).Hash
$nativeHash = (Get-FileHash -LiteralPath $NativeLibrary).Hash
$runtimeHash = (Get-FileHash -LiteralPath $runtimeFile).Hash
@('preferredGraphicsBackend:vulkan','maxFps:60','pauseOnLostFocus:false','soundCategory_master:0.0') | Set-Content -LiteralPath (Join-Path $qaPath 'options.txt')
$start = [Diagnostics.ProcessStartInfo]::new((Join-Path $PrismRoot 'java/java-runtime-epsilon/bin/java.exe'))
$start.WorkingDirectory = $qaPath
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$arguments = @('--enable-native-access=ALL-UNNAMED', ('-Dsulkan.dlssProbeDirectory=' + $qaPath),
    ('-Dsulkan.dlssProbeLibrary=' + $NativeLibrary), ('-Dsulkan.dlssProbeRuntime=' + $RuntimeDirectory),
    '-Xmx2G','-XX:StackShadowPages=32','-classpath',($classpath -join ';'),
    'net.fabricmc.loader.impl.launch.knot.KnotClient','--username','SulkanQA','--version','26.3','--gameDir',$qaPath,
    '--assetsDir',(Join-Path $PrismRoot 'assets'),'--assetIndex','34','--accessToken','0',
    '--uuid','00000000000000000000000000000001','--width','854','--height','480')
foreach ($argument in $arguments) { $start.ArgumentList.Add($argument) }
if ($EnableDlss) { $start.ArgumentList.Insert(0, '-Dsulkan.dlss=true') }
if ($RayTracing) { $start.ArgumentList.Insert(0, '-Dsulkan.rayTracing=true') }
if ($FrameEvaluation) { $start.ArgumentList.Insert(0, '-Dsulkan.dlssFrameProbe=true') }
$process = [Diagnostics.Process]::Start($start)
@{ pid = $process.Id; artifactSha256 = $artifactHash; nativeSha256 = $nativeHash; runtimeSha256 = $runtimeHash } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $qaPath 'launch.json')
$output = $process.StandardOutput.ReadToEndAsync()
$errors = $process.StandardError.ReadToEndAsync()
$expired = !$process.WaitForExit(180000)
if ($expired) { $process.Kill($true); $process.WaitForExit() }
$stdout = $output.GetAwaiter().GetResult()
$stderr = $errors.GetAwaiter().GetResult()
Set-Content -LiteralPath (Join-Path $qaPath 'stdout.txt') -Value $stdout
Set-Content -LiteralPath (Join-Path $qaPath 'stderr.txt') -Value $stderr
$unchanged = (Get-FileHash -LiteralPath $copiedArtifact).Hash -eq $artifactHash
$unchanged = $unchanged -and (Get-FileHash -LiteralPath $NativeLibrary).Hash -eq $nativeHash -and (Get-FileHash -LiteralPath $runtimeFile).Hash -eq $runtimeHash
foreach ($mod in $mods.GetEnumerator()) {
    if ((Get-FileHash -LiteralPath (Join-Path $qaPath ('mods/' + $mod.Key))).Hash -ne $mod.Value) { $unchanged = $false }
}
$passed = !$expired -and $process.ExitCode -eq 0 -and $unchanged -and
    $stdout.Contains('SULKAN_DLSS_CAPABILITY_PASS') -and !$stdout.Contains('SULKAN_DLSS_CAPABILITY_FAIL')
if ($RayTracing) { $passed = $passed -and $stdout.Contains('SULKAN_RT_DEVICE_NEGOTIATION requested=true enabled=true extensionsCallable=true') }
if ($FrameEvaluation) { $passed = $passed -and $stdout.Contains('SULKAN_DLSS_FRAME_PASS') -and !$stdout.Contains('SULKAN_DLSS_FRAME_FAIL') }
@{ passed = $passed; exitCode = $process.ExitCode; timedOut = $expired; pid = $process.Id;
    artifactSha256 = $artifactHash; nativeSha256 = $nativeHash; runtimeSha256 = $runtimeHash;
    runtimeModsUnchanged = $unchanged; frameEvaluation = [bool]$FrameEvaluation;
    scope = $(if ($FrameEvaluation) { 'Actual constant HDR GPU frame evaluations and retirement; not gameplay quality, motion, jitter or performance' } else { 'NGX initialization/capabilities only; no frame evaluation or performance claim' }) } |
    ConvertTo-Json | Set-Content -LiteralPath (Join-Path $qaPath 'result.json')
$process.Dispose()
if (!$passed) { throw "DLSS probe failed; inspect $qaPath" }
Write-Output $(if ($FrameEvaluation) { "DLSS GPU frame probe passed: $qaPath" } else { "DLSS capability probe passed: $qaPath" })
