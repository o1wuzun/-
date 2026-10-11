param([string]$BindAddress='127.0.0.1',[ValidateSet('auto','h2','mysql')][string]$Database='auto')
$ErrorActionPreference='Stop'
& (Join-Path $PSScriptRoot 'stop-public.ps1')
& (Join-Path $PSScriptRoot 'stop-dev.ps1')
& (Join-Path $PSScriptRoot 'start-dev.ps1') -BindAddress $BindAddress -Database $Database
Write-Output 'Private mode: no public tunnel. Only the selected local/LAN interface is bound.'
