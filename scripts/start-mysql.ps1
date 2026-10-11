param([int]$Port=3307)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$mysqlRoot=Join-Path $projectRoot 'data/mysql'
$databaseRoot=Join-Path $mysqlRoot 'server-data'
$configPath=Join-Path $mysqlRoot 'project-config.json'
$mysqld=(Get-Command mysqld -ErrorAction Stop).Source
$tool=Join-Path $projectRoot 'services/database-tools/target/database-tools-0.1.0-SNAPSHOT.jar'
if(!(Test-Path -LiteralPath $tool)){throw 'Build database-tools before initializing MySQL.'}
New-Item -ItemType Directory -Force -Path $mysqlRoot | Out-Null
# MySQL on Windows cannot reliably reopen data directories through Unicode paths.
if($mysqlRoot -match '[^\x00-\x7F]'){
 $alias=Join-Path ([System.IO.Path]::GetPathRoot($projectRoot)) 'community-mysql'
 if(Test-Path -LiteralPath $alias){$link=Get-Item -LiteralPath $alias;if($link.LinkType -ne 'Junction' -or $link.Target -ne $mysqlRoot){throw 'MySQL ASCII alias exists and does not target this project.'}}else{New-Item -ItemType Junction -Path $alias -Target $mysqlRoot|Out-Null}
 $databaseRoot=Join-Path $alias 'server-data'
}
if(!(Test-Path -LiteralPath $configPath)){
 function Secret{$bytes=New-Object byte[] 32;[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes);[Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')}
 $services=@()
 foreach($item in @(@{id='identity';type='identity';file='identity'},@{id='default';type='group';file='group-default'},@{id='contest';type='group';file='group-contest'})){$services+=@{id=$item.id;type=$item.type;database="community_$($item.id)";user="comm_$($item.id)";password=(Secret);migration_user="migrate_$($item.id)";migration_password=(Secret);h2_file="data/$($item.file)"}}
 @{port=$Port;admin_password=(Secret);services=$services}|ConvertTo-Json -Depth 5|Set-Content -LiteralPath $configPath
}
$config=Get-Content -LiteralPath $configPath -Raw|ConvertFrom-Json
if($config.port -ne $Port){throw 'Port differs from persisted project configuration.'}
$listener=Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if($listener){$owner=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)";if(!$owner.CommandLine -or !$owner.CommandLine.Contains($databaseRoot)){throw 'Port is occupied by a different database instance.'};if(Test-Path -LiteralPath (Join-Path $mysqlRoot 'bootstrap.pending')){& java -jar $tool setup $configPath $projectRoot;if($LASTEXITCODE -ne 0){throw 'Initial account setup failed.'}};Write-Output "Project MySQL already listens locally on $Port.";return}
if(!(Test-Path -LiteralPath (Join-Path $databaseRoot 'mysql'))){
 & $mysqld --no-defaults --initialize-insecure "--basedir=$((Get-Item $mysqld).Directory.Parent.FullName)" "--datadir=$databaseRoot" 2> (Join-Path $mysqlRoot 'initialize.log')
 if($LASTEXITCODE -ne 0){throw 'MySQL initialization failed; inspect data/mysql/initialize.log.'}
 Set-Content -LiteralPath (Join-Path $mysqlRoot 'bootstrap.pending') -Value 'New project instance awaiting initial root password.'
}
$arguments=@('--no-defaults','--standalone','--console',('--datadir="'+$databaseRoot+'"'),"--port=$Port",'--bind-address=127.0.0.1','--mysqlx=OFF','--character-set-server=utf8mb4','--collation-server=utf8mb4_bin')
$process=Start-Process $mysqld -ArgumentList $arguments -WorkingDirectory $mysqlRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $mysqlRoot 'server.out.log') -RedirectStandardError (Join-Path $mysqlRoot 'server.err.log')
@{pid=$process.Id;port=$Port;data_directory=$databaseRoot}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $mysqlRoot 'process.json')
for($i=0;$i -lt 45;$i++){if(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue){& java -jar $tool setup $configPath $projectRoot;if($LASTEXITCODE -ne 0){throw 'MySQL account setup failed.'};Write-Output "Project MySQL ready on 127.0.0.1:$Port.";return};if($process.HasExited){throw 'Project MySQL startup failed.'};Start-Sleep -Seconds 1}
throw 'Project MySQL did not become ready.'
