param([string]$BaseUrl,[string]$InviteToken)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if(!$BaseUrl){$BaseUrl=(Get-Content (Join-Path $projectRoot 'data/public-processes.json') -Raw|ConvertFrom-Json).url}
if(!$BaseUrl -or ([Uri]$BaseUrl).Scheme -ne 'https'){throw 'Provide the public HTTPS URL.'}
$BaseUrl=$BaseUrl.TrimEnd('/')
$accessCookie='' # Public entry no longer requires an invitation.
function Call($route,$headers=@{},$method='GET',$body=$null){
 $merged=@{Cookie=$accessCookie};foreach($key in $headers.Keys){$merged[$key]=$headers[$key]}
 $params=@{Uri=$BaseUrl+$route;Method=$method;Headers=$merged;TimeoutSec=45}
 if($null -ne $body){$params.ContentType='application/json';$params.Body=$body|ConvertTo-Json -Depth 6}
 (Invoke-RestMethod @params).data
}
function Denied($route,$code,$headers=@{}){try{Call $route $headers|Out-Null;throw 'Unexpected access'}catch{if(!$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne $code){throw}}}
$page=Invoke-WebRequest "$BaseUrl/" -Headers @{Cookie=$accessCookie} -TimeoutSec 30
if($page.StatusCode -ne 200 -or $page.Content -notmatch '/assets/'){throw 'Public built frontend failed'}
$catalog=Call '/identity/api/v1/community/groups'
if(@($catalog).Count -lt 2){throw 'Public group directory failed'}
Denied '/data/dev-keys.json' 404
Denied '/.env' 404
Denied '/groups/group-default/api/v1/group/discussions' 401
$keys=Get-Content (Join-Path $projectRoot 'data/dev-keys.json') -Raw|ConvertFrom-Json
$users=@()
foreach($i in @(1,2)){
 $id=[Guid]::NewGuid().ToString('N')
 $account=@{email="public-$id@example.com";password="Public-$id";nickname="公网测试成员$i"}
 Call '/identity/api/v1/auth/register' @{} 'POST' $account|Out-Null
 $login=Call '/identity/api/v1/auth/login' @{} 'POST' $account
 $h=@{Authorization='Bearer '+$login.access_token}
 Call '/groups/group-default/api/v1/group/join' $h 'POST' @{join_key=$keys.join_key;nickname=$account.nickname;host_name='公网场景测试'}|Out-Null
 $users+=$h
}
$group='/groups/group-default/api/v1/group'
$post=Call "$group/discussions" $users[0] 'POST' @{title='公网穿透验收-'+[Guid]::NewGuid().ToString('N');body='经公网 HTTPS 入口发送的双用户测试'}
Call "$group/discussions/$($post.id)/comments" $users[1] 'POST' @{body='第二位成员经公网回复'}|Out-Null
$comments=Call "$group/discussions/$($post.id)/comments" $users[0]
if(@($comments).Count -ne 1 -or $comments[0].body -ne '第二位成员经公网回复'){throw 'Public discussion/reply round-trip failed'}
Denied '/groups/group-contest/api/v1/group/discussions' 404 $users[0]
Write-Output 'PASS: public HTTPS frontend, directory, registration, login, join, two-user discussion/reply and access boundaries.'
Write-Output 'Requests used the real public edge from this PC. Independent mobile-network acceptance is still required.'
