$ErrorActionPreference='Stop'
if(!$env:TEST_ADMIN_EMAIL -or !$env:TEST_ADMIN_PASSWORD){throw 'Set local test administrator credentials in the process environment.'}
$base='http://127.0.0.1:5173/identity/api/v1'
function Api($path,$token,$method='GET',$body=$null){$args=@{Uri=$base+$path;Method=$method;Headers=@{Authorization="Bearer $token"}};if($body){$args.ContentType='application/json';$args.Body=$body|ConvertTo-Json};(Invoke-RestMethod @args).data}
$admin=(Api '/auth/login' '' 'POST' @{email=$env:TEST_ADMIN_EMAIL;password=$env:TEST_ADMIN_PASSWORD}).access_token
$account=@{email="deploy-$([guid]::NewGuid())@example.test";password='Local-test-123';nickname='部署测试成员'}
Api '/auth/register' '' 'POST' $account|Out-Null
$user=(Api '/auth/login' '' 'POST' $account).access_token
foreach($visibility in @('PUBLIC','PRIVATE')){
 $r=Api '/community/group-requests' $user 'POST' @{name="本地部署验证 $visibility";description='独立数据库和进程验证';visibility=$visibility}
 try{Api "/community/group-requests/$($r.id)/deploy" $user 'POST'|Out-Null;throw 'Unapproved deployment allowed'}catch{if(!$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 409){throw}}
 Api "/admin/group-requests/$($r.id)" $admin 'PATCH' @{status='APPROVED'}|Out-Null
 try{Api "/community/group-requests/$($r.id)/deploy" $admin 'POST'|Out-Null;throw 'Non-owner deployment allowed'}catch{if(!$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 404){throw}}
 Api "/community/group-requests/$($r.id)/deploy" $user 'POST'|Out-Null
 $done=$false
 for($i=0;$i -lt 45;$i++){$current=Api '/community/group-requests' $user|Where-Object ID -eq $r.id;if($current.DEPLOYMENT_STATUS -eq 'FAILED'){throw $current.DEPLOYMENT_ERROR};if($current.DEPLOYMENT_STATUS -eq 'DEPLOYED'){$done=$true;break};Start-Sleep -Seconds 2}
 if(!$done){throw 'Deployment timed out'}
 try{Api "/community/group-requests/$($r.id)/deploy" $user 'POST'|Out-Null;throw 'Duplicate deployment accepted'}catch{if(!$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 409){throw}}
 $notice=Api '/notifications' $user|Where-Object REQUEST_ID -eq $r.id
 if($notice.BODY -notmatch '群组部署成功'){throw 'Deployment completion notification missing'}
 $group=Api '/community/groups' $user|Where-Object id -eq "local-$($r.id)"
 if(!$group){throw 'Deployed group missing from directory'}
 $url='http://127.0.0.1:5173'+$group.proxy_path+'/api/v1/group'
 $info=(Invoke-RestMethod $url).data;if($info.id -ne $group.id){throw 'Proxy reached wrong group'}
 $key='';if($visibility -eq 'PRIVATE'){$key=(Api "/community/group-requests/$($r.id)/join-key" $user).join_key}
 if($visibility -eq 'PRIVATE'){
  try{Api "/community/group-requests/$($r.id)/join-key" $admin|Out-Null;throw 'Private key exposed to non-owner'}catch{if(!$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 404){throw}}
  try{Invoke-RestMethod "$url/join" -Method Post -Headers @{Authorization="Bearer $user"} -ContentType 'application/json' -Body (@{join_key='wrong-key';nickname='测试';host_name='本机'}|ConvertTo-Json)|Out-Null;throw 'Wrong private key accepted'}catch{if(!$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 403){throw}}
 }
 Invoke-RestMethod "$url/join" -Method Post -Headers @{Authorization="Bearer $user"} -ContentType 'application/json' -Body (@{join_key=$key;nickname='部署测试成员';host_name='本机测试'}|ConvertTo-Json)|Out-Null
 $post=(Invoke-RestMethod "$url/discussions" -Method Post -Headers @{Authorization="Bearer $user"} -ContentType 'application/json' -Body (@{title='独立群组验证';body='真实服务已启动'}|ConvertTo-Json)).data
 if(!$post.id){throw 'Discussion write failed'}
 Write-Output "$visibility deployment, proxy, join and discussion passed: $($group.id)"
}
