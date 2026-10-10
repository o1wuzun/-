$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$keys=Get-Content -LiteralPath (Join-Path $projectRoot 'data/dev-keys.json') -Raw | ConvertFrom-Json
$demoEmail='demo-'+[Guid]::NewGuid().ToString('N').Substring(0,8)+'@example.com'
$credentials=@{email=$demoEmail;password='Local-demo-'+[Guid]::NewGuid().ToString('N');nickname='联调同伴'}
$root='http://127.0.0.1:5173'
Invoke-RestMethod "$root/identity/api/v1/auth/register" -Method Post -ContentType 'application/json' -Body ($credentials|ConvertTo-Json) | Out-Null
$login=Invoke-RestMethod "$root/identity/api/v1/auth/login" -Method Post -ContentType 'application/json' -Body ($credentials|ConvertTo-Json)
$headers=@{Authorization='Bearer '+$login.data.access_token}
$join=@{join_key=$keys.join_key;nickname=$credentials.nickname;host_name='本机联调设备'}
Invoke-RestMethod "$root/group/api/v1/group/join" -Method Post -Headers $headers -ContentType 'application/json' -Body ($join|ConvertTo-Json) | Out-Null
Invoke-RestMethod "$root/group/api/v1/group/heartbeat" -Method Post -Headers $headers | Out-Null
$content=@{title='Spring Boot + Vue 联调验证';body='统一身份登录、独立群组加入和讨论持久化已验证。'}
Invoke-RestMethod "$root/group/api/v1/group/discussions" -Method Post -Headers $headers -ContentType 'application/json' -Body ($content|ConvertTo-Json) | Out-Null
$posts=Invoke-RestMethod "$root/group/api/v1/group/discussions" -Headers $headers
if(!($posts.data | Where-Object { $_.title -eq $content.title -and $_.body -eq $content.body })){throw 'Discussion round-trip failed'}
$members=Invoke-RestMethod "$root/group/api/v1/group/members" -Headers $headers
if(!($members.data | Where-Object { $_.user_id -eq $login.data.user.id -and $_.online })){throw 'Presence synchronization failed'}
Write-Output 'PASS: Vue proxy -> identity login -> group join -> presence -> persisted discussion.'
Write-Output 'A clearly labeled demo account and discussion were created in the local development database.'
