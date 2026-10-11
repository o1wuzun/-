$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$statePath=Join-Path $projectRoot 'data/mysql/process.json'
if(!(Test-Path -LiteralPath $statePath)){Write-Output 'No project MySQL process record.';return}
$state=Get-Content -LiteralPath $statePath -Raw|ConvertFrom-Json
$listener=Get-NetTCPConnection -LocalPort $state.port -State Listen -ErrorAction SilentlyContinue
if(!$listener){Write-Output 'Project MySQL already stopped.';return}
$proc=Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
if(!$proc.CommandLine -or !$proc.CommandLine.Contains($state.data_directory)){throw 'Refusing to shut down an unrelated database instance.'}
$config=Join-Path $projectRoot 'data/mysql/project-config.json'
$tool=Join-Path $projectRoot 'services/database-tools/target/database-tools-0.1.0-SNAPSHOT.jar'
& java -jar $tool shutdown $config $projectRoot
if($LASTEXITCODE -ne 0){throw 'Graceful database shutdown failed.'}
Write-Output 'Project MySQL shut down gracefully; original system MySQL was not changed.'
