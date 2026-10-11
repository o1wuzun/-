param([Parameter(Mandatory=$true)][string]$Configuration)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$configRoot=Join-Path $projectRoot 'data/deployed-groups'
$resolved=(Resolve-Path -LiteralPath $Configuration).Path
if(!$resolved.StartsWith($configRoot+[IO.Path]::DirectorySeparatorChar)){throw 'Configuration must belong to this project.'}
$config=Get-Content -LiteralPath $resolved -Raw|ConvertFrom-Json
if($config.id -notmatch '^local-[a-f0-9-]{36}$' -or $config.port -lt 8100 -or $config.port -gt 8199){throw 'Invalid local deployment configuration.'}
if(Get-NetTCPConnection -LocalPort $config.port -State Listen -ErrorAction SilentlyContinue){throw 'Deployment port is occupied.'}
$jar=Join-Path $projectRoot 'services/group-service/target/group-service-0.1.0-SNAPSHOT.jar'
$keys=Get-Content -LiteralPath (Join-Path $projectRoot 'data/dev-keys.json') -Raw|ConvertFrom-Json
$env:IDENTITY_PUBLIC_KEY=$keys.public_key
Remove-Item Env:IDENTITY_PRIVATE_KEY,Env:PUBLIC_ACCESS_GATEWAY_KEY -ErrorAction SilentlyContinue
foreach($item in (Get-ChildItem Env:)){if($item.Name -match '^(SMTP_|RABBITMQ_|NOTIFICATIONS_|DEPLOYMENT_)'){[Environment]::SetEnvironmentVariable($item.Name,$null,'Process')}}
$env:SERVER_ADDRESS='127.0.0.1';$env:SPRING_PROFILES_ACTIVE='mysql'
$env:GROUP_ID=$config.id;$env:GROUP_NAME=$config.name;$env:GROUP_DESCRIPTION=$config.description
$env:GROUP_VISIBILITY=$config.visibility;$env:GROUP_OWNER_ID=$config.owner_id;$env:GROUP_JOIN_KEY=$config.join_key
$env:GROUP_PORT=[string]$config.port;$env:DB_URL=$config.db_url;$env:DB_USER=$config.db_user;$env:DB_PASSWORD=$config.db_password
# Schema has already been created by the provisioner. Child JVM has DML credentials only.
$env:SPRING_FLYWAY_ENABLED='false';$env:DB_MIGRATION_USER=$config.db_user;$env:DB_MIGRATION_PASSWORD=$config.db_password
$process=Start-Process java -ArgumentList @('-jar',('"'+$jar+'"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $configRoot ($config.id+'.out.log')) -RedirectStandardError (Join-Path $configRoot ($config.id+'.err.log'))
@{pid=$process.Id;id=$config.id;port=$config.port}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $configRoot ($config.id+'.process.json'))
for($i=0;$i -lt 45;$i++){
 try{$info=(Invoke-RestMethod "http://127.0.0.1:$($config.port)/api/v1/group" -TimeoutSec 2).data;if($info.id -eq $config.id){Write-Output 'Group process ready.';exit 0}}catch{}
 if($process.HasExited){throw 'Group process exited before readiness.'}
 Start-Sleep -Seconds 1
}
Stop-Process -Id $process.Id -ErrorAction SilentlyContinue
throw 'Group readiness timeout.'
