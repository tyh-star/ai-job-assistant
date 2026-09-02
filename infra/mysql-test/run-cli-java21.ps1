# Runs the real import CLI as a separate OS process on Java 21, against the isolated
# MySQL 8.4 test container.
#
# Why a container and not the host JDK: this machine has only JDK 23. The image builds
# with maven:3.9.9-eclipse-temurin-21 and runs on eclipse-temurin:21-jre-jammy, so both
# the compile and the runtime are Java 21 -- which is the thing the host cannot show.
#
# Networking: the CLI joins the test MySQL's own Compose network and connects to the
# SERVICE NAME on 3306. Inside a container 127.0.0.1:3307 is that container's own
# loopback, not the host's published port, so using it would either fail or -- worse on
# some setups -- reach something else entirely.
#
# Credentials go in through --env-file. Never a command-line argument, never printed.
#
#   .\infra\mysql-test\run-cli-java21.ps1

[CmdletBinding()]
param(
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = (Resolve-Path (Join-Path $here '..\..')).Path
$envFile = Join-Path $here '.env.mysql-test'
$image = 'aijob-collector:java21-clitest'
$network = 'aijob-mysqltest_default'
$mysqlService = 'mysql-test'

if (-not (Test-Path $envFile)) {
    Write-Host "MISSING: $envFile - run new-credentials.ps1 first."
    exit 1
}
$env:PATH = "$env:ProgramFiles\Docker\Docker\resources\bin;$env:PATH"

# --- read the credentials, without echoing them -----------------------------
$creds = @{}
foreach ($line in Get-Content $envFile) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#')) { continue }
    $n, $v = $t -split '=', 2
    $creds[$n.Trim()] = $v
}
foreach ($n in @('AIJOB_MYSQL_TEST_USERNAME', 'AIJOB_MYSQL_TEST_PASSWORD')) {
    if (-not $creds.ContainsKey($n) -or [string]::IsNullOrWhiteSpace($creds[$n])) {
        Write-Host "MISSING $n in $envFile"; exit 1
    }
}
Write-Host "Credentials: loaded from $envFile (values not shown)"

# --- confirm the target is the isolated container ---------------------------
$running = & docker ps --filter "name=$mysqlService" --format '{{.Names}}'
if (-not $running) { Write-Host "The isolated MySQL container is not running."; exit 1 }
Write-Host "Target: $running on network $network (service name $mysqlService, port 3306 in-network)"

# --- build the Java 21 image ------------------------------------------------
if (-not $SkipBuild) {
    Write-Host "Building $image from Dockerfile.collector (Java 21 build + Java 21 runtime)..."
    Push-Location $root
    & docker build -f Dockerfile.collector -t $image .
    $buildCode = $LASTEXITCODE
    Pop-Location
    if ($buildCode -ne 0) { Write-Host "Build failed."; exit $buildCode }
}

# --- a synthetic batch to import --------------------------------------------
# Generated here rather than mounting collected job data into a container.
$batchDir = Join-Path $env:TEMP "aijob-cli-batch"
New-Item -ItemType Directory -Path $batchDir -Force | Out-Null
# A board this database has never seen, so the FIRST run is genuinely an insert rather
# than an "unchanged" that only looks like success because an earlier attempt got there.
$suffix = -join ((1..8) | ForEach-Object { '0123456789abcdef'[(Get-Random -Maximum 16)] })
$board = "clitest-$suffix"
$runId = "20260901T210000Z-java21c$suffix"
$lines = @()
for ($i = 1; $i -le 5; $i++) {
    $lines += ('{"run_id":"' + $runId + '","provider":"greenhouse","board":"' + $board + '",' +
        '"external_id":"REQ-' + $i + '","title":"Java 21 CLI Check ' + $i + '",' +
        '"company":"CLI Test","location":"Remote - Testing",' +
        '"apply_url":"https://job-boards.greenhouse.io/' + $board + '/jobs/' + (9000 + $i) + '",' +
        '"description":"<p>A synthetic posting used only to exercise the import CLI.</p>",' +
        '"remote":true,"posted_at":"2026-08-18T18:06:19Z","fetched_at":"2026-09-01T21:00:00Z"}')
}
$jsonl = Join-Path $batchDir 'clitest.jsonl'
[System.IO.File]::WriteAllText($jsonl, (($lines -join "`n") + "`n"), (New-Object System.Text.UTF8Encoding($false)))
$manifest = Join-Path $batchDir 'clitest.manifest.json'
[System.IO.File]::WriteAllText($manifest, @"
{
  "run_id": "$runId",
  "mode": "fixture",
  "provider": "greenhouse",
  "board": "$board",
  "company": "CLI Test",
  "fetched_count": 12,
  "exported_count": 5,
  "max_export": 5,
  "truncated": true,
  "complete_snapshot": false,
  "status": "ok"
}
"@, (New-Object System.Text.UTF8Encoding($false)))
Write-Host "Batch: 5 synthetic postings in $batchDir"

# --- the container's environment --------------------------------------------
# Written to a temp file so nothing lands on a command line, and deleted in the finally.
$containerEnv = Join-Path $env:TEMP ("aijob-cli-env-" + [guid]::NewGuid().ToString('N'))
$envLines = @(
    "SPRING_DATASOURCE_URL=jdbc:mysql://${mysqlService}:3306/aijob_import_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
    "SPRING_DATASOURCE_USERNAME=$($creds['AIJOB_MYSQL_TEST_USERNAME'])",
    "SPRING_DATASOURCE_PASSWORD=$($creds['AIJOB_MYSQL_TEST_PASSWORD'])",
    "AIJOB_STORAGE_ROOT=/tmp/aijob-uploads",
    # Deliberately NOT set: JWT_SECRET, OPENAI_API_KEY, JSEARCH_API_KEY. The collector
    # profile's placeholders have to carry the run, which is part of what this proves.
    "JOB_COLLECTOR_ENABLED=false"
)
[System.IO.File]::WriteAllText($containerEnv, (($envLines -join "`n") + "`n"),
    (New-Object System.Text.UTF8Encoding($false)))

try {
    $acl = Get-Acl $containerEnv
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($rule in @($acl.Access)) { [void]$acl.RemoveAccessRule($rule) }
    $me = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule(
        $me, 'FullControl', 'None', 'None', 'Allow')))
    $fi = New-Object System.IO.FileInfo($containerEnv)
    $fi.SetAccessControl([System.Security.AccessControl.FileSecurity]$acl)
} catch {
    # Not fatal: the file lives for seconds in the user's own temp directory.
}

function Invoke-ImportCli {
    param([string]$Label)
    Write-Host ""
    Write-Host "=== $Label ==="
    $started = Get-Date
    # Windows PowerShell 5.1 wraps a native command's stderr lines in ErrorRecords, and
    # under ErrorActionPreference='Stop' that aborts the script on output the JVM writes
    # routinely ("Picked up JAVA_TOOL_OPTIONS"). Spring Boot logs to stderr by default, so
    # essentially the whole run arrives that way. Relax it for this call only.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & docker run --rm `
            --network $network `
            --env-file $containerEnv `
            -v "${batchDir}:/data:ro" `
            $image `
            --aijob.import.jsonl=/data/clitest.jsonl `
            --aijob.import.manifest=/data/clitest.manifest.json 2>&1
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previous
    }
    $elapsed = [int]((Get-Date) - $started).TotalSeconds
    # Flatten ErrorRecords back to their text so the checks below can scan one string.
    $text = $output | ForEach-Object { if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { "$_" } }
    return [pscustomobject]@{ Output = $text; ExitCode = $code; Seconds = $elapsed }
}

try {
    $first = Invoke-ImportCli 'FIRST import'
    $first.Output | ForEach-Object { "  | $_" }
    Write-Host "  exit=$($first.ExitCode) seconds=$($first.Seconds)"

    $second = Invoke-ImportCli 'SECOND import (same batch)'
    $second.Output | ForEach-Object { "  | $_" }
    Write-Host "  exit=$($second.ExitCode) seconds=$($second.Seconds)"

    # A batch whose manifest disagrees with the file: the CLI must refuse it and say so
    # with a distinct exit code, not treat a bad batch as an empty success.
    $badManifest = Join-Path $batchDir 'clitest.manifest.json'
    $goodManifestText = [System.IO.File]::ReadAllText($badManifest)
    [System.IO.File]::WriteAllText($badManifest,
        $goodManifestText.Replace('"exported_count": 5', '"exported_count": 99'),
        (New-Object System.Text.UTF8Encoding($false)))
    $refused = Invoke-ImportCli 'THIRD run (manifest disagrees with the file)'
    $refused.Output | Select-Object -Last 6 | ForEach-Object { "  | $_" }
    Write-Host "  exit=$($refused.ExitCode) seconds=$($refused.Seconds)"
    [System.IO.File]::WriteAllText($badManifest, $goodManifestText,
        (New-Object System.Text.UTF8Encoding($false)))

    Write-Host ""
    Write-Host "=== checks ==="
    $all = (($first.Output + $second.Output) | Out-String)

    function Field($text, $name) {
        $m = [regex]::Match($text, "$name=(\d+)")
        if ($m.Success) { [int]$m.Groups[1].Value } else { -1 }
    }
    $f = ($first.Output | Out-String); $s = ($second.Output | Out-String)
    Write-Host ("  first  : inserted={0} updated={1} unchanged={2} failed={3} expired={4} exit={5} ({6}s)" -f `
        (Field $f 'inserted'), (Field $f 'updated'), (Field $f 'unchanged'), (Field $f 'failed'),
        (Field $f 'expired'), $first.ExitCode, $first.Seconds)
    Write-Host ("  second : inserted={0} updated={1} unchanged={2} failed={3} expired={4} exit={5} ({6}s)" -f `
        (Field $s 'inserted'), (Field $s 'updated'), (Field $s 'unchanged'), (Field $s 'failed'),
        (Field $s 'expired'), $second.ExitCode, $second.Seconds)
    Write-Host ("  first inserted 5, none existing : {0} (must be True)" -f
        ((Field $f 'inserted') -eq 5 -and (Field $f 'unchanged') -eq 0))
    Write-Host ("  second inserted 0, all unchanged: {0} (must be True)" -f
        ((Field $s 'inserted') -eq 0 -and (Field $s 'unchanged') -eq 5))
    Write-Host ("  exit codes 0 / 0                : {0} (must be True)" -f
        ($first.ExitCode -eq 0 -and $second.ExitCode -eq 0))
    Write-Host ("  refused batch exit code 2       : {0} (must be True)" -f ($refused.ExitCode -eq 2))
    Write-Host ("  refused batch wrote nothing     : {0} (must be True)" -f
        (($refused.Output | Out-String) -match 'refused before any write'))
    Write-Host ("  each run under 60s              : {0} (must be True)" -f
        ($first.Seconds -lt 60 -and $second.Seconds -lt 60 -and $refused.Seconds -lt 60))
    $pw = $creds['AIJOB_MYSQL_TEST_PASSWORD']
    Write-Host ("  password in output          : {0} (must be False)" -f ($all.Contains($pw)))
    Write-Host ("  java version reported       : {0}" -f (
        ($all -split "`n" | Select-String -Pattern 'using Java [0-9.]+' |
            ForEach-Object { $_.Matches[0].Value } | Select-Object -First 1)))
    Write-Host ("  Tomcat started              : {0} (must be False)" -f
        ($all -match 'Tomcat started|TomcatWebServer'))
    Write-Host ("  scheduled collection ran    : {0} (must be False)" -f
        ($all -match 'Collecting from source|collectDueSources'))
    Write-Host ("  outbound board request      : {0} (must be False)" -f
        ($all -match 'boards-api\.greenhouse\.io|api\.lever\.co'))
    Write-Host ("  OpenAI / JSearch contacted  : {0} (must be False)" -f
        ($all -match 'api\.openai\.com|jsearch|rapidapi'))
    Write-Host ("  expiry performed            : {0} (must be False)" -f
        ($all -match 'expired=[1-9]'))
}
finally {
    if (Test-Path $containerEnv) { [System.IO.File]::Delete($containerEnv) }
    Write-Host ""
    Write-Host "(container env file deleted)"
}
