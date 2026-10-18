#Requires -Version 7.0
param(
    [Parameter(Mandatory)][string] $ToolchainRoot,
    [Parameter(Mandatory)][string] $SdkRoot,
    [string] $JavaRoot = (Join-Path $env:APPDATA 'PrismLauncher/java/java-runtime-epsilon'),
    [string] $JniRoot
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$ToolchainRoot = (Resolve-Path -LiteralPath $ToolchainRoot).Path
$SdkRoot = (Resolve-Path -LiteralPath $SdkRoot).Path
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'native-dependencies.lock.json') -Raw | ConvertFrom-Json
foreach ($item in $lock.files) {
    if ((Get-FileHash -LiteralPath (Join-Path $SdkRoot $item.path)).Hash -ne $item.sha256) { throw "SDK/JNI dependency differs from lock: $($item.path)" }
}
if (!$JniRoot) { $JniRoot = Join-Path $JavaRoot 'include' }
if (!(Test-Path -LiteralPath (Join-Path $JniRoot 'jni.h'))) { throw 'JNI headers are missing; supply a full JDK or explicit JniRoot' }
$JniRoot = (Resolve-Path -LiteralPath $JniRoot).Path
$compilerRoot = Join-Path $ToolchainRoot 'Microsoft.VC.14.44.17.14.Tools.HostX64.TargetX64.base/Contents/VC/Tools/MSVC/14.44.35207/bin/Hostx64/x64'
$compiler = Join-Path $compilerRoot 'cl.exe'
if ((Get-AuthenticodeSignature -LiteralPath $compiler).Status -ne 'Valid') { throw 'Compiler signature is not valid' }
$headers = Join-Path $ToolchainRoot 'Microsoft.VC.14.44.17.14.CRT.Headers.base/Contents/VC/Tools/MSVC/14.44.35207/include'
$crt = Join-Path $ToolchainRoot 'Microsoft.VC.14.44.17.14.CRT.x64.Desktop.base/Contents/VC/Tools/MSVC/14.44.35207/lib/x64'
$crtCommon = Join-Path $ToolchainRoot 'Microsoft.VC.14.44.17.14.CRT.x64.OneCore.Desktop.base/Contents/VC/Tools/MSVC/14.44.35207/lib/onecore/x64'
$windowsHeaders = Join-Path $ToolchainRoot 'microsoft.windows.sdk.cpp/c/Include/10.0.26100.0'
$windowsLib = Join-Path $ToolchainRoot 'microsoft.windows.sdk.cpp.x64/c'
$vulkanHeaders = (Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'vulkan-headers') -Directory | Select-Object -First 1).FullName
$output = Join-Path $projectRoot 'build/native/dlss'
New-Item -ItemType Directory -Path $output -Force | Out-Null
$previousPath = $env:PATH
$previousInclude = $env:INCLUDE
$previousLib = $env:LIB
try {
    $env:PATH = $compilerRoot + ';' + $previousPath
    $env:INCLUDE = @($headers, (Join-Path $windowsHeaders 'ucrt'), (Join-Path $windowsHeaders 'um'), (Join-Path $windowsHeaders 'shared')) -join ';'
    $env:LIB = @($crt, $crtCommon, (Join-Path $windowsLib 'um/x64'), (Join-Path $windowsLib 'ucrt/x64')) -join ';'
    $arguments = @('/nologo','/LD','/MT','/std:c++17','/EHsc','/O2','/W4','/WX','/Brepro',
        ('/I' + $JniRoot), ('/I' + (Join-Path $JniRoot 'win32')),
        ('/I' + (Join-Path $vulkanHeaders 'include')), ('/I' + $SdkRoot),
        ('/Fo' + $output.Replace('\','/') + '/'), (Join-Path $projectRoot 'native/dlss/ngx_probe.cpp'),
        (Join-Path $projectRoot 'native/dlss/ngx_session.cpp'),
        '/link','/Brepro','/INCREMENTAL:NO',('/OUT:' + (Join-Path $output 'sulkan_ngx_probe.dll')),
        ('/IMPLIB:' + (Join-Path $output 'sulkan_ngx_probe.lib')),
        (Join-Path $SdkRoot 'lib/Windows_x86_64/x64/nvsdk_ngx_s.lib'),
        'advapi32.lib','shell32.lib','ole32.lib','user32.lib')
    & $compiler @arguments 2>&1 | Tee-Object -FilePath (Join-Path $output 'build.txt')
    if ($LASTEXITCODE -ne 0) { throw "NGX probe compiler/linker failed: $LASTEXITCODE" }
    Get-FileHash -LiteralPath (Join-Path $output 'sulkan_ngx_probe.dll') -Algorithm SHA256
} finally {
    $env:PATH = $previousPath
    $env:INCLUDE = $previousInclude
    $env:LIB = $previousLib
}
