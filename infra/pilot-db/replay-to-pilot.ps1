# Replays stored batch artifacts into the PILOT schema. No network, no collection.
#
#   .\infra\pilot-db\replay-to-pilot.ps1                 # every live run, oldest first
#   .\infra\pilot-db\replay-to-pilot.ps1 -Run <run-id>   # one run
#
# TARGET SELECTION IS EXPLICIT AND HAS NO FALLBACK
# ------------------------------------------------
# This reads infra/pilot-db/.env.pilot-db and nothing else. If that file is missing it
# STOPS. It never reaches for .env.mysql-test, and it never reaches for a production
# config: a "helpful" fallback is how real postings ended up in a schema the test suite
# truncates. Wrong-database-by-default is the failure this whole split exists to prevent.
#
# ORDER MATTERS. Runs are replayed OLDEST FIRST so the newest batch is the last writer
# for any posting it contains. Replaying newest-first would let a stale copy overwrite a
# fresher one -- the import updates on content change, and it has no idea which batch is
# more recent.
#
# FIXTURE RUNS ARE EXCLUDED. Synthetic postings belong in the test schema; putting them
# beside real ones is exactly the contamination the fixture allowlist was split to avoid.
#
# Still SAMPLE_IMPORT: nothing is expired, closed or deleted.

[CmdletBinding()]
param(
    [string]$Run = '',
    [string]$Image = 'aijob-pipeline:local',
    [string]$Network = 'aijob-mysqltest_default',
    [string]$StateVolume = 'aijob-pilot-state',
    [string]$Allowlist = '/opt/aijob/config/sources-allowlist.expansion100.json'
)

$ErrorActionPreference = 'Stop'
$env:PATH = "$env:ProgramFiles\Docker\Docker\resources\bin;$env:PATH"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$pilotEnv = Join-Path $here '.env.pilot-db'

if (-not (Test-Path $pilotEnv)) {
    Write-Host "MISSING $pilotEnv"
    Write-Host "Run .\infra\pilot-db\new-pilot-credentials.ps1 and init-pilot-schema.ps1 first."
    Write-Host "This script does NOT fall back to the test or production database."
    exit 1
}

$pilot = @{}
foreach ($line in Get-Content $pilotEnv) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#')) { continue }
    $n, $v = $t -split '=', 2
    $pilot[$n.Trim()] = $v
}
foreach ($k in @('AIJOB_PILOT_DB_URL_INNETWORK', 'AIJOB_PILOT_DB_USERNAME', 'AIJOB_PILOT_DB_PASSWORD')) {
    if (-not $pilot[$k]) { Write-Host "MISSING $k in $pilotEnv -- stopping"; exit 1 }
}
Write-Host "target     : PILOT schema (credentials from $pilotEnv, values not shown)"
Write-Host "allowlist  : $Allowlist"

# The container talks to the service name on 3306. 127.0.0.1:3307 is the HOST's view of
# the same server; inside a container that loopback is the container's own.
$containerEnv = Join-Path $env:TEMP ("aijob-pilot-env-" + [guid]::NewGuid().ToString('N'))
[System.IO.File]::WriteAllText($containerEnv, (@(
    "SPRING_DATASOURCE_URL=$($pilot['AIJOB_PILOT_DB_URL_INNETWORK'])",
    "SPRING_DATASOURCE_USERNAME=$($pilot['AIJOB_PILOT_DB_USERNAME'])",
    "SPRING_DATASOURCE_PASSWORD=$($pilot['AIJOB_PILOT_DB_PASSWORD'])",
    "AIJOB_STORAGE_ROOT=/tmp/aijob-uploads",
    "AIJOB_COLLECTOR_ALLOWLIST_FILE=$Allowlist",
    "JOB_COLLECTOR_ENABLED=false"
) -join "`n") + "`n", (New-Object System.Text.UTF8Encoding($false)))

try {
    # The replay logic lives in replay-batches.sh, mounted rather than passed through
    # sh -c: the script contains quoting that PowerShell and the shell each reinterpret,
    # and a mangled argument produced "Unterminated quoted string" instead of a replay.
    $mountDir = (Resolve-Path $here).Path
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & docker run --rm --network $Network --env-file $containerEnv `
        -v "${StateVolume}:/var/lib/aijob" -e "ONLY_RUN=$Run" `
        -v "${mountDir}:/pilot:ro" --entrypoint sh $Image /pilot/replay-batches.sh 2>&1 |
        ForEach-Object { if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { "$_" } }
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    exit $code
}
finally {
    if (Test-Path $containerEnv) { [System.IO.File]::Delete($containerEnv) }
}
