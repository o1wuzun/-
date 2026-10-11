param(
 [ValidateSet('public','private','stop-public','stop','update')][string]$Action='public',
 [switch]$Check,
 [Parameter(ValueFromRemainingArguments=$true)][string[]]$ScriptArguments
)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$command=Get-Command pwsh.exe -ErrorAction SilentlyContinue
$candidates=@()
if($command){$candidates+=$command.Source}
if($PSVersionTable.PSVersion.Major -ge 7){$candidates+=(Join-Path $PSHOME 'pwsh.exe')}
$candidates+=@(
 (Join-Path $env:ProgramFiles 'PowerShell\7\pwsh.exe'),
 (Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\native\powershell\pwsh.exe')
)
$shellPath=$candidates|Where-Object {Test-Path -LiteralPath $_ -PathType Leaf}|Select-Object -First 1
if(!$shellPath){throw 'PowerShell 7 was not found. Install it from https://aka.ms/powershell-release?tag=stable and run this launcher again.'}
if($Check){Write-Output "PowerShell 7: $shellPath";exit 0}
$scriptName=switch($Action){'public'{'start-public.ps1'}'private'{'start-private.ps1'}'stop-public'{'stop-public.ps1'}'stop'{'stop-dev.ps1'}'update'{'update-project.ps1'}}
Set-Location -LiteralPath $projectRoot
# The child scripts can discover the same runtime without a permanent PATH change.
$env:PATH=(Split-Path -Parent $shellPath)+';'+$env:PATH
& $shellPath -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot $scriptName) @ScriptArguments
exit $LASTEXITCODE
