# Runs the backend test suite on Java 21, in a container, against the isolated MySQL 8.4.
#
# Two runs, reported separately because they are different claims:
#
#   1. the full suite, live flags off, MySQL variables absent -- the MySQL class skips,
#      exactly as it does on the host
#   2. the MySQL integration suite, with the test credentials supplied
#
# The host keeps its JDK 23; nothing here changes it.
#
# Networking: run 2 joins the test MySQL's Compose network and connects to the SERVICE
# NAME on 3306. Inside a container 127.0.0.1:3307 is that container's own loopback, not
# the host's published port.
#
# Credentials come from the existing .env.mysql-test through --env-file. They are not
# regenerated, not printed, and never put on a command line.
#
#   .\infra\mysql-test\run-tests-java21.ps1

[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$FullSuiteOnly,
    [switch]$MysqlOnly
)

$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = (Resolve-Path (Join-Path $here '..\..')).Path
$envFile = Join-Path $here '.env.mysql-test'
$image = 'aijob-tests:java21'
$network = 'aijob-mysqltest_default'
$mysqlService = 'mysql-test'

$env:PATH = "$env:ProgramFiles\Docker\Docker\resources\bin;$env:PATH"

if (-not $SkipBuild) {
    Write-Host "Building $image (maven:3.9.9-eclipse-temurin-21)..."
    Push-Location $root
    & docker build -f infra/mysql-test/Dockerfile.java21-tests -t $image .
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { Write-Host "Build failed."; exit $code }
}

# A named volume for ~/.m2 so the second run and any re-run reuse the first download.
& docker volume create aijob-m2-java21 | Out-Null

# The source is not bind-mounted: the image COPYs it at build time, so each run tests the
# tree the image was built from. An earlier attempt mounted the host tree read-only with
# a volume over /src/target, and `mvn clean` failed trying to delete that mount point --
# and with the source baked in there is nothing to clean in a fresh container anyway.

function Invoke-Maven {
    param([string]$Label, [string[]]$DockerArgs, [string[]]$MavenArgs)
    Write-Host ""
    Write-Host "=== $Label ==="
    $started = Get-Date
    # Windows PowerShell 5.1 turns a native command's stderr into ErrorRecords, which
    # under ErrorActionPreference='Stop' aborts on output Maven writes routinely.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & docker run --rm -v aijob-m2-java21:/root/.m2 @DockerArgs $image @MavenArgs 2>&1
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    $minutes = [math]::Round(((Get-Date) - $started).TotalMinutes, 1)
    $text = $output | ForEach-Object {
        if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { "$_" }
    }
    return [pscustomobject]@{ Output = $text; ExitCode = $code; Minutes = $minutes }
}

function Show-Result {
    param($Result, [string]$Label)
    $text = $Result.Output | Out-String
    Write-Host "  exit=$($Result.ExitCode)  elapsed=$($Result.Minutes)min"
    $Result.Output | Select-String -Pattern 'Starting .* using Java' | Select-Object -First 1 |
        ForEach-Object { "  runtime: " + ([regex]::Match($_, 'using Java [0-9._]+').Value) } | Write-Host
    $Result.Output | Select-String -CaseSensitive -Pattern '^\[(INFO|WARNING|ERROR)\] Tests run: \d+, Fail' |
        Select-Object -Last 1 | ForEach-Object { "  $_" } | Write-Host
    $failures = $Result.Output | Select-String -CaseSensitive -Pattern '^\[ERROR\]\s+\w+Test' | Select-Object -First 10
    if ($failures) {
        Write-Host "  FAILURES:"
        $failures | ForEach-Object { "    $_" } | Write-Host
    }
    # Skips, with the reason JUnit recorded.
    $Result.Output | Select-String -Pattern 'Skipped: [1-9]' | Select-Object -Last 3 |
        ForEach-Object { "  $_" } | Write-Host
}

$results = @{}

if (-not $MysqlOnly) {
    # No MySQL variables in this environment, so the MySQL class skips exactly as on the
    # host, and no live flag is set, so the OpenAI classes skip too.
    $results['full'] = Invoke-Maven 'FULL SUITE on Java 21 (live flags off, no MySQL vars)' `
        @() `
        @('test')
    Show-Result $results['full'] 'full'
}

if (-not $FullSuiteOnly) {
    if (-not (Test-Path $envFile)) {
        Write-Host "MISSING: $envFile - cannot run the MySQL suite."
        exit 1
    }
    $running = & docker ps --filter "name=$mysqlService" --format '{{.Names}}'
    if (-not $running) { Write-Host "The isolated MySQL container is not running."; exit 1 }
    Write-Host ""
    Write-Host "Target: $running on $network (service name $mysqlService, port 3306 in-network)"

    # Re-point the URL at the in-network service. Read from the existing file; the file
    # itself is neither modified nor regenerated.
    $creds = @{}
    foreach ($line in Get-Content $envFile) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        $n, $v = $t -split '=', 2
        $creds[$n.Trim()] = $v
    }
    $containerEnv = Join-Path $env:TEMP ("aijob-tests-env-" + [guid]::NewGuid().ToString('N'))
    [System.IO.File]::WriteAllText($containerEnv, (@(
        "AIJOB_MYSQL_TEST_URL=jdbc:mysql://${mysqlService}:3306/aijob_import_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
        "AIJOB_MYSQL_TEST_USERNAME=$($creds['AIJOB_MYSQL_TEST_USERNAME'])",
        "AIJOB_MYSQL_TEST_PASSWORD=$($creds['AIJOB_MYSQL_TEST_PASSWORD'])"
    ) -join "`n") + "`n", (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Credentials: taken from the existing $envFile (values not shown, file unchanged)"

    try {
        $results['mysql'] = Invoke-Maven 'MYSQL SUITE on Java 21 (isolated aijob_import_test)' `
            @('--network', $network, '--env-file', $containerEnv) `
            @('test', '-Dtest=JobExportImportMySqlIntegrationTest', '-DfailIfNoSpecifiedTests=false')
        Show-Result $results['mysql'] 'mysql'

        $all = ($results['mysql'].Output | Out-String)
        $pw = $creds['AIJOB_MYSQL_TEST_PASSWORD']
        Write-Host ("  password in output: {0} (must be False)" -f ($all.Contains($pw)))
    }
    finally {
        if (Test-Path $containerEnv) { [System.IO.File]::Delete($containerEnv) }
        Write-Host "  (container env file deleted; .env.mysql-test untouched)"
    }
}

Write-Host ""
Write-Host "=== summary ==="
foreach ($k in @('full', 'mysql')) {
    if ($results.ContainsKey($k)) {
        Write-Host ("  {0,-6} exit={1} ({2} min)" -f $k, $results[$k].ExitCode, $results[$k].Minutes)
    }
}
$bad = @($results.Values | Where-Object { $_.ExitCode -ne 0 })
if ($bad.Count -gt 0) { exit 1 }
