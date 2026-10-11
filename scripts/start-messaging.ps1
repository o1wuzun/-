param([switch]$TestMailbox)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtime=Join-Path $projectRoot 'data/messaging'
$alias=Join-Path ([System.IO.Path]::GetPathRoot($projectRoot)) 'community-mq'
if(!(Test-Path -LiteralPath $alias)){New-Item -ItemType Directory -Force $runtime|Out-Null;New-Item -ItemType Junction -Path $alias -Target $runtime|Out-Null}
$link=Get-Item -LiteralPath $alias
if($link.LinkType -ne 'Junction' -or $link.Target -ne $runtime){throw 'Runtime alias targets another directory.'}
$broker=Join-Path $alias 'rabbitmq_server-4.1.4/sbin/rabbitmq-server.bat'
$env:ERLANG_HOME=Join-Path $alias 'erlang'
if(!(Test-Path -LiteralPath $broker) -or !(Test-Path -LiteralPath (Join-Path $env:ERLANG_HOME 'bin/erl.exe'))){throw 'Install the project RabbitMQ runtime first; see docs/NOTIFICATIONS.md.'}
$env:RABBITMQ_BASE=Join-Path $alias 'broker-data'
New-Item -ItemType Directory -Force $env:RABBITMQ_BASE | Out-Null
$env:RABBITMQ_CONFIG_FILE=Join-Path $env:RABBITMQ_BASE 'rabbitmq'
$env:RABBITMQ_NODENAME='community@localhost'
$env:ERL_EPMD_ADDRESS='127.0.0.1'
$env:RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS='-kernel inet_dist_use_interface {127,0,0,1}'
@'
listeners.tcp.1 = 127.0.0.1:5672
distribution.listener.port_range.min = 25672
distribution.listener.port_range.max = 25672
'@ | Set-Content -LiteralPath ($env:RABBITMQ_CONFIG_FILE+'.conf') -Encoding utf8
if(!(Get-NetTCPConnection -LocalPort 5672 -State Listen -ErrorAction SilentlyContinue)){
 $process=Start-Process $broker -WorkingDirectory $alias -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'rabbit.out.log') -RedirectStandardError (Join-Path $runtime 'rabbit.err.log')
 @{pid=$process.Id;node=$env:RABBITMQ_NODENAME}|ConvertTo-Json|Set-Content (Join-Path $runtime 'broker-process.json')
 for($i=0;$i -lt 45;$i++){if(Get-NetTCPConnection -LocalPort 5672 -State Listen -ErrorAction SilentlyContinue){break};if($process.HasExited){throw 'RabbitMQ failed; inspect data/messaging/rabbit.err.log.'};Start-Sleep -Seconds 1}
 if(!(Get-NetTCPConnection -LocalPort 5672 -State Listen -ErrorAction SilentlyContinue)){throw 'RabbitMQ did not become ready.'}
}else{
 $listener=Get-NetTCPConnection -LocalPort 5672 -State Listen
 $owner=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
 if(!$owner.CommandLine -or !$owner.CommandLine.Contains($alias)){throw 'Port 5672 belongs to another broker.'}
}
if($TestMailbox){
 if(Get-NetTCPConnection -LocalPort 1025 -State Listen -ErrorAction SilentlyContinue){throw 'Test SMTP port is occupied.'}
 $fixture=Join-Path $PSScriptRoot 'mail-test-server.mjs'
 $process=Start-Process node -ArgumentList ('"'+$fixture+'"') -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'smtp.out.log') -RedirectStandardError (Join-Path $runtime 'smtp.err.log')
 @{pid=$process.Id}|ConvertTo-Json|Set-Content (Join-Path $runtime 'smtp-process.json')
}
Write-Output 'RabbitMQ is available only on this computer (127.0.0.1:5672).'
