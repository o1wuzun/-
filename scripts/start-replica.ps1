param(
    [Parameter(Mandatory=$true)][string]$SourceUrl,
    [Parameter(Mandatory=$true)][string]$NodeCredential,
    [string]$PublicInvite,
    [string]$DatabasePath = './data/replica-default',
    [int]$Port = 8090
)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$jar=Join-Path $projectRoot 'services/replica-service/target/replica-service-0.1.0-SNAPSHOT.jar'
if(!(Test-Path -LiteralPath $jar)){throw 'Replica service jar is missing. Run Maven package first.'}
if([string]::IsNullOrWhiteSpace($NodeCredential) -or $NodeCredential.Length -lt 20){throw 'NodeCredential is required and appears too short.'}
$env:REPLICA_PUBLIC_ACCESS_SESSION=''
if($PublicInvite){
 $sourceUri=[Uri]$SourceUrl
 if($sourceUri.Scheme -ne 'https'){throw 'Public access invitations require HTTPS.'}
 $exchange=Invoke-WebRequest ($sourceUri.GetLeftPart([UriPartial]::Authority)+'/access/exchange') -Method POST -ContentType 'application/json' -Body (@{token=$PublicInvite}|ConvertTo-Json)
 $cookie=($exchange.Headers['Set-Cookie'][0] -split ';')[0]
 if($cookie -notmatch '^__Host-community_access=(.+)$'){throw 'Public invitation exchange failed.'}
 $env:REPLICA_PUBLIC_ACCESS_SESSION=$Matches[1]
}
$env:REPLICA_SOURCE_URL=$SourceUrl.TrimEnd('/')
$env:REPLICA_NODE_CREDENTIAL=$NodeCredential
$env:REPLICA_DB_URL="jdbc:h2:file=$DatabasePath"
$env:REPLICA_PORT=$Port
$env:REPLICA_BIND='127.0.0.1'
$logRoot=Join-Path $projectRoot 'data'
New-Item -ItemType Directory -Force -Path $logRoot | Out-Null
if(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue){throw "Port $Port is occupied."}
$process=Start-Process java -ArgumentList @('-jar',('"'+$jar+'"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $logRoot "replica-$Port.out.log") -RedirectStandardError (Join-Path $logRoot "replica-$Port.err.log")
Remove-Item Env:REPLICA_PUBLIC_ACCESS_SESSION
Write-Output "Replica service started on http://127.0.0.1:$Port (PID $($process.Id))."
Write-Output 'The node credential stays in this process environment and is not written to the project files.'
