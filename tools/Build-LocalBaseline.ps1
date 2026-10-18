param(
    [Parameter(Mandatory = $true)][string]$InstanceMods,
    [string]$PrismRoot = (Join-Path $env:APPDATA 'PrismLauncher'),
    [string]$JavaRoot = (Join-Path $env:APPDATA 'PrismLauncher/java/java-runtime-epsilon')
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$buildRoot = Join-Path $projectRoot 'build/local-baseline'
if (Test-Path -LiteralPath $buildRoot) {
    throw "Build directory already exists: $buildRoot. Archive it before a fresh baseline build."
}
$classesRoot = Join-Path $buildRoot 'classes'
$depsRoot = Join-Path $buildRoot 'dependencies'
New-Item -ItemType Directory -Force $classesRoot,$depsRoot | Out-Null
$lock = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'libraries.lock.json') -Raw | ConvertFrom-Json
$classPath = [System.Collections.Generic.List[string]]::new()
function Add-VerifiedDependency([string]$dependencyPath, [string]$expectedHash) {
    if (-not (Test-Path -LiteralPath $dependencyPath)) { throw "Missing dependency: $dependencyPath" }
    $actualHash = (Get-FileHash -LiteralPath $dependencyPath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $expectedHash) { throw "Dependency hash mismatch: $dependencyPath" }
    $classPath.Add($dependencyPath)
}
foreach ($library in $lock.libraries) {
    Add-VerifiedDependency (Join-Path (Join-Path $PrismRoot 'libraries') $library.path) $library.sha256
}
foreach ($mod in $lock.mods) {
    Add-VerifiedDependency (Join-Path $InstanceMods $mod.path) $mod.sha256
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($containerPath in @(
    (Join-Path $InstanceMods 'fabric-api-0.162.0+26.3.jar'),
    (Join-Path $PrismRoot 'libraries/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar')
)) {
    $container = [System.IO.Compression.ZipFile]::OpenRead($containerPath)
    try {
        foreach ($entry in $container.Entries) {
            if ($entry.FullName -match '^META-INF/jars/[^/]+\.jar$') {
                $destination = Join-Path $depsRoot $entry.Name
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry,$destination,$false)
                $classPath.Add($destination)
            }
        }
    } finally { $container.Dispose() }
}
$compilerArgs = @('--release','25','-proc:none','-classpath',('"' + (($classPath -join ';').Replace('\','/')) + '"'),'-d',('"' + $classesRoot.Replace('\','/') + '"'))
$compilerArgs += Get-ChildItem -LiteralPath (Join-Path $projectRoot 'src/client/java') -Recurse -Filter '*.java' |
    ForEach-Object { '"' + $_.FullName.Replace('\','/') + '"' }
$argsFile = Join-Path $buildRoot 'javac.args'
[System.IO.File]::WriteAllLines($argsFile,$compilerArgs,[System.Text.UTF8Encoding]::new($false))
& (Join-Path $JavaRoot 'bin/javac.exe') ('@' + $argsFile)
if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }
Copy-Item -Path (Join-Path $projectRoot 'src/client/resources/*') -Destination $classesRoot -Recurse
$manifest = Get-Content -LiteralPath (Join-Path $projectRoot 'src/main/resources/fabric.mod.json') -Raw
$manifest = $manifest.Replace('${version}','0.5.1-cinematic.0')
[System.IO.File]::WriteAllText((Join-Path $classesRoot 'fabric.mod.json'),$manifest,[System.Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath (Join-Path $projectRoot 'LICENSE') -Destination (Join-Path $classesRoot 'LICENSE_sulkan')
$artifact = Join-Path $buildRoot 'sulkan-0.5.1-cinematic.0-26.3.jar'
& (Join-Path $JavaRoot 'bin/jar.exe') '--create' '--date=2026-09-24T00:00:00Z' '--file' $artifact '-C' $classesRoot '.'
if ($LASTEXITCODE -ne 0) { throw "jar failed with exit code $LASTEXITCODE" }
Write-Output "LOCAL BASELINE ONLY; runtime validation pending: $artifact"
Get-FileHash -LiteralPath $artifact -Algorithm SHA256
