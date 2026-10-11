$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolRoot=Join-Path $projectRoot 'data/tools'
New-Item -ItemType Directory -Force -Path $toolRoot | Out-Null
$tempPath=Join-Path $toolRoot 'cloudflared-download.exe'
$target=Join-Path $toolRoot 'cloudflared.exe'
try{
 Invoke-WebRequest 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe' -OutFile $tempPath
 $signature=Get-AuthenticodeSignature -FilePath $tempPath
 if($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O="?Cloudflare, Inc\.') {throw 'Cloudflare executable signature verification failed.'}
 Move-Item -LiteralPath $tempPath -Destination $target -Force
 Write-Output 'Official Cloudflare connector installed; publisher signature verified.'
}finally{if(Test-Path -LiteralPath $tempPath){Remove-Item -LiteralPath $tempPath}}
