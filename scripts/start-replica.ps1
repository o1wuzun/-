param(
    [Parameter(Mandatory=$true)][string]$SourceUrl,
    [Parameter(Mandatory=$true)][string]$NodeCredential,
    [string]$DatabasePath = './data/replica-default',
    [int]$Port = 8090
)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$jar=Join-Path $projectRoot 'services/replica-service/target/replica-service-0.1.0-SNAPSHOT.jar'
if(!(Test-Path -LiteralPath $jar)){throw 'Replica service jar is missing. Run Maven package first.'}
if([string]::IsNullOrWhiteSpace($NodeCredential) -or $NodeCredential.Length -lt 20){throw 'NodeCredential is required and appears too short.'}
$env:REPLICA_SOURCE_URL=$SourceUrl.TrimEnd('/')
$env:REPLICA_NODE_CREDENTIAL=$NodeCredential
$env:REPLICA_DB_URL="jdbc:h2:file=$DatabasePath"
$env:REPLICA_PORT=$Port
$env:REPLICA_BIND='127.0.0.1'
$logRoot=Join-Path $projectRoot 'data'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
if(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue){throw "Port $Port is occupied."}
$process=Start-Process java -ArgumentList @('-jar',('"'+$jar+'"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot "replica-$Port.out.log") -RedirectStandardError (Join-Path $logRoot "replica-$Port.err.log")
Write-Output "Replica service started on http://127.0.0.1:$Port (PID $($process.Id))."
Write-Output 'The node credential stays in this process environment and is not written to the project files.'
