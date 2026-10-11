param([switch]$BrokerOnly)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtime=Join-Path $projectRoot 'data/messaging'
$alias=Join-Path ([System.IO.Path]::GetPathRoot($projectRoot)) 'community-mq'
$link=Get-Item -LiteralPath $alias
if($link.LinkType -ne 'Junction' -or $link.Target -ne $runtime){throw 'Unexpected runtime alias.'}
$env:ERLANG_HOME=Join-Path $alias 'erlang'
$env:RABBITMQ_BASE=Join-Path $alias 'broker-data'
$env:RABBITMQ_NODENAME='community@localhost'
$env:ERL_EPMD_ADDRESS='127.0.0.1'
if(Get-NetTCPConnection -LocalPort 5672 -State Listen -ErrorAction SilentlyContinue){
 & (Join-Path $alias 'rabbitmq_server-4.1.4/sbin/rabbitmqctl.bat') stop
 if($LASTEXITCODE -ne 0){throw 'RabbitMQ graceful shutdown failed.'}
}
$state=Join-Path $runtime 'smtp-process.json'
if(!$BrokerOnly -and (Test-Path -LiteralPath $state)){
 $processId=(Get-Content -LiteralPath $state -Raw|ConvertFrom-Json).pid
 $process=Get-CimInstance Win32_Process -Filter "ProcessId=$processId"
 if($process){if(!$process.CommandLine.Contains((Join-Path $PSScriptRoot 'mail-test-server.mjs'))){throw 'SMTP PID no longer belongs to the fixture.'};Stop-Process -Id $processId}
}
