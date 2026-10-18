#Requires -Version 7.0
param([Parameter(Mandatory)][string] $Destination)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$target = [IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $target) { throw 'Destination must be new' }
$archive = Join-Path $projectRoot 'src/client/resources/sulkan-bundled/Sulkan-Realistic.zip'
[IO.Compression.ZipFile]::ExtractToDirectory($archive, $target)
$manifestPath = Join-Path $target 'sulkan.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -AsHashtable
$manifest.name = 'Sulkan opaque ray visibility diagnostic'
$manifest.rayCoordinates = 'camera_relative'
$manifest.resources['ray.visibility'] = @{ format = 'RGBA16_FLOAT'; scale = 1.0 }
$manifest.resources['ray.display'] = @{ format = 'RGBA8_UNORM'; scale = 1.0 }
$manifest.passes += @(
    @{ name = 'ray.visibility'; compute = 'shaders/ray-visibility.comp'; rayScene = $true;
       localSize = @(8,8,1); reads = @{ Depth = '$depth' }; writes = @('ray.visibility') },
    @{ name = 'ray.present'; fragment = 'shaders/ray-present.fsh';
       reads = @{ Visibility = 'ray.visibility' }; writes = @('ray.display') }
)
$manifest.present = 'ray.display'
$manifest | ConvertTo-Json -Depth 32 | Set-Content -LiteralPath $manifestPath
Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-visibility/visibility.comp') -Destination (Join-Path $target 'shaders/ray-visibility.comp')
Copy-Item -LiteralPath (Join-Path $projectRoot 'examples/ray-visibility/present.fsh') -Destination (Join-Path $target 'shaders/ray-present.fsh')
Write-Output "Diagnostic pack created: $target"
