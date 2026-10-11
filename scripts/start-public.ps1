param([switch]$LocalOnly)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dataRoot=Join-Path $projectRoot 'data'
$statePath=Join-Path $dataRoot 'public-processes.json'
New-Item -ItemType Directory -Force -Path $dataRoot | Out-Null
foreach($port in @(8095,8096,8097,8098)){if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw "Port $port occupied. Stop the previous public instance first."}}
if(!$LocalOnly -and (Get-NetTCPConnection -LocalPort 19090 -State Listen -ErrorAction SilentlyContinue)){throw 'Tunnel status port 19090 occupied.'}
foreach($port in @(8081,8082,8083)){
 $listening=$false
 for($i=0;$i -lt 30;$i++){if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){$listening=$true;break};Start-Sleep -Seconds 1}
 if(!$listening){throw 'Start the community services with scripts/start-dev.ps1 first.'}
}
if(!(Test-Path (Join-Path $projectRoot 'frontend/dist/index.html'))){throw 'Build frontend first: npm --prefix frontend run build'}
$credentialPath=Join-Path $dataRoot 'relay-keys.json'
if(!(Test-Path -LiteralPath $credentialPath)){
 $credentials=@{}
 foreach($group in @('group-default','group-contest')){$bytes=New-Object byte[] 32;[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes);$credentials[$group]=[Convert]::ToBase64String($bytes)}
 $credentials | ConvertTo-Json | Set-Content -LiteralPath $credentialPath
}
$keys=Get-Content -LiteralPath $credentialPath -Raw | ConvertFrom-Json
$state=@{entries=@();url=$null}
function Launch($executable,$arguments,$name,$artifact){
 $process=Start-Process $executable -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $dataRoot "$name.out.log") -RedirectStandardError (Join-Path $dataRoot "$name.err.log")
 $port=switch($name){'public-relay'{8095};'public-group-default'{8096};'public-group-contest'{8097};'public-gateway'{8098};'public-cloudflared'{19090}}
 $script:state.entries+=@{pid=$process.Id;artifact=$artifact;port=$port}
 $script:state | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $statePath
}
function StartJava($module,$name){$jar=Join-Path $projectRoot "services/$module/target/$module-0.1.0-SNAPSHOT.jar";if(!(Test-Path -LiteralPath $jar)){throw 'Build Maven modules first.'};Launch 'java' @('-jar',('"'+$jar+'"')) $name $jar}
function Ready($url){for($i=0;$i -lt 45;$i++){try{Invoke-RestMethod $url -TimeoutSec 2|Out-Null;return}catch{Start-Sleep -Seconds 1}};throw "Service not ready: $url"}
$names=@('RELAY_CREDENTIALS','RELAY_PORT','SERVER_ADDRESS','TUNNEL_RELAY_URL','TUNNEL_GROUP_ID','TUNNEL_CREDENTIAL','TUNNEL_SOURCE_URL','TUNNEL_PORT','PUBLIC_ACCESS_GATEWAY_KEY')
$saved=@{};foreach($name in $names){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try{
 $env:RELAY_CREDENTIALS=$keys|ConvertTo-Json -Compress;$env:RELAY_PORT='8095';$env:SERVER_ADDRESS='127.0.0.1'
 StartJava 'relay-service' 'public-relay';Ready 'http://127.0.0.1:8095/api/v1/relay/group-default/status'
 $env:TUNNEL_RELAY_URL='http://127.0.0.1:8095'
 foreach($entry in @(@{id='group-default';source=8082;port=8096},@{id='group-contest';source=8083;port=8097})){
  $env:TUNNEL_GROUP_ID=$entry.id;$env:TUNNEL_CREDENTIAL=$keys.($entry.id);$env:TUNNEL_SOURCE_URL="http://127.0.0.1:$($entry.source)";$env:TUNNEL_PORT=$entry.port
  StartJava 'tunnel-client' "public-$($entry.id)";Ready "http://127.0.0.1:$($entry.port)/api/v1/tunnel/status"
 }
 # Public gateway and Cloudflare need no relay credentials in their environment.
 Remove-Item Env:RELAY_CREDENTIALS,Env:TUNNEL_CREDENTIAL -ErrorAction SilentlyContinue
 $keyPath=Join-Path $dataRoot 'gateway-key.txt'
 if(!(Test-Path -LiteralPath $keyPath)){throw 'Restart community services with the updated start-dev script first.'}
 $env:PUBLIC_ACCESS_GATEWAY_KEY=(Get-Content -LiteralPath $keyPath -Raw).Trim()
 $gateway=Join-Path $projectRoot 'scripts/public-gateway.mjs'
 Launch 'node' @(('"'+$gateway+'"')) 'public-gateway' $gateway
 Ready 'http://127.0.0.1:8098/access'
 Remove-Item Env:PUBLIC_ACCESS_GATEWAY_KEY
 if($LocalOnly){Write-Output 'Local public gateway ready: http://127.0.0.1:8098';return}
 $cloudflared=Join-Path $dataRoot 'tools/cloudflared.exe'
 if(!(Test-Path -LiteralPath $cloudflared)){throw 'Install official Cloudflare cloudflared at data/tools/cloudflared.exe first.'}
 Launch $cloudflared @('tunnel','--url','http://127.0.0.1:8098','--protocol','auto','--no-autoupdate','--metrics','127.0.0.1:19090') 'public-cloudflared' $cloudflared
 for($i=0;$i -lt 60;$i++){
  $log=Get-Content (Join-Path $dataRoot 'public-cloudflared.err.log') -Raw -ErrorAction SilentlyContinue
  if($log -match 'https://[a-z0-9-]+\.trycloudflare\.com'){
   $candidate=$Matches[0]
   try{$ready=Invoke-WebRequest 'http://127.0.0.1:19090/ready' -TimeoutSec 2;if($ready.StatusCode -eq 200){$state.url=$candidate;$state|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $statePath;Write-Output "Public HTTPS URL: $($state.url)";Write-Output 'Access remains locked until the administrator enables it and issues invitations.';Write-Output 'Keep this PC awake. Restarting the temporary tunnel can change this address.';return}}catch{}
  }
  Start-Sleep -Seconds 1
 }
 throw 'Cloudflare did not issue a public URL. Inspect data/public-cloudflared.err.log.'
}catch{& (Join-Path $PSScriptRoot 'stop-public.ps1');throw}finally{foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
