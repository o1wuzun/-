param([int]$RelayPort=18095,[int]$TunnelPort=18096,[int]$SecondTunnelPort=18097)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$data=Join-Path $root 'data'
$keys=Get-Content -LiteralPath (Join-Path $data 'dev-keys.json') -Raw|ConvertFrom-Json
$processes=[System.Collections.Generic.List[System.Diagnostics.Process]]::new()
foreach($port in @($RelayPort,$TunnelPort,$SecondTunnelPort)){if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){throw "Test port $port occupied"}}
function ApiCall($url,$headers=@{},$method='GET',$body=$null){$args=@{Uri=$url;Headers=$headers;Method=$method;TimeoutSec=25};if($null -ne $body){$args.ContentType='application/json';$args.Body=$body|ConvertTo-Json -Depth 8};(Invoke-RestMethod @args).data}
function Denied($url,$headers,$code,$method='GET',$body=$null){try{ApiCall $url $headers $method $body|Out-Null;throw 'Unexpected access'}catch{if(!$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne $code){throw}}}
function Launch($module,$name){
 $jar=Join-Path $root "services/$module/target/$module-0.1.0-SNAPSHOT.jar"
 $p=Start-Process java -ArgumentList @('-jar',('"'+$jar+'"')) -WorkingDirectory $root -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $data "$name.out.log") -RedirectStandardError (Join-Path $data "$name.err.log")
 $processes.Add($p);return $p
}
function StartFirst {
 $env:TUNNEL_GROUP_ID='group-default';$env:TUNNEL_CREDENTIAL=$firstKey;$env:TUNNEL_SOURCE_URL='http://127.0.0.1:8082';$env:TUNNEL_PORT=$TunnelPort
 Launch 'tunnel-client' 'relay-test-first'
}
function StopListener($port){
 foreach($connection in @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)){
  $info=Get-CimInstance Win32_Process -Filter "ProcessId=$($connection.OwningProcess)"
  if(!$info.CommandLine -or !$info.CommandLine.Contains($root) -or $info.CommandLine -notmatch '(relay-service|tunnel-client)-0\.1\.0-SNAPSHOT\.jar'){throw "Refusing to stop unrelated process on port $port"}
  Stop-Process -Id $connection.OwningProcess -ErrorAction SilentlyContinue
 }
}
$firstKey=[Guid]::NewGuid().ToString('N');$secondKey=[Guid]::NewGuid().ToString('N')
$relay="http://127.0.0.1:$RelayPort";$first="$relay/groups/group-default/api/v1/group";$second="$relay/groups/group-contest/api/v1/group"
try {
 $env:RELAY_PORT=$RelayPort;$env:SERVER_ADDRESS='127.0.0.1';$env:RELAY_CREDENTIALS=@{'group-default'=$firstKey;'group-contest'=$secondKey}|ConvertTo-Json -Compress
 Launch 'relay-service' 'relay-test-server'|Out-Null
 $ready=$false
 for($i=0;$i -lt 30;$i++){try{ApiCall "$relay/api/v1/relay/group-default/status"|Out-Null;$ready=$true;break}catch{Start-Sleep -Seconds 1}}
 if(!$ready){throw 'Relay startup failed'}
 Denied $first @{} 503
 Denied "$relay/api/v1/tunnel/group-contest/poll" @{Authorization="Bearer $firstKey"} 401
 $env:TUNNEL_RELAY_URL=$relay
 $connector=StartFirst
 $env:TUNNEL_GROUP_ID='group-contest';$env:TUNNEL_CREDENTIAL=$secondKey;$env:TUNNEL_SOURCE_URL='http://127.0.0.1:8083';$env:TUNNEL_PORT=$SecondTunnelPort
 Launch 'tunnel-client' 'relay-test-second'|Out-Null
 $online=$false
 for($i=0;$i -lt 30;$i++){if((ApiCall "$relay/api/v1/relay/group-default/status").online -and (ApiCall "$relay/api/v1/relay/group-contest/status").online){$online=$true;break};Start-Sleep -Seconds 1}
 if(!$online){throw 'Tunnel connectors did not come online'}
 if((ApiCall $first).id -ne 'group-default'){throw 'Wrong source group identity'}
 Denied "$first/discussions" @{} 401
 $id=[Guid]::NewGuid().ToString('N');$account=@{email="relay-$id@example.com";password="Relay-test-$id";nickname='中继场景成员'}
 ApiCall 'http://127.0.0.1:8081/api/v1/auth/register' @{} 'POST' $account|Out-Null
 $login=ApiCall 'http://127.0.0.1:8081/api/v1/auth/login' @{} 'POST' $account;$h=@{Authorization='Bearer '+$login.access_token}
 ApiCall "$first/join" $h 'POST' @{join_key=$keys.join_key;nickname=$account.nickname;host_name='仅出站连接主机'}|Out-Null
 ApiCall "$second/join" $h 'POST' @{join_key=$keys.contest_join_key;nickname=$account.nickname;host_name='第二群组主机'}|Out-Null
 $post=ApiCall "$first/discussions" $h 'POST' @{title="中继讨论-$id";body='此请求通过主机主动连接回传'}
 ApiCall "$first/discussions/$($post.id)/comments" $h 'POST' @{body='通过中继发送回复'}|Out-Null
 $comments=ApiCall "$first/discussions/$($post.id)/comments" $h
 if(@($comments).Count -ne 1 -or $comments[0].body -ne '通过中继发送回复'){throw 'Relay reply round-trip failed'}
 Denied "$second/discussions/$($post.id)/comments" $h 404
 $node=ApiCall "$first/nodes" $h 'POST' @{name='中继复制测试节点'};$nh=@{'X-Node-Credential'=$node.credential}
 $events=ApiCall "$first/replication/events?after=0" $nh
 if($events.group_id -ne 'group-default'){throw 'Relay replication identity mismatch'}
 Denied "$second/replication/events" $nh 401
 ApiCall "$first/nodes/$($node.id)" $h 'DELETE'|Out-Null
 Denied "$first/replication/events" $nh 401
 StopListener $TunnelPort
 if(!$connector.HasExited){Stop-Process -Id $connector.Id}
 $offline=$false
 for($i=0;$i -lt 40;$i++){if(!(ApiCall "$relay/api/v1/relay/group-default/status").online){$offline=$true;break};Start-Sleep -Seconds 1}
 if(!$offline){throw 'Disconnected host was not marked offline'}
 Denied $first @{} 503
 $connector=StartFirst
 $online=$false
 for($i=0;$i -lt 30;$i++){if((ApiCall "$relay/api/v1/relay/group-default/status").online){$online=$true;break};Start-Sleep -Seconds 1}
 if(!$online){throw 'Connector did not recover'}
 $comments=ApiCall "$first/discussions/$($post.id)/comments" $h
 if(@($comments).Count -ne 1){throw 'Data lost after reconnect'}
 Write-Output 'PASS: outbound tunnel transports group identity, membership, discussion, replies and node events.'
 Write-Output 'PASS: tunnel credentials and node credentials remain group-bound; offline and reconnect preserve content.'
 Write-Output 'This is a local transport integration test, not a real cross-Internet deployment test.'
}finally{foreach($port in @($TunnelPort,$SecondTunnelPort,$RelayPort)){StopListener $port};foreach($process in $processes){if(!$process.HasExited){Stop-Process -Id $process.Id -ErrorAction SilentlyContinue}}}
