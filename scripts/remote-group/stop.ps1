$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath $PSScriptRoot).Path
$statePath=Join-Path $root 'data/processes.json'
if(!(Test-Path -LiteralPath $statePath)){return}
$state=Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
foreach($entry in $state.entries){
 $artifact=[IO.Path]::GetFullPath($entry.artifact)
 if($artifact -ne (Join-Path $root 'group.jar') -and $artifact -ne (Join-Path $root 'tunnel.jar')){throw 'Invalid process artifact.'}
 $port=if($artifact -eq (Join-Path $root 'group.jar')){8180}else{8181}
 $ids=@([int]$entry.pid)+@(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue|Select-Object -ExpandProperty OwningProcess)
 foreach($processId in ($ids|Select-Object -Unique)){
  $process=Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
  if($process){if(!$process.CommandLine -or !$process.CommandLine.Contains($artifact)){throw 'Cannot verify process ownership.'};Stop-Process -Id $processId}
 }
}
Remove-Item -LiteralPath $statePath
