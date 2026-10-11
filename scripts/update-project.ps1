$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $root
& (Join-Path $PSScriptRoot 'stop-public.ps1')
& (Join-Path $PSScriptRoot 'stop-dev.ps1')
$pending=Join-Path $root 'data/pending-update'
$modules=@('identity-service','group-service','relay-service','tunnel-client','database-tools')
if((Test-Path -LiteralPath $pending) -and @($modules|Where-Object {!(Test-Path -LiteralPath (Join-Path $pending "$_.jar"))}).Count -eq 0){
 foreach($module in $modules){Copy-Item -LiteralPath (Join-Path $pending "$module.jar") -Destination (Join-Path $root "services/$module/target/$module-0.1.0-SNAPSHOT.jar") -Force}
 foreach($module in $modules){Remove-Item -LiteralPath (Join-Path $pending "$module.jar")}
}else{
 & mvn "-Dmaven.repo.local=F:/social/.m2build/repository" -pl services/identity-service,services/group-service,services/relay-service,services/tunnel-client,services/database-tools -am package -DskipTests
 if($LASTEXITCODE -ne 0){throw 'Backend build failed.'}
}
& npm --prefix frontend run build
if($LASTEXITCODE -ne 0){throw 'Frontend build failed.'}
& (Join-Path $PSScriptRoot 'start-dev.ps1')
Write-Output 'Update installed. Community is running locally. Run start-public.cmd to reopen the public tunnel.'
