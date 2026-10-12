$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$statePath=Join-Path $projectRoot 'data/dev-processes.json'
if(!(Test-Path -LiteralPath $statePath)){Write-Output 'No development process record.';return}
$state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
foreach($entry in $state.PSObject.Properties){
    $processId=[int]$entry.Value
    $proc=Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
    if(!$proc){continue}
    $expected=if($entry.Name -eq 'web'){Join-Path $projectRoot 'frontend/node_modules/vite/bin/vite.js'}elseif($entry.Name -eq 'identity'){Join-Path $projectRoot 'services/identity-service/target/identity-service-0.1.0-SNAPSHOT.jar'}else{Join-Path $projectRoot 'services/group-service/target/group-service-0.1.0-SNAPSHOT.jar'}
    if($entry.Name -eq 'web' -and $env:COMMUNITY_DESKTOP){$expected=Join-Path $projectRoot 'desktop/web-server.mjs'}
    if(!$proc.CommandLine){throw "Cannot read process $processId ownership. Run stop-dev.ps1 in the terminal that started the services."}
    if(!$proc.CommandLine.Contains($expected)){throw "Process $processId no longer matches the recorded project service. Refusing to stop it."}
    Stop-Process -Id $processId
    Write-Output "Stopped $($entry.Name) process $processId."
}
# Some Windows Java launchers create a child JVM. Verify exact project artifact paths
# before stopping those children; never kill unrelated java/node processes.
$artifacts=@((Join-Path $projectRoot 'services/identity-service/target/identity-service-0.1.0-SNAPSHOT.jar'),(Join-Path $projectRoot 'services/group-service/target/group-service-0.1.0-SNAPSHOT.jar'),(Join-Path $projectRoot 'frontend/node_modules/vite/bin/vite.js'))
if($env:COMMUNITY_DESKTOP){$artifacts+=(Join-Path $projectRoot 'desktop/web-server.mjs')}
foreach($proc in (Get-CimInstance Win32_Process)){
    if(!$proc.CommandLine -or $proc.Name -notin @('java.exe','node.exe')){continue}
    foreach($artifact in $artifacts){
        if($proc.CommandLine.Contains($artifact)){
            Stop-Process -Id $proc.ProcessId -ErrorAction SilentlyContinue
            Write-Output "Stopped verified project child process $($proc.ProcessId)."
            break
        }
    }
}
