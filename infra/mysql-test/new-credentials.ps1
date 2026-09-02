# Generates the test container's two passwords and writes them to a local, git-ignored,
# user-only file. Run it once before starting the container.
#
# What it deliberately does NOT do:
#   * print either password, or any part of one
#   * accept a password as a parameter (parameters land in the shell's history and, on
#     Windows, in the process command line other users can read)
#   * touch any existing database, credential or .env file
#
# The two passwords are generated separately. Reusing one value for root and for the
# test account would mean the scratch account's password is also the server's.
#
#   .\infra\mysql-test\new-credentials.ps1
#   .\infra\mysql-test\new-credentials.ps1 -Force   # replace an existing file

[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$envFile = Join-Path $here '.env.mysql-test'

if ((Test-Path $envFile) -and -not $Force) {
    Write-Host "Credentials already exist at $envFile"
    Write-Host "Nothing was changed. Re-run with -Force to replace them (this invalidates the"
    Write-Host "running container's account: recreate the container afterwards)."
    exit 0
}

function New-Password {
    param(
        [Parameter(Mandatory = $true)]
        [System.Security.Cryptography.RandomNumberGenerator]$Rng,
        [int]$Bytes = 24
    )
    # A cryptographic RNG, never Get-Random (seeded predictably enough to matter), never a
    # timestamp, never a fixed value.
    #
    # The instance GetBytes, not the static RandomNumberGenerator.Fill: Fill arrived in
    # .NET Core 2.0 and does not exist on .NET Framework 4.8, which is what Windows
    # PowerShell 5.1 runs on. On this machine that shell is the only one installed, so the
    # static call fails outright with "does not contain a method named 'Fill'".
    $buffer = New-Object byte[] $Bytes
    $Rng.GetBytes($buffer)
    # base64url: no '+', '/', '=', '$' or quotes, so the value survives a .env file, a
    # Compose interpolation and a shell without escaping or quoting. ToBase64String is
    # .NET 2.0-era and safe here; Convert.ToHexString is NOT (.NET 5+), and if hex is ever
    # wanted the compatible spelling is BitConverter.ToString($buffer).Replace('-', '').
    [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

# One RNG for both passwords, disposed in a finally so the handle is released even if
# generation or the file write throws.
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    # Generated separately: reusing one value would make the scratch account's password
    # also the server's.
    $rootPassword = New-Password -Rng $rng
    $testPassword = New-Password -Rng $rng
}
finally {
    $rng.Dispose()
}
$port = 3307

# One file, LF endings, no BOM: Compose's --env-file parser and Docker both dislike a BOM.
$lines = @(
    '# Generated locally by infra/mysql-test/new-credentials.ps1. NOT for version control.',
    '# Scope: a throwaway MySQL 8.4 container on 127.0.0.1 that holds only synthetic test',
    '# data. These values grant nothing anywhere else.',
    "AIJOB_MYSQL_TEST_PORT=$port",
    "AIJOB_MYSQL_TEST_ROOT_PASSWORD=$rootPassword",
    "AIJOB_MYSQL_TEST_PASSWORD=$testPassword",
    '',
    '# What the Java integration test reads. Kept here so one file is the single source',
    '# for both the container and the test run.',
    "AIJOB_MYSQL_TEST_URL=jdbc:mysql://127.0.0.1:$port/aijob_import_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
    'AIJOB_MYSQL_TEST_USERNAME=aijob_test'
)
$content = ($lines -join "`n") + "`n"
[System.IO.File]::WriteAllText($envFile, $content, (New-Object System.Text.UTF8Encoding($false)))

# Windows has no chmod 600; the equivalent is an ACL naming exactly one principal.
# Inheritance is switched off first, or the parent folder's rules stay in force.
#
# GetAccessControl/SetAccessControl scoped to the Access section, NOT Get-Acl/Set-Acl.
# Get-Acl returns owner, DACL and SACL together, and writing that whole descriptor back
# makes Windows attempt the audit section too, which needs SeSecurityPrivilege. An
# ordinary session does not hold it, so Set-Acl fails with "The process does not possess
# the 'SeSecurityPrivilege' privilege" -- reproducibly on the -Force path, where the file
# already carries a protected ACL. Naming the one section we actually change keeps the
# operation unprivileged.
$me = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$fileInfo = New-Object System.IO.FileInfo($envFile)
$access = [System.Security.AccessControl.AccessControlSections]::Access
$security = $fileInfo.GetAccessControl($access)
$security.SetAccessRuleProtection($true, $false)   # protected, and drop inherited rules
foreach ($rule in @($security.Access)) { [void]$security.RemoveAccessRule($rule) }
$security.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule(
    $me, 'FullControl', 'None', 'None', 'Allow')))
$fileInfo.SetAccessControl($security)

# Ownership is not set here: this process just created the file, so it already owns it,
# and taking ownership is another privileged call with nothing to gain. Verified instead.
$owner = (New-Object System.IO.FileInfo($envFile)).GetAccessControl(
    [System.Security.AccessControl.AccessControlSections]::Owner).GetOwner(
    [System.Security.Principal.NTAccount]).Value
if ($owner -ne $me) {
    Write-Host "WARNING: $envFile is owned by $owner, not $me. Check it before use."
}

# Drops this script's references. It does NOT scrub the values from memory: .NET strings
# are immutable, so the only real protection is that they were never printed, never
# passed as an argument, and never left this process.
$rootPassword = $null
$testPassword = $null

Write-Host "Wrote $envFile"
Write-Host "Readable by: $me only (owner: $owner)"
Write-Host "Two independent passwords were generated. Neither was printed."
Write-Host ""
Write-Host "Next: .\infra\mysql-test\check-env.ps1   (reports presence, never values)"
