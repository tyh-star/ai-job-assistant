# Reports whether the test credentials are in place. Presence and shape only: no value,
# no prefix, no length that would narrow a guess, is ever printed.
#
#   .\infra\mysql-test\check-env.ps1

[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$envFile = Join-Path $here '.env.mysql-test'

$required = @(
    'AIJOB_MYSQL_TEST_PORT',
    'AIJOB_MYSQL_TEST_ROOT_PASSWORD',
    'AIJOB_MYSQL_TEST_PASSWORD',
    'AIJOB_MYSQL_TEST_URL',
    'AIJOB_MYSQL_TEST_USERNAME'
)
# Names whose values must never be echoed, whatever else this script grows into.
$secret = @('AIJOB_MYSQL_TEST_ROOT_PASSWORD', 'AIJOB_MYSQL_TEST_PASSWORD')

if (-not (Test-Path $envFile)) {
    Write-Host "MISSING: $envFile"
    Write-Host "Run .\infra\mysql-test\new-credentials.ps1 first."
    exit 1
}

$present = @{}
foreach ($line in Get-Content $envFile) {
    $trimmed = $line.Trim()
    if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
    $name, $value = $trimmed -split '=', 2
    $present[$name.Trim()] = -not [string]::IsNullOrWhiteSpace($value)
}

Write-Host "Credentials file: $envFile"
$ok = $true
foreach ($name in $required) {
    if ($present.ContainsKey($name) -and $present[$name]) {
        if ($secret -contains $name) {
            Write-Host ("  {0,-34} SET (value not shown)" -f $name)
        } else {
            # Non-secret values are still not echoed here, so that this script never has
            # to decide case by case what is safe to print.
            Write-Host ("  {0,-34} SET" -f $name)
        }
    } else {
        Write-Host ("  {0,-34} MISSING" -f $name)
        $ok = $false
    }
}

Write-Host ""
Write-Host "File permissions:"
$acl = Get-Acl $envFile
$principals = @($acl.Access | ForEach-Object { $_.IdentityReference.Value } | Sort-Object -Unique)
Write-Host ("  owner      : {0}" -f $acl.Owner)
Write-Host ("  accessible : {0}" -f ($principals -join ', '))
if ($principals.Count -gt 1) {
    Write-Host "  WARNING: more than one principal can read this file."
    Write-Host "           Re-run new-credentials.ps1 -Force to reset the ACL."
}

Write-Host ""
$repoRoot = Resolve-Path (Join-Path $here '..\..')
& git -C $repoRoot check-ignore --quiet $envFile 2>$null
$ignored = ($LASTEXITCODE -eq 0)
if ($ignored) {
    Write-Host "Git: ignored (will not be committed)"
} else {
    Write-Host "Git: NOT IGNORED -- stop and fix .gitignore before committing anything"
    $ok = $false
}

if (-not $ok) { exit 1 }
Write-Host ""
Write-Host "Ready. Nothing above revealed a credential."
