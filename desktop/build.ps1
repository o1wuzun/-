param([string]$Jdk='C:\Program Files\Java\jdk-24',[string]$PowerShellRuntime='C:\Users\ASUS\.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell')
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$build=Join-Path $root 'desktop/build';$release=Join-Path $root 'releases'
if(Test-Path -LiteralPath $build){throw 'Build directory exists. Use a clean desktop/build directory for reproducibility.'}
New-Item -ItemType Directory -Force -Path $build,$release | Out-Null
$runtimeZip=Join-Path $build 'jre.zip'
Invoke-WebRequest 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse' -OutFile $runtimeZip
Expand-Archive -LiteralPath $runtimeZip -DestinationPath (Join-Path $build 'runtime')
$runtime=(Get-ChildItem (Join-Path $build 'runtime') -Directory|Select-Object -First 1).FullName
$inputRoot=Join-Path $build 'input';$payload=Join-Path $inputRoot 'payload';$tools=Join-Path $inputRoot 'tools'
New-Item -ItemType Directory -Force -Path $inputRoot,$payload,$tools,(Join-Path $build 'classes') | Out-Null
& (Join-Path $Jdk 'bin/javac.exe') --release 21 -encoding UTF-8 -d (Join-Path $build 'classes') (Join-Path $PSScriptRoot 'Desktop.java')
if($LASTEXITCODE){throw 'Desktop compilation failed.'}
& (Join-Path $Jdk 'bin/jar.exe') --create --file (Join-Path $inputRoot 'desktop.jar') --main-class Desktop -C (Join-Path $build 'classes') .
Copy-Item -LiteralPath (Join-Path $root 'scripts') -Destination $payload -Recurse
New-Item -ItemType Directory -Force -Path (Join-Path $payload 'desktop'),(Join-Path $payload 'frontend') | Out-Null
Copy-Item (Join-Path $PSScriptRoot 'run.ps1'),(Join-Path $PSScriptRoot 'web-server.mjs') (Join-Path $payload 'desktop')
Copy-Item -LiteralPath (Join-Path $root 'frontend/dist') -Destination (Join-Path $payload 'frontend') -Recurse
foreach($module in @('identity-service','group-service','database-tools','relay-service','tunnel-client','replica-service')){
 $dest=Join-Path $payload "services/$module/target";New-Item -ItemType Directory -Force -Path $dest|Out-Null
 Copy-Item -LiteralPath (Join-Path $root "services/$module/target/$module-0.1.0-SNAPSHOT.jar") -Destination $dest
 if($module -in @('identity-service','group-service')){
  $migrations=Join-Path $payload "services/$module/src/main/resources/db/migration";New-Item -ItemType Directory -Force -Path $migrations|Out-Null
  Copy-Item -LiteralPath (Join-Path $root "services/$module/src/main/resources/db/migration/mysql") -Destination $migrations -Recurse
 }
}
Copy-Item -LiteralPath $PowerShellRuntime -Destination (Join-Path $tools 'pwsh') -Recurse
New-Item -ItemType Directory -Force -Path (Join-Path $tools 'node'),(Join-Path $tools 'mysql')|Out-Null
Copy-Item -LiteralPath (Get-Command node).Source -Destination (Join-Path $tools 'node/node.exe')
$mysql=(Get-Item (Get-Command mysqld).Source).Directory.Parent.FullName
foreach($part in @('bin','lib','share','LICENSE','README')){Copy-Item -LiteralPath (Join-Path $mysql $part) -Destination (Join-Path $tools 'mysql') -Recurse}
Copy-Item -LiteralPath (Join-Path $root 'data/tools/cloudflared.exe') -Destination $tools
$licenses=Join-Path $tools 'licenses';New-Item -ItemType Directory -Force -Path $licenses|Out-Null
Invoke-WebRequest 'https://raw.githubusercontent.com/nodejs/node/main/LICENSE' -OutFile (Join-Path $licenses 'Node-LICENSE.txt')
Invoke-WebRequest 'https://raw.githubusercontent.com/cloudflare/cloudflared/master/LICENSE' -OutFile (Join-Path $licenses 'cloudflared-LICENSE.txt')
Invoke-WebRequest 'https://raw.githubusercontent.com/PowerShell/PowerShell/master/LICENSE.txt' -OutFile (Join-Path $licenses 'PowerShell-LICENSE.txt')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'THIRD-PARTY.md') -Destination $inputRoot
Copy-Item -LiteralPath (Join-Path $root 'LICENSE') -Destination $inputRoot
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'README.md') -Destination (Join-Path $inputRoot 'USER-GUIDE.md')
& (Join-Path $Jdk 'bin/jpackage.exe') --type app-image --name AlgoTogetherDemo --app-version 0.1.0 --vendor AlgoTogether --input $inputRoot --main-jar desktop.jar --main-class Desktop --runtime-image $runtime --dest $release
if($LASTEXITCODE){throw 'EXE packaging failed.'}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'README.md') -Destination (Join-Path $release 'AlgoTogetherDemo/USER-GUIDE.md')
Compress-Archive -Path (Join-Path $release 'AlgoTogetherDemo') -DestinationPath (Join-Path $release 'AlgoTogetherDemo-0.1.0-win-x64.zip') -CompressionLevel Optimal
Get-FileHash (Join-Path $release 'AlgoTogetherDemo-0.1.0-win-x64.zip') -Algorithm SHA256 | Format-List
