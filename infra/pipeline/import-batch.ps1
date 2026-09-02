# Runs the Java import CLI over one batch, in the Java 21 container, against the isolated
# test MySQL. Called by run-pipeline.ps1; usable on its own for a single batch.
#
# Credentials come from infra/mysql-test/.env.mysql-test through --env-file: never a
# command-line argument, never printed. The file is read, never written.
#
# Exit code is the CLI's own: 0 imported, 2 refused (nothing written), 3 the board is
# leased by another process, 1 the import failed.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$JavaRepo,
    [Parameter(Mandatory = $true)][string]$Jsonl,
    [Parameter(Mandatory = $true)][string]$Manifest,
    [string]$Image = 'aijob-collector:java21-clitest',
    [string]$Network = 'aijob-mysqltest_default',
    [string]$MysqlService = 'mysql-test',
    # Which allowlist the IMPORTER checks. It re-reads the file for itself rather than
    # trusting the caller, so a fixture run's synthetic board can be permitted without
    # adding it to the file that lists real boards.
    [string]$Allowlist = ''
)

$ErrorActionPreference = 'Stop'
$env:PATH = "$env:ProgramFiles\Docker\Docker\resources\bin;$env:PATH"

$envFile = Join-Path $JavaRepo 'infra\mysql-test\.env.mysql-test'
if (-not (Test-Path $envFile)) { Write-Host "MISSING $envFile"; exit 1 }
foreach ($p in @($Jsonl, $Manifest)) {
    if (-not (Test-Path $p)) { Write-Host "MISSING $p"; exit 1 }
}

$creds = @{}
foreach ($line in Get-Content $envFile) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#')) { continue }
    $n, $v = $t -split '=', 2
    $creds[$n.Trim()] = $v
}

# The batch directory is mounted read-only, so an import cannot alter the evidence of what
# it imported.
$batchDir = Split-Path -Parent (Resolve-Path $Jsonl).Path
$jsonlName = Split-Path -Leaf $Jsonl
$manifestName = Split-Path -Leaf $Manifest

$allowlistName = if ($Allowlist) { Split-Path -Leaf $Allowlist } else { "sources-allowlist.json" }
$containerEnv = Join-Path $env:TEMP ("aijob-import-env-" + [guid]::NewGuid().ToString('N'))
[System.IO.File]::WriteAllText($containerEnv, (@(
    # The service name on 3306, not 127.0.0.1:3307: inside a container that loopback is
    # the container's own, not the host's published port.
    "SPRING_DATASOURCE_URL=jdbc:mysql://${MysqlService}:3306/aijob_import_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
    "SPRING_DATASOURCE_USERNAME=$($creds['AIJOB_MYSQL_TEST_USERNAME'])",
    "SPRING_DATASOURCE_PASSWORD=$($creds['AIJOB_MYSQL_TEST_PASSWORD'])",
    "AIJOB_STORAGE_ROOT=/tmp/aijob-uploads",
    # The allowlist the importer checks for itself, mounted below.
    "AIJOB_COLLECTOR_ALLOWLIST_FILE=/config/$allowlistName",
    # The Java collector's own timer stays off. This pipeline is the only writer.
    "JOB_COLLECTOR_ENABLED=false"
) -join "`n") + "`n", (New-Object System.Text.UTF8Encoding($false)))

try {
    # Preflight the mount. Docker Desktop bind-mounts a path it does not share as an
    # EMPTY directory rather than failing, and the import then reports "No manifest file"
    # -- which sends you looking at the exporter instead of at file sharing. Ask what the
    # container can actually see, and say so plainly when the answer is nothing.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $seen = & docker run --rm -v "${batchDir}:/data:ro" --entrypoint sh $Image -c "ls /data" 2>&1
    $ErrorActionPreference = $previous
    $seenNames = @($seen | ForEach-Object { "$_".Trim() } | Where-Object { $_ -ne '' })
    if ($seenNames -notcontains $jsonlName -or $seenNames -notcontains $manifestName) {
        Write-Host "MOUNT PROBLEM: the container cannot see the batch."
        Write-Host "  host   : $batchDir  ($jsonlName, $manifestName present)"
        Write-Host "  in the container: $(if ($seenNames) { $seenNames -join ', ' } else { '(empty)' })"
        Write-Host "  Docker Desktop is not sharing this path. Put the batch root somewhere"
        Write-Host "  it shares -- inside the repo, or under %LOCALAPPDATA%\Temp -- with"
        Write-Host "  -BatchRoot or AIJOB_BATCH_ROOT."
        exit 1
    }

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $out = & docker run --rm `
        --network $Network `
        --env-file $containerEnv `
        -v "${batchDir}:/data:ro" `
        -v "$(Join-Path $JavaRepo 'config'):/config:ro" `
        $Image `
        "--aijob.import.jsonl=/data/$jsonlName" `
        "--aijob.import.manifest=/data/$manifestName" 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $previous

    $out | ForEach-Object {
        if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { "$_" }
    }
    exit $code
}
finally {
    if (Test-Path $containerEnv) { [System.IO.File]::Delete($containerEnv) }
}
