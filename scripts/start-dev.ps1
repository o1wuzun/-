param(
    [string]$BindAddress = '127.0.0.1',
    [ValidateSet('auto','h2','mysql')][string]$Database = 'auto',
    [string]$WebBindAddress = ''
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dataRoot = Join-Path $projectRoot 'data'
New-Item -ItemType Directory -Force -Path $dataRoot | Out-Null

function Assert-Address([string]$value, [string]$name) {
    $parsed = $null
    if (![System.Net.IPAddress]::TryParse($value, [ref]$parsed)) { throw "$name 必须是 IP 地址，当前值：$value" }
}
Assert-Address $BindAddress 'BindAddress'
if ([string]::IsNullOrWhiteSpace($WebBindAddress)) { $WebBindAddress = $BindAddress }
Assert-Address $WebBindAddress 'WebBindAddress'

if ($Database -eq 'auto') {
    $modePath = Join-Path $dataRoot 'database-mode.json'
    $Database = if (Test-Path -LiteralPath $modePath) { (Get-Content $modePath -Raw | ConvertFrom-Json).database } else { 'h2' }
}

function Require-File([string]$path, [string]$message) { if (!(Test-Path -LiteralPath $path)) { throw $message } }
$identityJar = Join-Path $projectRoot 'services/identity-service/target/identity-service-0.1.0-SNAPSHOT.jar'
$groupJar = Join-Path $projectRoot 'services/group-service/target/group-service-0.1.0-SNAPSHOT.jar'
$webEntry = if ($env:COMMUNITY_DESKTOP) { Join-Path $projectRoot 'desktop/web-server.mjs' } else { Join-Path $projectRoot 'frontend/node_modules/vite/bin/vite.js' }
Require-File $identityJar '找不到 identity-service JAR，请先执行 Maven 打包。'
Require-File $groupJar '找不到 group-service JAR，请先执行 Maven 打包。'
Require-File $webEntry '找不到前端运行入口，请先安装前端依赖。'
if ($Database -eq 'mysql') { & (Join-Path $PSScriptRoot 'start-mysql.ps1') }

function Ensure-Key([string]$path, [int]$bytes = 32) {
    if (!(Test-Path -LiteralPath $path)) { $raw=New-Object byte[] $bytes; [System.Security.Cryptography.RandomNumberGenerator]::Fill($raw); [Convert]::ToBase64String($raw) | Set-Content $path -NoNewline }
    return (Get-Content $path -Raw).Trim()
}
$gatewayKey = Ensure-Key (Join-Path $dataRoot 'gateway-key.txt')
$keysPath = Join-Path $dataRoot 'dev-keys.json'
if (!(Test-Path -LiteralPath $keysPath)) {
    $rsa=[System.Security.Cryptography.RSA]::Create(2048)
    try { $join=New-Object byte[] 24; [System.Security.Cryptography.RandomNumberGenerator]::Fill($join); @{public_key=[Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo());private_key=[Convert]::ToBase64String($rsa.ExportPkcs8PrivateKey());join_key=[Convert]::ToBase64String($join)} | ConvertTo-Json | Set-Content $keysPath } finally {$rsa.Dispose()}
}
$keys=Get-Content $keysPath -Raw | ConvertFrom-Json
if (!$keys.PSObject.Properties['contest_join_key']) { $keys | Add-Member -NotePropertyName contest_join_key -NotePropertyValue (Ensure-Key (Join-Path $dataRoot 'contest-join-key.txt') 24); $keys | ConvertTo-Json | Set-Content $keysPath }

foreach ($port in @(8081,8082,8083,5173)) { if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "端口 $port 已被占用，请先执行 scripts/stop-dev.ps1。" } }

function Set-Database([string]$serviceId) {
    if ($Database -eq 'mysql') {
        $configPath=Join-Path $dataRoot 'mysql/project-config.json'; Require-File $configPath '缺少 MySQL 项目配置。'; $config=Get-Content $configPath -Raw | ConvertFrom-Json; $db=$config.services | Where-Object {$_.id -eq $serviceId}; if (!$db){throw "MySQL 中缺少服务 $serviceId 的数据库配置。"}
        $env:SPRING_PROFILES_ACTIVE='mysql'; $env:DB_URL="jdbc:mysql://127.0.0.1:$($config.port)/$($db.database)?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&characterEncoding=UTF-8"; $env:DB_USER=$db.user; $env:DB_PASSWORD=$db.password; $env:DB_MIGRATION_USER=$db.migration_user; $env:DB_MIGRATION_PASSWORD=$db.migration_password
    } else { $env:SPRING_PROFILES_ACTIVE=''; Remove-Item Env:DB_URL,Env:DB_USER,Env:DB_PASSWORD,Env:DB_MIGRATION_USER,Env:DB_MIGRATION_PASSWORD -ErrorAction SilentlyContinue }
}
function Start-Service([string]$name,[string]$jar,[hashtable]$environment) {
    foreach($item in $environment.GetEnumerator()){[Environment]::SetEnvironmentVariable($item.Key,[string]$item.Value,'Process')}; $out=Join-Path $dataRoot "$name.out.log"; $err=Join-Path $dataRoot "$name.err.log"; $process=Start-Process -FilePath java -ArgumentList @('-jar',$jar) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput $out -RedirectStandardError $err; Start-Sleep -Milliseconds 500; if($process.HasExited){throw "$name 启动失败，请查看 data/$name.err.log。"}; return $process
}

$started=@()
try {
    $env:SERVER_ADDRESS=$BindAddress; $env:PUBLIC_ACCESS_GATEWAY_KEY=$gatewayKey; $env:IDENTITY_PUBLIC_KEY=$keys.public_key; $env:IDENTITY_PRIVATE_KEY=$keys.private_key; $env:GROUP_JOIN_KEY=$keys.join_key
    $env:DEPLOYMENT_LOCAL_ENABLED=if($Database -eq 'mysql'){'true'}else{'false'}
    $pwsh=(Get-Command pwsh -ErrorAction SilentlyContinue).Source; if(!$pwsh){$pwsh=(Get-Command powershell -ErrorAction SilentlyContinue).Source}; if($pwsh){$env:DEPLOYMENT_LOCAL_SHELL=$pwsh}
    $notificationVariables=@(); $notificationPath=Join-Path $dataRoot 'notifications.json'
    if(Test-Path $notificationPath){$notification=Get-Content $notificationPath -Raw|ConvertFrom-Json;if($notification.enabled){$env:SPRING_PROFILES_ACTIVE=if($env:SPRING_PROFILES_ACTIVE){"$($env:SPRING_PROFILES_ACTIVE),notifications"}else{'notifications'};$allowed=@('RABBITMQ_HOST','RABBITMQ_PORT','RABBITMQ_USER','RABBITMQ_PASSWORD','RABBITMQ_VHOST','SMTP_HOST','SMTP_PORT','SMTP_USER','SMTP_PASSWORD','SMTP_FROM','SMTP_AUTH','SMTP_STARTTLS','SMTP_SSL');foreach($item in $notification.environment.PSObject.Properties){if($item.Name -notin $allowed){throw "通知配置变量不允许：$($item.Name)"};[Environment]::SetEnvironmentVariable($item.Name,[string]$item.Value,'Process');$notificationVariables+=$item.Name}}}
    Set-Database 'identity'; $env:IDENTITY_DB_URL='jdbc:h2:file:./data/identity'; $identity=Start-Service 'identity' $identityJar @{SERVER_PORT='8081'}; $started+=$identity
    foreach($variable in $notificationVariables){[Environment]::SetEnvironmentVariable($variable,$null,'Process')}; Remove-Item Env:IDENTITY_PRIVATE_KEY,Env:PUBLIC_ACCESS_GATEWAY_KEY -ErrorAction SilentlyContinue
    Set-Database 'default'; $env:GROUP_PORT='8082';$env:GROUP_ID='group-default';$env:GROUP_NAME='算法基础学习组';$env:GROUP_VISIBILITY='PRIVATE';$env:GROUP_DESCRIPTION='从二分查找与数据结构开始';$env:GROUP_DB_URL='jdbc:h2:file:./data/group-default';$env:GROUP_JOIN_KEY=$keys.join_key; $group=Start-Service 'group' $groupJar @{SERVER_PORT='8082'}; $started+=$group
    Set-Database 'contest'; $env:GROUP_PORT='8083';$env:GROUP_ID='group-contest';$env:GROUP_NAME='算法竞赛练习组';$env:GROUP_VISIBILITY='PRIVATE';$env:GROUP_DESCRIPTION='分享比赛思路和解题经验';$env:GROUP_DB_URL='jdbc:h2:file:./data/group-contest';$env:GROUP_JOIN_KEY=$keys.contest_join_key; $contest=Start-Service 'contest' $groupJar @{SERVER_PORT='8083'}; $started+=$contest
    Remove-Item Env:DB_PASSWORD,Env:DB_MIGRATION_PASSWORD -ErrorAction SilentlyContinue
    $webWorkingDirectory = if ($env:COMMUNITY_DESKTOP) { Join-Path $projectRoot 'desktop' } else { Join-Path $projectRoot 'frontend' }
    $web=Start-Process -FilePath node -ArgumentList @($webEntry,'--host',$WebBindAddress) -WorkingDirectory $webWorkingDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $dataRoot 'web.out.log') -RedirectStandardError (Join-Path $dataRoot 'web.err.log'); Start-Sleep -Milliseconds 500; if($web.HasExited){throw '前端启动失败，请查看 data/web.err.log。'}; $started+=$web
    @{identity=$identity.Id;group=$group.Id;contest=$contest.Id;web=$web.Id}|ConvertTo-Json|Set-Content (Join-Path $dataRoot 'dev-processes.json')
    Write-Output "开发服务已启动：前端 http://$WebBindAddress`:5173，数据库模式 $Database。"
} catch { foreach($process in $started){if($process -and !$process.HasExited){Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue}}; throw }
