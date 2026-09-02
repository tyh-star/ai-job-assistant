# Runs the MySQL integration test against the isolated container.
#
# The credentials go into the Maven child process's ENVIRONMENT, never onto a command
# line: a -Dpassword=... argument is visible to every other process on this machine
# through the command line, and lands in PowerShell's history file.
#
#   .\infra\mysql-test\run-mysql-tests.ps1

[CmdletBinding()]
param(
    [string]$Test = 'JobExportImportMySqlIntegrationTest'
)

$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Resolve-Path (Join-Path $here '..\..')
$envFile = Join-Path $here '.env.mysql-test'

if (-not (Test-Path $envFile)) {
    Write-Host "MISSING: $envFile - run new-credentials.ps1 first."
    exit 1
}

# Load into this process only. Nothing is echoed, and the values do not outlive the run.
foreach ($line in Get-Content $envFile) {
    $trimmed = $line.Trim()
    if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
    $name, $value = $trimmed -split '=', 2
    Set-Item -Path ("Env:" + $name.Trim()) -Value $value
}

foreach ($name in @('AIJOB_MYSQL_TEST_URL', 'AIJOB_MYSQL_TEST_USERNAME', 'AIJOB_MYSQL_TEST_PASSWORD')) {
    if (-not (Test-Path ("Env:" + $name))) {
        Write-Host "MISSING environment variable $name - run check-env.ps1"
        exit 1
    }
}
Write-Host "AIJOB_MYSQL_TEST_URL / USERNAME / PASSWORD: set (values not shown)"

# The toolchain this repository is built with on this machine.
$env:JAVA_HOME = 'C:\Users\nb666\.jdks\openjdk-23.0.2'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$mvn = 'C:\itailig good   xxx\IntelliJ IDEA 2024.2.0.1\plugins\maven\lib\maven3\bin\mvn.cmd'

Set-Location $root
& $mvn -B test "-Dtest=$Test" -DfailIfNoSpecifiedTests=false
$code = $LASTEXITCODE

# Do not leave the password in this shell's environment after the run.
foreach ($name in @('AIJOB_MYSQL_TEST_ROOT_PASSWORD', 'AIJOB_MYSQL_TEST_PASSWORD')) {
    Remove-Item ("Env:" + $name) -ErrorAction SilentlyContinue
}

exit $code
