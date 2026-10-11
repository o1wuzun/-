param()
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$config=Get-Content -LiteralPath (Join-Path $root 'group-config.json') -Raw -Encoding UTF8 | ConvertFrom-Json
if($config.id -notmatch '^remote-[a-f0-9-]{36}$'){throw 'Invalid group ID.'}
if($config.relay_url -notmatch '^https://[^/]+$'){throw 'Relay requires HTTPS.'}
$java=(Get-Command java -ErrorAction Stop).Source
$version=& $java --version | Out-String
if($version -notmatch '(?:openjdk|java) (\d+)' -or [int]$Matches[1] -lt 21){throw 'Install Java 21 or newer first.'}
foreach($port in @(8180,8181)){if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw "Port $port is occupied. Stop the previous group first."}}
New-Item -ItemType Directory -Force -Path (Join-Path $root 'data') | Out-Null
$variables=@('SERVER_ADDRESS','SPRING_PROFILES_ACTIVE','GROUP_PORT','GROUP_DB_URL','DB_PASSWORD','GROUP_ID','GROUP_NAME','GROUP_DESCRIPTION','GROUP_VISIBILITY','GROUP_JOIN_KEY','GROUP_OWNER_ID','IDENTITY_PUBLIC_KEY','TUNNEL_PORT','TUNNEL_RELAY_URL','TUNNEL_GROUP_ID','TUNNEL_CREDENTIAL','TUNNEL_SOURCE_URL')
$saved=@{};foreach($name in $variables){$saved[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
$started=@()
try{
 $env:SERVER_ADDRESS='127.0.0.1';$env:SPRING_PROFILES_ACTIVE='';$env:GROUP_PORT='8180';$env:GROUP_DB_URL='jdbc:h2:file:./data/group';$env:DB_PASSWORD=''
 $env:GROUP_ID=$config.id;$env:GROUP_NAME=$config.name;$env:GROUP_DESCRIPTION=$config.description;$env:GROUP_VISIBILITY=$config.visibility;$env:GROUP_JOIN_KEY=$config.join_key;$env:GROUP_OWNER_ID=$config.owner_id;$env:IDENTITY_PUBLIC_KEY=$config.identity_public_key
 $groupJar=Join-Path $root 'group.jar'
 $group=Start-Process $java -ArgumentList @('-jar',('"'+$groupJar+'"')) -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'data/group.log') -RedirectStandardError (Join-Path $root 'data/group.err.log');$started+=$group
 $ready=$false
 for($i=0;$i -lt 60;$i++){try{$info=Invoke-RestMethod 'http://127.0.0.1:8180/api/v1/group' -TimeoutSec 2;if($info.data.id -eq $config.id){$ready=$true;break}}catch{};Start-Sleep -Seconds 1}
 if(!$ready){throw 'Group did not start. Inspect data/group.log.'}
 $env:TUNNEL_PORT='8181';$env:TUNNEL_RELAY_URL=$config.relay_url;$env:TUNNEL_GROUP_ID=$config.id;$env:TUNNEL_CREDENTIAL=$config.relay_credential;$env:TUNNEL_SOURCE_URL='http://127.0.0.1:8180'
 $tunnelJar=Join-Path $root 'tunnel.jar'
 $tunnel=Start-Process $java -ArgumentList @('-jar',('"'+$tunnelJar+'"')) -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $root 'data/tunnel.log') -RedirectStandardError (Join-Path $root 'data/tunnel.err.log');$started+=$tunnel
 @{entries=@(@{pid=$group.Id;artifact=$groupJar},@{pid=$tunnel.Id;artifact=$tunnelJar})} | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $root 'data/processes.json') -Encoding UTF8
 Write-Output 'Group started. Return to the community site to check connection status.'
 Write-Output 'Keep this computer awake. Temporary public address changes require a new bundle config.'
}catch{foreach($process in $started){Stop-Process -Id $process.Id -ErrorAction SilentlyContinue};throw}finally{foreach($name in $variables){[Environment]::SetEnvironmentVariable($name,$saved[$name],'Process')}}
