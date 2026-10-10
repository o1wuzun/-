$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$keys=Get-Content -LiteralPath (Join-Path $projectRoot 'data/dev-keys.json') -Raw | ConvertFrom-Json
$root='http://127.0.0.1:5173'
$directory=Invoke-RestMethod "$root/identity/api/v1/community/groups"
if($directory.data.Count -ne 2){throw 'Expected two configured group services'}
$testId=[Guid]::NewGuid().ToString('N')
$account=@{email="isolation-$testId@example.com";password="Test-$testId";nickname='隔离验证同伴'}
Invoke-RestMethod "$root/identity/api/v1/auth/register" -Method Post -ContentType 'application/json' -Body ($account|ConvertTo-Json)|Out-Null
$login=Invoke-RestMethod "$root/identity/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body ($account|ConvertTo-Json)
$headers=@{Authorization='Bearer '+$login.data.access_token}
function ExpectDenied($url){
    try {Invoke-RestMethod $url -Headers $headers|Out-Null;throw 'Unexpected content access'}
    catch {if(!$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 404){throw}}
}
$first="$root/groups/group-default/api/v1/group"
$second="$root/groups/group-contest/api/v1/group"
ExpectDenied "$first/discussions"
ExpectDenied "$second/discussions"
$join=@{nickname=$account.nickname;host_name='隔离联调设备';join_key=$keys.join_key}
Invoke-RestMethod "$first/join" -Method Post -Headers $headers -ContentType 'application/json' -Body ($join|ConvertTo-Json)|Out-Null
$post=@{title="仅基础组可见-$testId";body='此讨论不可出现在竞赛组。'}
Invoke-RestMethod "$first/discussions" -Method Post -Headers $headers -ContentType 'application/json' -Body ($post|ConvertTo-Json)|Out-Null
ExpectDenied "$second/discussions"
# A key for group A must not admit the same account into group B.
try {Invoke-RestMethod "$second/join" -Method Post -Headers $headers -ContentType 'application/json' -Body ($join|ConvertTo-Json)|Out-Null;throw 'Wrong group key was accepted'}
catch {if(!$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 403){throw}}
$join.join_key=$keys.contest_join_key
Invoke-RestMethod "$second/join" -Method Post -Headers $headers -ContentType 'application/json' -Body ($join|ConvertTo-Json)|Out-Null
$b=Invoke-RestMethod "$second/discussions" -Headers $headers
if($b.data|Where-Object {$_.title -eq $post.title}){throw 'Group data leaked between databases'}
$a=Invoke-RestMethod "$first/discussions" -Headers $headers
if(!($a.data|Where-Object {$_.title -eq $post.title})){throw 'Group A data was not persisted'}
$node=Invoke-RestMethod "$first/nodes" -Method Post -Headers $headers -ContentType 'application/json' -Body (@{name='基础组隔离联调节点'}|ConvertTo-Json)
$nodeHeaders=@{'X-Node-Credential'=$node.data.credential}
$events=Invoke-RestMethod "$first/replication/events" -Headers $nodeHeaders
if($events.data.group_id -ne 'group-default'){throw 'Wrong replication group identity'}
try {Invoke-RestMethod "$second/replication/events" -Headers $nodeHeaders|Out-Null;throw 'Node credential leaked across groups'}
catch {if(!$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 401){throw}}
Invoke-RestMethod "$first/nodes/$($node.data.id)" -Method Delete -Headers $headers|Out-Null
try {Invoke-RestMethod "$first/replication/events" -Headers $nodeHeaders|Out-Null;throw 'Revoked node continued reading'}
catch {if(!$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 401){throw}}
Write-Output 'PASS: one identity, two service processes, separate memberships, independent invitation keys and isolated discussion databases.'
Write-Output 'PASS: node credentials are group-bound and revocation blocks event reads.'
