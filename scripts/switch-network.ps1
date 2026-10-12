param(
 [ValidateSet('menu','lan','public','local','stop')][string]$Mode='menu',
 [ValidateSet('auto','h2','mysql')][string]$Database='auto',
 [switch]$NoBrowser,
 [switch]$Preview
)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $projectRoot
$statePath=Join-Path $projectRoot 'data/network-mode.json'
if($Mode -eq 'menu'){
 Write-Host ''
 Write-Host '算法同行 · 启动与切换'
 Write-Host '  1. 局域网：同一网络的电脑、手机访问'
 Write-Host '  2. 公网：启动中继、网关和 HTTPS 隧道'
 Write-Host '  3. 本机：仅当前电脑访问'
 Write-Host '  4. 停止社区及公网服务（保留数据库）'
 Write-Host '  0. 取消'
 $selection=Read-Host '请选择 0 / 1 / 2 / 3 / 4'
 $Mode=switch($selection){'1'{'lan'}'2'{'public'}'3'{'local'}'4'{'stop'}'0'{return}default{throw '请选择菜单中的数字。'}}
 if(!$Mode){return}
}
$webAddress=if($Mode -eq 'lan'){'0.0.0.0'}else{'127.0.0.1'}
if($Preview){
 Write-Output "Mode=$Mode Database=$Database Backend=127.0.0.1 Web=$webAddress"
 Write-Output 'Stop old public services -> Stop old community services'
 if($Mode -ne 'stop'){Write-Output 'Start database/community -> Wait for API and frontend readiness'}
 if($Mode -eq 'public'){Write-Output 'Start relay/connectors/gateway/HTTPS tunnel -> Show public URL and admin access policy'}
 Write-Output 'Preview only: no processes or settings changed.'
 return
}
function EnsureArtifacts {
 if($env:COMMUNITY_DESKTOP){return}
 foreach($command in @('java','node','npm')){Get-Command $command -ErrorAction Stop | Out-Null}
 $modules=@('identity-service','group-service','database-tools')
 if($Mode -eq 'public'){$modules+=@('relay-service','tunnel-client')}
 $missing=@($modules|Where-Object {!(Test-Path -LiteralPath (Join-Path $projectRoot "services/$_/target/$_-0.1.0-SNAPSHOT.jar"))})
 if($missing.Count){
  Get-Command mvn -ErrorAction Stop | Out-Null
  Write-Host '首次启动：正在构建后端…'
  $projects=($modules|ForEach-Object {"services/$_"}) -join ','
  & mvn "-Dmaven.repo.local=F:/social/.m2build/repository" -pl $projects -am package -DskipTests
  if($LASTEXITCODE -ne 0){throw '后端构建失败，现有服务未停止。'}
 }
 if(!(Test-Path -LiteralPath (Join-Path $projectRoot 'frontend/node_modules/vite/bin/vite.js'))){
  Write-Host '首次启动：正在安装前端依赖…'
  & npm --prefix frontend ci
  if($LASTEXITCODE -ne 0){throw '前端依赖安装失败，现有服务未停止。'}
 }
 if($Mode -eq 'public'){
  if(!(Test-Path -LiteralPath (Join-Path $projectRoot 'data/tools/cloudflared.exe'))){throw '缺少公网隧道程序 data/tools/cloudflared.exe，请先按公网部署说明安装。现有服务未停止。'}
  Write-Host '正在准备公网前端…'
  & npm --prefix frontend run build
  if($LASTEXITCODE -ne 0){throw '前端构建失败，现有服务未停止。'}
 }
}
function Ready($url){
 for($i=0;$i -lt 60;$i++){
  try{Invoke-WebRequest -Uri $url -TimeoutSec 2 | Out-Null;return}catch{Start-Sleep -Seconds 1}
 }
 throw "服务启动超时：$url，请查看 data 中对应的启动日志。"
}
if($Mode -ne 'stop'){EnsureArtifacts}
try{
 Write-Host '正在停止原有模式…'
 & (Join-Path $PSScriptRoot 'stop-public.ps1')
 & (Join-Path $PSScriptRoot 'stop-dev.ps1')
 New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'data') | Out-Null
 @{mode=$Mode;status='starting';updated_at=[DateTimeOffset]::Now.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
 if($Mode -eq 'stop'){
  @{mode='stop';status='stopped';updated_at=[DateTimeOffset]::Now.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
  Write-Host '社区和公网服务已停止，数据库与已有记录保留。'
  return
 }
 Write-Host '正在启动数据库和社区…'
 & (Join-Path $PSScriptRoot 'start-dev.ps1') -BindAddress '127.0.0.1' -WebBindAddress $webAddress -Database $Database
 Ready 'http://127.0.0.1:8081/api/v1/community/groups'
 Ready 'http://127.0.0.1:8082/api/v1/group'
 Ready 'http://127.0.0.1:8083/api/v1/group'
 Ready 'http://127.0.0.1:5173/'
 $url='http://127.0.0.1:5173/'
 if($Mode -eq 'public'){
  Write-Host '正在启动公网中继和隧道…'
  & (Join-Path $PSScriptRoot 'start-public.ps1')
  $url=(Get-Content -LiteralPath (Join-Path $projectRoot 'data/public-processes.json') -Raw | ConvertFrom-Json).url
  if(!$url){throw '公网隧道未返回可访问的地址。'}
  $key=(Get-Content -LiteralPath (Join-Path $projectRoot 'data/gateway-key.txt') -Raw).Trim()
  $policy=Invoke-RestMethod 'http://127.0.0.1:8081/api/v1/internal/public-access/check' -Method Post -Headers @{Authorization="Bearer $key"} -ContentType 'application/json' -Body '{"session":"","device":false}' -TimeoutSec 5
  if(!$policy.data.enabled){Write-Host '隧道已启动。请在本机管理后台开启“公网访问”，外部成员才能进入。' -ForegroundColor Yellow}
  Write-Host "公网地址：$url"
  Write-Host '临时公网地址可能在重启后变化；远程群组需要更新部署配置。'
 }elseif($Mode -eq 'lan'){
  Write-Host '局域网已启动，同伴使用下列地址：'
  $physicalIndexes=@(Get-NetAdapter -Physical -ErrorAction SilentlyContinue | Where-Object {$_.Status -eq 'Up'} | Select-Object -ExpandProperty ifIndex)
  $allAddresses=@(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object {$_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*' -and $_.AddressState -eq 'Preferred'})
  $preferred=@($allAddresses | Where-Object {$_.InterfaceIndex -in $physicalIndexes})
  if(!$preferred.Count){$preferred=$allAddresses}
  $addresses=@($preferred | Select-Object -ExpandProperty IPAddress -Unique)
  foreach($address in $addresses){Write-Host "  http://${address}:5173/"}
  Write-Host '选择主机当前 Wi-Fi 或有线网卡地址。同伴无法访问时检查私有网络防火墙的 5173 端口。'
 }
 @{mode=$Mode;status='ready';url=$url;web_bind=$webAddress;updated_at=[DateTimeOffset]::Now.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8
 Write-Host '本机地址：http://127.0.0.1:5173/'
 if(!$NoBrowser){Start-Process $url}
}catch{
 if(Test-Path -LiteralPath (Join-Path $projectRoot 'data')){@{mode=$Mode;status='failed';updated_at=[DateTimeOffset]::Now.ToString('o')} | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8}
 Write-Host '切换未完成。请在原来启动服务的终端重试；启动失败的日志位于 data 目录。' -ForegroundColor Yellow
 throw
}
