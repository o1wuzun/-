param([string]$BindAddress='127.0.0.1',[ValidateSet('auto','h2','mysql')][string]$Database='auto',[string]$WebBindAddress='')
$ErrorActionPreference = 'Stop'
$parsedAddress=$null
if(![System.Net.IPAddress]::TryParse($BindAddress,[ref]$parsedAddress)){throw 'BindAddress must be a local IP address.'}
if(!$WebBindAddress){$WebBindAddress=$BindAddress}
$parsedWebAddress=$null
if(![System.Net.IPAddress]::TryParse($WebBindAddress,[ref]$parsedWebAddress)){throw 'WebBindAddress must be a local IP address.'}
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dataRoot = Join-Path $projectRoot 'data'
New-Item -ItemType Directory -Force -Path $dataRoot | Out-Null
if($Database -eq 'auto'){$modePath=Join-Path $dataRoot 'database-mode.json';$Database=if(Test-Path -LiteralPath $modePath){(Get-Content -LiteralPath $modePath -Raw|ConvertFrom-Json).database}else{'h2'}}
if($Database -eq 'mysql'){if($env:COMMUNITY_DESKTOP){& (Join-Path $PSScriptRoot 'start-mysql.ps1') -Port 3317}else{& (Join-Path $PSScriptRoot 'start-mysql.ps1')}}
$gatewayKeyPath=Join-Path $dataRoot 'gateway-key.txt'
if(!(Test-Path -LiteralPath $gatewayKeyPath)){$gatewayBytes=New-Object byte[] 32;[System.Security.Cryptography.RandomNumberGenerator]::Fill($gatewayBytes);[Convert]::ToBase64String($gatewayBytes)|Set-Content -LiteralPath $gatewayKeyPath}
$env:PUBLIC_ACCESS_GATEWAY_KEY=(Get-Content -LiteralPath $gatewayKeyPath -Raw).Trim()
$env:SERVER_ADDRESS=$BindAddress
$keysPath = Join-Path $dataRoot 'dev-keys.json'
if (!(Test-Path -LiteralPath $keysPath)) {
    $rsa = [System.Security.Cryptography.RSA]::Create(2048)
    $keyBytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
    @{ public_key = [Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo()); private_key = [Convert]::ToBase64String($rsa.ExportPkcs8PrivateKey()); join_key = [Convert]::ToBase64String($keyBytes) } | ConvertTo-Json | Set-Content -LiteralPath $keysPath
    $rsa.Dispose()
}
$keys = Get-Content -Raw -LiteralPath $keysPath | ConvertFrom-Json
if (!$keys.PSObject.Properties['contest_join_key']) {
    $contestBytes=New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($contestBytes)
    $keys | Add-Member -NotePropertyName contest_join_key -NotePropertyValue ([Convert]::ToBase64String($contestBytes))
    $keys | ConvertTo-Json | Set-Content -LiteralPath $keysPath
}
$env:IDENTITY_PUBLIC_KEY = $keys.public_key
$env:IDENTITY_PRIVATE_KEY = $keys.private_key
$env:GROUP_JOIN_KEY = $keys.join_key
foreach ($port in @(8081,8082,8083,5173)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Port $port is occupied. Stop the previous development instance first." }
}
$identityJar = Join-Path $projectRoot 'services/identity-service/target/identity-service-0.1.0-SNAPSHOT.jar'
$groupJar = Join-Path $projectRoot 'services/group-service/target/group-service-0.1.0-SNAPSHOT.jar'
$viteScript = Join-Path $projectRoot 'frontend/node_modules/vite/bin/vite.js'
if($env:COMMUNITY_DESKTOP){$viteScript=Join-Path $projectRoot 'desktop/web-server.mjs'}
foreach($file in @($identityJar,$groupJar,$viteScript)) {if(!(Test-Path -LiteralPath $file)){throw "Missing $file. Build the services and install frontend dependencies first."}}
function DatabaseFor($id){
 if($Database -eq 'mysql'){
  $settings=Get-Content -LiteralPath (Join-Path $dataRoot 'mysql/project-config.json') -Raw|ConvertFrom-Json
  $db=$settings.services|Where-Object {$_.id -eq $id}
  if(!$db){throw 'Missing project database configuration.'}
  $env:SPRING_PROFILES_ACTIVE='mysql'
  $env:DB_URL="jdbc:mysql://127.0.0.1:$($settings.port)/$($db.database)?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&characterEncoding=UTF-8"
  $env:DB_USER=$db.user;$env:DB_PASSWORD=$db.password;$env:DB_MIGRATION_USER=$db.migration_user;$env:DB_MIGRATION_PASSWORD=$db.migration_password
 }else{$env:SPRING_PROFILES_ACTIVE='';$env:DB_PASSWORD=''}
}
DatabaseFor 'identity'
$env:DEPLOYMENT_LOCAL_ENABLED=if($Database -eq 'mysql'){'true'}else{'false'};$env:DEPLOYMENT_LOCAL_SHELL=(Get-Command pwsh).Source
# SMTP/broker secrets live in the ignored data directory and only enter identity.
$notificationPath=Join-Path $dataRoot 'notifications.json'
$notificationVariables=@()
if(Test-Path -LiteralPath $notificationPath){
 $notificationConfig=Get-Content -LiteralPath $notificationPath -Raw|ConvertFrom-Json
 if($notificationConfig.enabled){
  $env:SPRING_PROFILES_ACTIVE=if($env:SPRING_PROFILES_ACTIVE){$env:SPRING_PROFILES_ACTIVE+',notifications'}else{'notifications'}
  $allowed=@('RABBITMQ_HOST','RABBITMQ_PORT','RABBITMQ_USER','RABBITMQ_PASSWORD','RABBITMQ_VHOST','SMTP_HOST','SMTP_PORT','SMTP_USER','SMTP_PASSWORD','SMTP_FROM','SMTP_AUTH','SMTP_STARTTLS','SMTP_SSL')
  foreach($item in $notificationConfig.environment.PSObject.Properties){
   if($item.Name -notin $allowed){throw 'Unknown notification configuration variable.'}
   [Environment]::SetEnvironmentVariable($item.Name,[string]$item.Value,'Process');$notificationVariables+= $item.Name
  }
 }
}
$env:IDENTITY_DB_URL = 'jdbc:h2:file:./data/identity'
$env:GROUP_DB_URL = 'jdbc:h2:file:./data/group-default'
$env:GROUP_PORT='8082'
$env:GROUP_ID='group-default'
$env:GROUP_NAME='算法基础学习组'
$env:GROUP_VISIBILITY='PRIVATE';$env:GROUP_DESCRIPTION='从二分查找与数据结构开始'
$identity = Start-Process java -ArgumentList @('-jar',('"'+$identityJar+'"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $dataRoot 'identity.out.log') -RedirectStandardError (Join-Path $dataRoot 'identity.err.log')
foreach($variable in $notificationVariables){[Environment]::SetEnvironmentVariable($variable,$null,'Process')}
# Private signing material belongs exclusively to the identity process.
Remove-Item Env:IDENTITY_PRIVATE_KEY
Remove-Item Env:PUBLIC_ACCESS_GATEWAY_KEY
DatabaseFor 'default'
$group = Start-Process java -ArgumentList @('-jar',('"'+$groupJar+'"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $dataRoot 'group.out.log') -RedirectStandardError (Join-Path $dataRoot 'group.err.log')
$env:GROUP_PORT='8083'
$env:GROUP_ID='group-contest'
$env:GROUP_NAME='算法竞赛练习组'
$env:GROUP_VISIBILITY='PRIVATE';$env:GROUP_DESCRIPTION='分享比赛思路和解题经验'
$env:GROUP_DB_URL='jdbc:h2:file:./data/group-contest'
$env:GROUP_JOIN_KEY=$keys.contest_join_key
DatabaseFor 'contest'
$contest = Start-Process java -ArgumentList @('-jar',('"'+$groupJar+'"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $dataRoot 'contest.out.log') -RedirectStandardError (Join-Path $dataRoot 'contest.err.log')
Remove-Item Env:DB_PASSWORD,Env:DB_MIGRATION_PASSWORD -ErrorAction SilentlyContinue
$web = Start-Process node -ArgumentList @(('"'+$viteScript+'"'),'--host',$WebBindAddress) -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $dataRoot 'web.out.log') -RedirectStandardError (Join-Path $dataRoot 'web.err.log')
@{identity=$identity.Id;group=$group.Id;contest=$contest.Id;web=$web.Id} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $dataRoot 'dev-processes.json')
Write-Output 'Development processes launched. Frontend uses the selected local bind address on port 5173'
Write-Output 'Local-only development invite key: data/dev-keys.json (join_key). Do not publish this file.'

Write-Output "Database mode: $Database"
