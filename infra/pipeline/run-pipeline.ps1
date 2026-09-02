# One command: allowlist -> Go export -> verify this run's output -> Java import -> summary.
#
#   .\infra\pipeline\run-pipeline.ps1
#   .\infra\pipeline\run-pipeline.ps1 -Live       # actually fetch from the boards
#
# Manual only. Nothing here installs a scheduled task, and the Java collector's own timer
# stays off: the two would collect the same boards independently, and this pipeline is
# the one that writes.
#
# Paths are configuration, not constants -- see -JavaRepo / -UpstreamRepo below, or the
# AIJOB_REPO / FREEHIRE_REPO environment variables. Nothing is hardcoded to one machine.

[CmdletBinding()]
param(
    # Real network fetch. Off by default: the safe mode is the one you get by accident.
    [switch]$Live,

    # Both resolved in the body, not here: $PSScriptRoot is empty inside a param()
    # default on Windows PowerShell 5.1, which turns the intended default into a
    # Join-Path error instead.
    [string]$JavaRepo     = '',
    [string]$UpstreamRepo = '',

    # Where batches land: one directory per run, one subdirectory per board. Resolved in
    # the body so it can default relative to the repo.
    #
    # It has to be a path Docker Desktop will bind-mount, which is narrower than "any
    # directory you can write to". Measured on this machine: C:\<repo>\... and
    # %LOCALAPPDATA%\Temp\... mount fine, while other %LOCALAPPDATA% subdirectories mount
    # as an EMPTY directory -- no error, just nothing there. Defaulting inside the repo
    # keeps batches beside the project and on a path that is known to work.
    [string]$BatchRoot    = '',

    [int]$MaxPerBoard = 50,
    [int]$TimeoutSeconds = 120,

    # Pin the exporter. A run records what it resolves to either way; this refuses to
    # proceed when the checkout is not on the expected commit.
    [string]$ExpectedUpstreamSha = '',

    [switch]$SkipImport,

    # Fixture runs use a separate allowlist whose board identity is not a real board, so
    # synthetic postings never land in the same job_sources row as collected ones. They
    # used to share the live file, and three invented jobs ended up in the catalog beside
    # 50 real ones with nothing but the payload to tell them apart.
    [string]$Allowlist = ''
)

$ErrorActionPreference = 'Stop'
$goExe = 'C:\Program Files\Go\bin\go.exe'

function Fail($message) { Write-Host "PIPELINE ABORT: $message"; exit 1 }

# ---------------------------------------------------------------------------
# 0. Resolve configuration
# ---------------------------------------------------------------------------

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not $JavaRepo) {
    $JavaRepo = if ($env:AIJOB_REPO) { $env:AIJOB_REPO }
                else { (Resolve-Path (Join-Path $scriptDir '..\..')).Path }
}
if (-not $BatchRoot) {
    $BatchRoot = if ($env:AIJOB_BATCH_ROOT) { $env:AIJOB_BATCH_ROOT }
                 else { Join-Path $JavaRepo '.pipeline-batches' }
}
if (-not $UpstreamRepo -and $env:FREEHIRE_REPO) { $UpstreamRepo = $env:FREEHIRE_REPO }
if (-not $UpstreamRepo) {
    # Sibling of the Java repo is the layout this was developed against, but it is a
    # guess, not a rule: -UpstreamRepo or FREEHIRE_REPO overrides it.
    $guess = Join-Path (Split-Path -Parent $JavaRepo) 'freehire-upstream'
    if (Test-Path $guess) { $UpstreamRepo = $guess }
}
if (-not $UpstreamRepo -or -not (Test-Path (Join-Path $UpstreamRepo 'go.mod'))) {
    Fail "No FreeHire checkout. Pass -UpstreamRepo <path> or set FREEHIRE_REPO."
}
if (-not (Test-Path (Join-Path $JavaRepo 'pom.xml'))) {
    Fail "No Java project at $JavaRepo. Pass -JavaRepo <path> or set AIJOB_REPO."
}
if (-not (Test-Path $goExe)) { Fail "Go not found at $goExe." }

$allowlistPath = if ($Allowlist) { $Allowlist }
                 elseif ($Live)  { Join-Path $JavaRepo 'config\sources-allowlist.json' }
                 else            { Join-Path $JavaRepo 'config\sources-allowlist.fixture.json' }
if (-not (Test-Path $allowlistPath)) { Fail "No allowlist at $allowlistPath." }

$runId = (Get-Date).ToUniversalTime().ToString('yyyyMMddTHHmmssZ') + '-' +
         (-join ((1..6) | ForEach-Object { '0123456789abcdef'[(Get-Random -Maximum 16)] }))
$runDir = Join-Path $BatchRoot $runId
New-Item -ItemType Directory -Path $runDir -Force | Out-Null

Write-Host "run             : $runId"
Write-Host "java repo       : $JavaRepo"
Write-Host "freehire repo   : $UpstreamRepo"
Write-Host "batch dir       : $runDir"
Write-Host "mode            : $(if ($Live) { 'LIVE (real network)' } else { 'fixture (no network)' })"
Write-Host "allowlist       : $allowlistPath"

# ---------------------------------------------------------------------------
# 1. Pin the exporter
# ---------------------------------------------------------------------------

Push-Location $UpstreamRepo
$upstreamSha = (& git rev-parse HEAD).Trim()
$upstreamBranch = (& git rev-parse --abbrev-ref HEAD).Trim()
$upstreamDirty = @(& git status --porcelain).Count
Pop-Location
Write-Host "exporter        : $upstreamBranch @ $upstreamSha ($upstreamDirty uncommitted)"
if ($ExpectedUpstreamSha -and $upstreamSha -ne $ExpectedUpstreamSha) {
    Fail "exporter is at $upstreamSha, expected $ExpectedUpstreamSha"
}
if ($upstreamDirty -ne 0) {
    Write-Host "WARNING: the exporter checkout has uncommitted changes; this run is not reproducible from a commit."
}

# ---------------------------------------------------------------------------
# 2. Process mutex -- a kernel object, not a PID file
# ---------------------------------------------------------------------------
#
# A PID file only records that a process once started. It cannot tell a running pipeline
# from one killed mid-run, and the check-then-write between reading it and claiming it is
# a race. A named mutex is held by the OS: a second run cannot acquire it while the first
# is alive, and Windows releases it the instant that process dies, however it dies.
#
# This guards the HOST side (two pipeline runs on this machine). It is not the only lock:
# the import itself takes the per-source database lease, which is what covers a writer in
# another container.
$mutex = New-Object System.Threading.Mutex($false, 'Global\aijob-pipeline')
$holdsMutex = $false
try {
    try {
        $holdsMutex = $mutex.WaitOne(0)
    } catch [System.Threading.AbandonedMutexException] {
        # The previous holder died without releasing. We now own it, and the run it was
        # doing is over -- its batch directory is separate, so nothing is half-written.
        Write-Host "note: took over an abandoned pipeline mutex (a previous run was killed)"
        $holdsMutex = $true
    }
    if (-not $holdsMutex) {
        Write-Host "PIPELINE SKIPPED: another pipeline run holds the mutex on this machine."
        exit 3
    }

    # -----------------------------------------------------------------------
    # 3. Read the allowlist
    # -----------------------------------------------------------------------
    $allowlist = Get-Content $allowlistPath -Raw | ConvertFrom-Json
    $boards = @($allowlist.sources | Where-Object { $_.enabled })
    if ($boards.Count -eq 0) { Fail "no enabled sources in $allowlistPath" }
    Write-Host "boards          : $($boards.Count) enabled"

    $results = @()

    foreach ($board in $boards) {
        $label = "$($board.provider)/$($board.board)"
        Write-Host ""
        Write-Host "=== $label ==="

        if (-not $board.evidence) {
            Write-Host "  SKIP: no evidence recorded in the allowlist"
            $results += [pscustomobject]@{ Board = $label; Export = 'skipped'; Import = 'skipped'
                                           Reason = 'no evidence'; Fetched = 0; Exported = 0
                                           Inserted = 0; Updated = 0; Unchanged = 0; ExportRunId = '' }
            continue
        }

        # Its own directory, named for this run and this board. Nothing from a previous
        # run is reachable from here, so a failed export cannot be papered over by a file
        # that happens to be lying around.
        $boardDir = Join-Path $runDir "$($board.provider)-$($board.board)"
        New-Item -ItemType Directory -Path $boardDir -Force | Out-Null
        $jsonl = Join-Path $boardDir 'batch.jsonl'
        $manifest = Join-Path $boardDir 'batch.manifest.json'

        # -------------------------------------------------------------------
        # 4. Export
        # -------------------------------------------------------------------
        $exportArgs = @('run', './cmd/jdexport',
            '-provider', $board.provider, '-board', $board.board, '-company', $board.company,
            '-max', $MaxPerBoard, '-timeout', "$($TimeoutSeconds)s", '-out', $jsonl)
        if ($Live) {
            $exportArgs += '-live'
        } else {
            $fixture = Join-Path $UpstreamRepo "cmd/jdexport/testdata/$($board.provider)_board.json"
            if (-not (Test-Path $fixture)) {
                $fixture = Join-Path $UpstreamRepo 'cmd/jdexport/testdata/greenhouse_board.json'
            }
            $exportArgs += @('-fixture', $fixture)
        }

        Push-Location $UpstreamRepo
        $env:PATH = "$(Split-Path -Parent $goExe);$env:PATH"
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $exportOut = & $goExe @exportArgs 2>&1
        $exportCode = $LASTEXITCODE
        $ErrorActionPreference = $previous
        Pop-Location
        $exportText = ($exportOut | ForEach-Object {
            if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { "$_" }
        }) -join "`n"

        # The exporter's own bounded retries have already run by this point. A failure
        # here is final for this board in this run: no extra attempts, and the manifest
        # it left behind is kept as the record of why.
        if ($exportCode -ne 0) {
            Write-Host "  EXPORT FAILED (exit $exportCode) -- not importing this board"
            ($exportText -split "`n" | Select-Object -Last 4) | ForEach-Object { "    $_" }
            $results += [pscustomobject]@{ Board = $label; Export = 'failed'; Import = 'not attempted'
                                           Reason = "export exit $exportCode"; Fetched = 0; Exported = 0
                                           Inserted = 0; Updated = 0; Unchanged = 0; ExportRunId = '' }
            continue
        }

        # -------------------------------------------------------------------
        # 5. Verify THIS run's output before importing it
        # -------------------------------------------------------------------
        if (-not (Test-Path $jsonl) -or -not (Test-Path $manifest)) {
            Write-Host "  EXPORT reported success but produced no batch -- not importing"
            $results += [pscustomobject]@{ Board = $label; Export = 'incomplete'; Import = 'not attempted'
                                           Reason = 'missing jsonl or manifest'; Fetched = 0; Exported = 0
                                           Inserted = 0; Updated = 0; Unchanged = 0; ExportRunId = '' }
            continue
        }
        $m = Get-Content $manifest -Raw | ConvertFrom-Json
        $lineCount = @(Get-Content $jsonl | Where-Object { $_.Trim() -ne '' }).Count
        $checks = @()
        if ($m.status -notin @('ok', 'ok_empty')) { $checks += "manifest status=$($m.status)" }
        if ($m.provider -ne $board.provider)      { $checks += "manifest provider=$($m.provider)" }
        if ($m.board -ne $board.board)            { $checks += "manifest board=$($m.board)" }
        if ($lineCount -ne $m.exported_count)     { $checks += "file has $lineCount lines, manifest says $($m.exported_count)" }
        if ($checks.Count -gt 0) {
            Write-Host "  BATCH REJECTED before import: $($checks -join '; ')"
            $results += [pscustomobject]@{ Board = $label; Export = 'invalid'; Import = 'not attempted'
                                           Reason = ($checks -join '; '); Fetched = $m.fetched_count
                                           Exported = $m.exported_count; Inserted = 0; Updated = 0
                                           Unchanged = 0; ExportRunId = $m.run_id }
            continue
        }
        Write-Host "  export ok: run=$($m.run_id) fetched=$($m.fetched_count) exported=$($m.exported_count) truncated=$($m.truncated)"

        if ($SkipImport) {
            $results += [pscustomobject]@{ Board = $label; Export = 'ok'; Import = 'skipped by flag'
                                           Reason = ''; Fetched = $m.fetched_count; Exported = $m.exported_count
                                           Inserted = 0; Updated = 0; Unchanged = 0; ExportRunId = $m.run_id }
            continue
        }

        # -------------------------------------------------------------------
        # 6. Import
        # -------------------------------------------------------------------
        # Delegated to the existing runner, which re-validates everything independently:
        # the allowlist, the manifest, every record, and the per-source database lease.
        $import = & (Join-Path $scriptDir 'import-batch.ps1') `
            -JavaRepo $JavaRepo -Jsonl $jsonl -Manifest $manifest -Allowlist $allowlistPath
        $importCode = $LASTEXITCODE

        $status = switch ($importCode) {
            0 { 'ok' }
            2 { 'refused' }
            3 { 'skipped (board leased elsewhere)' }
            default { "failed ($importCode)" }
        }
        Write-Host "  import: $status"
        $counts = [regex]::Match(($import -join "`n"),
            'read=(\d+) inserted=(\d+) updated=(\d+) unchanged=(\d+) failed=(\d+) expired=(\d+)')
        $results += [pscustomobject]@{
            Board = $label; Export = 'ok'; Import = $status
            Reason = ''
            Fetched = $m.fetched_count; Exported = $m.exported_count
            Inserted  = $(if ($counts.Success) { [int]$counts.Groups[2].Value } else { 0 })
            Updated   = $(if ($counts.Success) { [int]$counts.Groups[3].Value } else { 0 })
            Unchanged = $(if ($counts.Success) { [int]$counts.Groups[4].Value } else { 0 })
            ExportRunId = $m.run_id
        }
    }

    # -----------------------------------------------------------------------
    # 7. Summary, and a run record beside the batches
    # -----------------------------------------------------------------------
    Write-Host ""
    Write-Host "=== summary: run $runId ==="
    $results | ForEach-Object {
        Write-Host ("  {0,-24} export={1,-10} import={2,-32} fetched={3,-4} exported={4,-4} ins={5} upd={6} unch={7}" -f `
            $_.Board, $_.Export, $_.Import, $_.Fetched, $_.Exported, $_.Inserted, $_.Updated, $_.Unchanged)
        if ($_.Reason) { Write-Host ("    reason: {0}" -f $_.Reason) }
        if ($_.ExportRunId) { Write-Host ("    export run_id: {0}" -f $_.ExportRunId) }
    }

    $record = [pscustomobject]@{
        pipeline_run_id = $runId
        started_utc     = $runId.Substring(0, 16)
        mode            = $(if ($Live) { 'live' } else { 'fixture' })
        exporter_sha    = $upstreamSha
        exporter_branch = $upstreamBranch
        exporter_dirty  = ($upstreamDirty -ne 0)
        allowlist       = $allowlistPath
        max_per_board   = $MaxPerBoard
        boards          = $results
    }
    $recordPath = Join-Path $runDir 'pipeline-run.json'
    $record | ConvertTo-Json -Depth 6 | Set-Content -Path $recordPath -Encoding utf8
    Write-Host ""
    Write-Host "run record: $recordPath"

    $bad = @($results | Where-Object { $_.Export -notin @('ok', 'skipped') -or $_.Import -like 'failed*' })
    if ($bad.Count -gt 0) { exit 1 }
}
finally {
    if ($holdsMutex) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
