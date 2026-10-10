param([int]$MockPort=18765)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$keys=Get-Content -LiteralPath (Join-Path $projectRoot 'data/dev-keys.json') -Raw | ConvertFrom-Json
$root='http://127.0.0.1:5173'
$first="$root/groups/group-default/api/v1/group"
$second="$root/groups/group-contest/api/v1/group"
function CallApi($url,$headers,$method='GET',$body=$null){
    $args=@{Uri=$url;Headers=$headers;Method=$method}
    if($null -ne $body){$args.ContentType='application/json';$args.Body=($body|ConvertTo-Json -Depth 8)}
    (Invoke-RestMethod @args).data
}
function Denied($url,$headers,$code,$method='GET',$body=$null){
    try {CallApi $url $headers $method $body|Out-Null;throw 'Unexpected successful access'}
    catch {if(!$_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne $code){throw}}
}
if(Get-NetTCPConnection -LocalPort $MockPort -State Listen -ErrorAction SilentlyContinue){throw 'Mock port is occupied'}
$mock=Start-Process python -ArgumentList @('-u',('"'+(Join-Path $PSScriptRoot 'mock-advisor.py')+'"'),$MockPort) -WindowStyle Hidden -PassThru
try {
    $ready=$false
    for($i=0;$i -lt 20;$i++){try{Invoke-WebRequest "http://127.0.0.1:$MockPort/"|Out-Null;$ready=$true;break}catch{Start-Sleep -Milliseconds 250}}
    if(!$ready){throw 'Mock model did not start'}
    $accounts=@()
    for($i=0;$i -lt 2;$i++){
        $id=[Guid]::NewGuid().ToString('N')
        $account=@{email="scenario-$id@example.com";password="Local-test-$id";nickname="交流联调成员$i"}
        CallApi "$root/identity/api/v1/auth/register" @{} 'POST' $account|Out-Null
        $login=CallApi "$root/identity/api/v1/auth/login" @{} 'POST' $account
        $accounts+=,@{Authorization='Bearer '+$login.access_token}
        CallApi "$first/join" $accounts[$i] 'POST' @{join_key=$keys.join_key;nickname=$account.nickname;host_name='场景测试设备'}|Out-Null
    }
    $a=$accounts[0];$b=$accounts[1]
    $post=CallApi "$first/discussions" $a 'POST' @{title='双成员交流场景';body='怎样定义二分不变量？'}
    CallApi "$first/discussions/$($post.id)/comments" $b 'POST' @{body='先明确搜索区间，再定义循环不变量。'}|Out-Null
    $comments=CallApi "$first/discussions/$($post.id)/comments" $a
    if(@($comments).Count -ne 1 -or $comments[0].body -notmatch '循环不变量'){throw 'Two-member reply failed'}
    CallApi "$second/join" $a 'POST' @{join_key=$keys.contest_join_key;nickname='跨组联调成员';host_name='场景测试设备'}|Out-Null
    Denied "$second/discussions/$($post.id)/comments" $a 404
    $settings=@{advisor_provider='local-test-model';advisor_api_key='local-protocol-test';advisor_endpoint="http://127.0.0.1:$MockPort/v1/chat/completions"}
    $profile=CallApi "$root/identity/api/v1/users/me/integrations" $a 'PUT' $settings
    if($profile.integrations.advisor_api_key){throw 'Key returned in profile'}
    $question=@{group_id='group-default';question='请解释循环不变量';consent='share-group-context'}
    $answer=CallApi "$root/identity/api/v1/advisor/messages" $a 'POST' $question
    if($answer.answer -notmatch '历史轮数=0'){throw 'Initial advisor request failed'}
    $question.question='继续解释边界条件'
    $answer=CallApi "$root/identity/api/v1/advisor/messages" $a 'POST' $question
    if($answer.answer -notmatch '历史轮数=1'){throw 'Follow-up did not include history'}
    $other=CallApi "$root/identity/api/v1/advisor/history?group_id=group-default" $b
    if(@($other).Count -ne 0){throw 'Private history leaked to another member'}
    $other=CallApi "$root/identity/api/v1/advisor/history?group_id=group-contest" $a
    if(@($other).Count -ne 0){throw 'History crossed group boundary'}
    Denied "$root/identity/api/v1/advisor/history?group_id=group-contest" $b 403
    CallApi "$root/identity/api/v1/advisor/history?group_id=group-default" $a 'DELETE'|Out-Null
    $history=CallApi "$root/identity/api/v1/advisor/history?group_id=group-default" $a
    if(@($history).Count -ne 0){throw 'Clear history failed'}
    Write-Output 'PASS: two-member discussion and reply; cross-group discussion isolation.'
    Write-Output 'PASS: configured model endpoint; follow-up history; account/group history isolation; clear history.'
    Write-Output 'Model responses used a clearly labelled local fixture; no external model was called.'
} finally {
    if(!$mock.HasExited){Stop-Process -Id $mock.Id}
}
