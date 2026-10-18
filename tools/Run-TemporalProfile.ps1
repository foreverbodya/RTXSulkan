#Requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$QaDirectory,
    [string]$InstanceMods = 'work/qa-world-dlss-5/mods',
    [ValidateSet('static','motion')][string]$Sequence = 'motion',
    [ValidateSet(3000,12600,18000)][int]$ProfileTime = 12600,
    [ValidateRange(2,32)][int]$RenderDistance = 6,
    [ValidateRange(180,3600)][int]$TimeoutSeconds = 3600
)
$ErrorActionPreference='Stop'
# This is independent-history image QA. The shared runner supplies the same
# full-quality options, muted audio, mod hashes and disposable-world safeguards.
# The Java controller replaces timing phases with sequential A/A/B/A captures.
& (Join-Path $PSScriptRoot 'Run-PerformanceProfile.ps1') `
    -QaDirectory $QaDirectory -InstanceMods $InstanceMods -TemporalProfile `
    -ComputeExperiment cloud-3d-env -ProfilePlan $Sequence -ProfileTime $ProfileTime `
    -RenderDistance $RenderDistance -TimeoutSeconds $TimeoutSeconds
