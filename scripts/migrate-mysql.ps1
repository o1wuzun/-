$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$configPath=Join-Path $projectRoot 'data/mysql/project-config.json'
$tool=Join-Path $projectRoot 'services/database-tools/target/database-tools-0.1.0-SNAPSHOT.jar'
foreach($file in @($configPath,$tool)){if(!(Test-Path -LiteralPath $file)){throw 'Start project MySQL and build database-tools first.'}}
& (Join-Path $PSScriptRoot 'start-mysql.ps1')
& (Join-Path $PSScriptRoot 'stop-public.ps1')
& (Join-Path $PSScriptRoot 'stop-dev.ps1')
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$backup=Join-Path $projectRoot "data/h2-backups/$stamp"
New-Item -ItemType Directory -Force -Path $backup|Out-Null
foreach($file in @('identity.mv.db','group-default.mv.db','group-contest.mv.db')){Copy-Item -LiteralPath (Join-Path $projectRoot "data/$file") -Destination (Join-Path $backup $file)}
& java -jar $tool setup $configPath $projectRoot
if($LASTEXITCODE -ne 0){throw 'Project database setup failed.'}
& java -jar $tool migrate $configPath $projectRoot | Tee-Object -FilePath (Join-Path $projectRoot 'data/mysql/import-verification.log')
if($LASTEXITCODE -ne 0){throw 'Import failed. H2 originals and offline backups are retained; applications remain stopped.'}
@{database='mysql';verified_at=(Get-Date).ToString('o');h2_backup=$backup}|ConvertTo-Json|Set-Content -LiteralPath (Join-Path $projectRoot 'data/database-mode.json')
Write-Output 'Verified import complete. Subsequent start-dev/start-private uses MySQL by default. Public access remains disabled.'
