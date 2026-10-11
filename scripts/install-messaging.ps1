$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtime=Join-Path $projectRoot 'data/messaging'
$downloads=Join-Path $projectRoot 'data/downloads'
$alias=Join-Path ([System.IO.Path]::GetPathRoot($projectRoot)) 'community-mq'
New-Item -ItemType Directory -Force $runtime,$downloads|Out-Null
if(!(Test-Path -LiteralPath $alias)){New-Item -ItemType Junction -Path $alias -Target $runtime|Out-Null}
$link=Get-Item -LiteralPath $alias
if($link.LinkType -ne 'Junction' -or $link.Target -ne $runtime){throw 'Unexpected runtime alias.'}
if(!(Test-Path -LiteralPath (Join-Path $alias 'erlang/bin/erl.exe'))){
 $installer=Join-Path $downloads 'otp.exe'
 Invoke-WebRequest 'https://github.com/erlang/otp/releases/download/OTP-27.3.4.4/otp_win64_27.3.4.4.exe' -OutFile $installer
 $signature=Get-AuthenticodeSignature -LiteralPath $installer
 if($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'Ericsson AB'){throw 'Erlang installer signature verification failed.'}
 $process=Start-Process $installer -ArgumentList @('/S',('/D='+ (Join-Path $alias 'erlang'))) -WindowStyle Hidden -PassThru -Wait
 if($process.ExitCode -ne 0){throw 'Erlang installation failed.'}
}
if(!(Test-Path -LiteralPath (Join-Path $runtime 'rabbitmq_server-4.1.4/sbin/rabbitmq-server.bat'))){
 $archive=Join-Path $downloads 'rabbitmq.zip'
 Invoke-WebRequest 'https://github.com/rabbitmq/rabbitmq-server/releases/download/v4.1.4/rabbitmq-server-windows-4.1.4.zip' -OutFile $archive
 if((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne 'F32811C37F208F6A249ECE70A10BC133D0B2E7983BDCED7A697B83925F2EDD68'){throw 'RabbitMQ archive checksum mismatch.'}
 Expand-Archive -LiteralPath $archive -DestinationPath $runtime
}
Write-Output 'Project messaging runtime installed. No Windows broker service was registered.'
