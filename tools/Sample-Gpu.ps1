# Each completed query is appended with a closed file handle. Killing a long-lived
# nvidia-smi -f process lost its final buffered rows in earlier profiling runs.
param([Parameter(Mandatory)][string]$OutputPath,[Parameter(Mandatory)][string]$StopFile,[Parameter(Mandatory)][int]$ParentPid)
$ErrorActionPreference='Stop'
while(!(Test-Path -LiteralPath $StopFile) -and (Get-Process -Id $ParentPid -ErrorAction SilentlyContinue)){
    & nvidia-smi --query-gpu=timestamp,utilization.gpu,utilization.memory,memory.used,memory.total,power.draw,clocks.gr,clocks.mem --format=csv,noheader,nounits | Add-Content -LiteralPath $OutputPath -Encoding utf8
    Start-Sleep -Milliseconds 500
}
