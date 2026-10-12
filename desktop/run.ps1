param([ValidateSet('local','lan','public','stop')][string]$Mode='local',[string]$AdminEmail)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $root
$env:COMMUNITY_DESKTOP='1'
$env:IDENTITY_ADMIN_EMAILS=$AdminEmail
if($Mode -ne 'stop'){
 foreach($port in @(8081,8082,8083,5173)){
  $listeners=Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
  foreach($listener in $listeners){$p=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)";if(!$p.CommandLine -or !$p.CommandLine.Contains($root)){throw "端口 $port 已被其他程序占用，请先停止旧版社区。"}}
 }
}
try{& (Join-Path $root 'scripts/switch-network.ps1') -Mode $Mode -Database mysql -NoBrowser}
catch{& (Join-Path $root 'scripts/stop-public.ps1');& (Join-Path $root 'scripts/stop-dev.ps1');throw}
finally{if($Mode -eq 'stop'){& (Join-Path $root 'scripts/stop-mysql.ps1')}}
