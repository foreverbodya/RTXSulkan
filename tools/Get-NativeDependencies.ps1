#Requires -Version 7.0
param([Parameter(Mandatory)][string] $ToolchainRoot, [Parameter(Mandatory)][string] $SdkRoot)
$ErrorActionPreference = 'Stop'
$roots = @{ toolchain = [IO.Path]::GetFullPath($ToolchainRoot); sdk = [IO.Path]::GetFullPath($SdkRoot) }
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'native-dependencies.lock.json') -Raw | ConvertFrom-Json
function Resolve-DependencyTarget([string] $root, [string] $relative) {
    $path = [IO.Path]::GetFullPath((Join-Path $root $relative))
    if (!$path.StartsWith($root.TrimEnd('\','/') + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Dependency target escapes its root: $relative"
    }
    return $path
}
foreach ($item in @($lock.packages) + @($lock.files)) {
    $root = $roots[$item.root]
    $path = Resolve-DependencyTarget $root $item.path
    New-Item -ItemType Directory -Path (Split-Path $path -Parent) -Force | Out-Null
    if (!(Test-Path -LiteralPath $path)) { Invoke-WebRequest -Uri $item.url -OutFile $path -TimeoutSec 180 }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $item.sha256) { throw "Native dependency hash mismatch: $($item.path)" }
    if ($item.PSObject.Properties.Name -contains 'extract') {
        $destination = Resolve-DependencyTarget $root $item.extract
        if (!(Test-Path -LiteralPath $destination)) { [IO.Compression.ZipFile]::ExtractToDirectory($path,$destination) }
    }
}
$resourceBin = Resolve-DependencyTarget $roots.toolchain 'msvc-resources-en-US/Contents/VC/Tools/MSVC/14.44.35207/bin/Hostx64/x64/1033'
$compilerBin = Resolve-DependencyTarget $roots.toolchain 'Microsoft.VC.14.44.17.14.Tools.HostX64.TargetX64.base/Contents/VC/Tools/MSVC/14.44.35207/bin/Hostx64/x64'
if (!(Test-Path -LiteralPath (Join-Path $compilerBin '1033'))) { Copy-Item -LiteralPath $resourceBin -Destination $compilerBin -Recurse }
if ((Get-AuthenticodeSignature -LiteralPath (Join-Path $compilerBin 'cl.exe')).Status -ne 'Valid') { throw 'Compiler signature invalid' }
Write-Output 'Pinned native dependencies verified. Files remain outside the project release artifacts.'
