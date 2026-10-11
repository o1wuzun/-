$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$statePath=Join-Path $projectRoot 'data/public-processes.json'
if(!(Test-Path -LiteralPath $statePath)){Write-Output 'No public process record.';return}
$state=Get-Content -LiteralPath $statePath -Raw|ConvertFrom-Json
$allowed=@((Join-Path $projectRoot 'services/relay-service/target/relay-service-0.1.0-SNAPSHOT.jar'),(Join-Path $projectRoot 'services/tunnel-client/target/tunnel-client-0.1.0-SNAPSHOT.jar'),(Join-Path $projectRoot 'scripts/public-gateway.mjs'),(Join-Path $projectRoot 'data/tools/cloudflared.exe'))
foreach($entry in $state.entries){
 if($entry.artifact -notin $allowed){throw 'Unexpected artifact in public process record.'}
}
foreach($entry in $state.entries){
 $port=if($entry.port){[int]$entry.port}elseif($entry.artifact -like '*relay-service*'){8095}elseif($entry.artifact -like '*tunnel-client*'){@(8096,8097)}elseif($entry.artifact -like '*public-gateway*'){8098}else{19090}
 $ids=@([int]$entry.pid)+@(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -ExpandProperty OwningProcess)
 foreach($processId in ($ids|Select-Object -Unique)){
  $proc=Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
  if(!$proc){continue}
  if(!$proc.CommandLine -or !$proc.CommandLine.Contains($entry.artifact)){throw "Process $processId does not match this public service; refusing to stop it."}
  Stop-Process -Id $processId
 }
}
Remove-Item -LiteralPath $statePath
Write-Output 'Public tunnel, gateway and relay stopped. Community services remain running.'
