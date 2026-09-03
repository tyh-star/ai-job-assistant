# Generates the pilot database's credentials. Separate file, separate accounts, separate
# schema from the test database.
#
#   .\infra\pilot-db\new-pilot-credentials.ps1
#   .\infra\pilot-db\new-pilot-credentials.ps1 -Force
#
# WHY NOT REUSE .env.mysql-test
# -----------------------------
# That file's account owns aijob_import_test, which the integration suite TRUNCATES on
# every run. Pointing pilot collection at those credentials is what destroyed 1379 real
# postings once already. Two files, two accounts, two schemas -- and the pilot config is
# never a fallback for a missing test config, nor the other way round.
#
# TWO ACCOUNTS, because migrating and importing are different jobs:
#   aijob_pilot        SELECT/INSERT/UPDATE/DELETE on the pilot schema. No DDL. This is
#                      what the pipeline runs as, every day, unattended.
#   aijob_pilot_admin  DDL as well, used by hand to create and migrate the schema. Its
#                      password is written here but never handed to the pipeline.
#
# Passwords are generated locally with a cryptographic RNG, never printed, never passed
# as an argument, and the file is git-ignored with an ACL naming only the current user.
#
# Windows PowerShell 5.1 (.NET Framework 4.8): RandomNumberGenerator.Create() plus the
# instance GetBytes, because the static Fill() does not exist there.

[CmdletBinding()]
param([switch]$Force)

$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$envFile = Join-Path $here '.env.pilot-db'

if ((Test-Path $envFile) -and -not $Force) {
    Write-Host "Pilot credentials already exist at $envFile"
    Write-Host "Nothing was changed. -Force replaces them, which invalidates the accounts in"
    Write-Host "MySQL until you re-run init-pilot-schema.ps1."
    exit 0
}

function New-Password {
    param([Parameter(Mandatory = $true)][System.Security.Cryptography.RandomNumberGenerator]$Rng,
          [int]$Bytes = 24)
    $buffer = New-Object byte[] $Bytes
    $Rng.GetBytes($buffer)
    # base64url: nothing a .env parser, a YAML interpolation or a shell would reinterpret.
    [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    $pilotPassword = New-Password -Rng $rng
    $adminPassword = New-Password -Rng $rng
}
finally { $rng.Dispose() }

$port = 3307
$lines = @(
    '# Generated locally by infra/pilot-db/new-pilot-credentials.ps1. NOT for version control.',
    '# Scope: the aijob_catalog_pilot schema on the isolated MySQL 8.4 test container.',
    '# These grant nothing in aijob_import_test, and nothing outside this container.',
    '',
    '# --- the pipeline account: data only, no DDL ---',
    "AIJOB_PILOT_DB_URL=jdbc:mysql://127.0.0.1:$port/aijob_catalog_pilot?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
    'AIJOB_PILOT_DB_USERNAME=aijob_pilot',
    "AIJOB_PILOT_DB_PASSWORD=$pilotPassword",
    '',
    '# The same account reached from inside the test network, where 127.0.0.1 is the',
    '# CONTAINER''S own loopback and not the host''s published port.',
    "AIJOB_PILOT_DB_URL_INNETWORK=jdbc:mysql://mysql-test:3306/aijob_catalog_pilot?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
    '',
    '# --- the migration account: DDL, used by hand, never given to the pipeline ---',
    'AIJOB_PILOT_ADMIN_USERNAME=aijob_pilot_admin',
    "AIJOB_PILOT_ADMIN_PASSWORD=$adminPassword",
    '',
    'AIJOB_PILOT_SCHEMA=aijob_catalog_pilot'
)
[System.IO.File]::WriteAllText($envFile, (($lines -join "`n") + "`n"),
    (New-Object System.Text.UTF8Encoding($false)))

# chmod 600's equivalent: inheritance off, one principal. GetAccessControl/SetAccessControl
# scoped to the Access section -- Get-Acl/Set-Acl would try to write the audit section too
# and fail without SeSecurityPrivilege.
$me = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$fileInfo = New-Object System.IO.FileInfo($envFile)
$security = $fileInfo.GetAccessControl([System.Security.AccessControl.AccessControlSections]::Access)
$security.SetAccessRuleProtection($true, $false)
foreach ($rule in @($security.Access)) { [void]$security.RemoveAccessRule($rule) }
$security.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule(
    $me, 'FullControl', 'None', 'None', 'Allow')))
$fileInfo.SetAccessControl($security)

$pilotPassword = $null
$adminPassword = $null

Write-Host "Wrote $envFile"
Write-Host "Readable by: $me only"
Write-Host "Two independent passwords generated (pipeline, migration). Neither was printed."
Write-Host ""
Write-Host "Next: .\infra\pilot-db\init-pilot-schema.ps1"
