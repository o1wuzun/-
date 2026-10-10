$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dataRoot = Join-Path $projectRoot 'data'
New-Item -ItemType Directory -Force -Path $dataRoot | Out-Null
$keysPath = Join-Path $dataRoot 'dev-keys.json'
if (!(Test-Path -LiteralPath $keysPath)) {
    $rsa = [System.Security.Cryptography.RSA]::Create(2048)
    $keyBytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($keyBytes)
    @{ public_key = [Convert]::ToBase64String($rsa.ExportSubjectPublicKeyInfo()); private_key = [Convert]::ToBase64String($rsa.ExportPkcs8PrivateKey()); join_key = [Convert]::ToBase64String($keyBytes) } | ConvertTo-Json | Set-Content -LiteralPath $keysPath
    $rsa.Dispose()
}
$keys = Get-Content -Raw -LiteralPath $keysPath | ConvertFrom-Json
$env:IDENTITY_PUBLIC_KEY = $keys.public_key
$env:IDENTITY_PRIVATE_KEY = $keys.private_key
$env:GROUP_JOIN_KEY = $keys.join_key
foreach ($port in @(8081,8082,5173)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "Port $port is occupied. Stop the previous development instance first." }
}
$identityJar = Join-Path $projectRoot 'services/identity-service/target/identity-service-0.1.0-SNAPSHOT.jar'
$groupJar = Join-Path $projectRoot 'services/group-service/target/group-service-0.1.0-SNAPSHOT.jar'
$viteScript = Join-Path $projectRoot 'frontend/node_modules/vite/bin/vite.js'
foreach($file in @($identityJar,$groupJar,$viteScript)) {if(!(Test-Path -LiteralPath $file)){throw "Missing $file. Build the services and install frontend dependencies first."}}
$env:IDENTITY_DB_URL = 'jdbc:h2:file:./data/identity'
$env:GROUP_DB_URL = 'jdbc:h2:file:./data/group-default'
$identity = Start-Process java -ArgumentList @('-jar',('"'+$identityJar+'"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $dataRoot 'identity.out.log') -RedirectStandardError (Join-Path $dataRoot 'identity.err.log')
# Private signing material belongs exclusively to the identity process.
Remove-Item Env:IDENTITY_PRIVATE_KEY
$group = Start-Process java -ArgumentList @('-jar',('"'+$groupJar+'"')) -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $dataRoot 'group.out.log') -RedirectStandardError (Join-Path $dataRoot 'group.err.log')
$web = Start-Process node -ArgumentList @(('"'+$viteScript+'"'),'--host','127.0.0.1') -WorkingDirectory (Join-Path $projectRoot 'frontend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $dataRoot 'web.out.log') -RedirectStandardError (Join-Path $dataRoot 'web.err.log')
@{identity=$identity.Id;group=$group.Id;web=$web.Id} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $dataRoot 'dev-processes.json')
Write-Output 'Development processes launched. Frontend: http://127.0.0.1:5173'
Write-Output 'Local-only development invite key: data/dev-keys.json (join_key). Do not publish this file.'
