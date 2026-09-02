# The pipeline's failure paths, exercised against the isolated test MySQL.
#
# Each case states what must happen and checks that it happened for the RIGHT reason: a
# batch refused because of a BOM instead of because its manifest says "failed" would pass
# a laxer check while proving nothing.
#
#   .\infra\pipeline\verify-pipeline.ps1

[CmdletBinding()]
param(
    [string]$JavaRepo = ''
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not $JavaRepo) { $JavaRepo = (Resolve-Path (Join-Path $scriptDir '..\..')).Path }

# Under the repo: Docker Desktop bind-mounts this reliably, unlike an arbitrary
# %LOCALAPPDATA% subdirectory, which mounts as an empty directory with no error.
$work = Join-Path $JavaRepo '.pipeline-batches\_verify'
if (Test-Path $work) { Get-ChildItem $work -Recurse -File | Remove-Item -Force }
New-Item -ItemType Directory -Path $work -Force | Out-Null

$results = @()
function Check($name, $expected, $actual, $detail) {
    $ok = $expected -eq $actual
    $script:results += [pscustomobject]@{ Case = $name; Expected = $expected; Actual = $actual; Ok = $ok; Detail = $detail }
    Write-Host ("  {0,-46} expected={1,-8} actual={2,-8} {3}" -f $name, $expected, $actual, $(if ($ok) { 'PASS' } else { 'FAIL' }))
    if ($detail) { Write-Host "      $detail" }
}

# Always BOM-less: PowerShell 5.1's Set-Content -Encoding utf8 writes a BOM, and the
# importer rejects that as malformed JSON -- which would mask the reason under test.
function Write-Utf8($path, $text) {
    [System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
}

function Import-Batch($dir) {
    $out = & (Join-Path $scriptDir 'import-batch.ps1') -JavaRepo $JavaRepo `
        -Jsonl (Join-Path $dir 'batch.jsonl') -Manifest (Join-Path $dir 'batch.manifest.json') 2>&1
    return [pscustomobject]@{ Code = $LASTEXITCODE; Text = ($out | Out-String) }
}

function New-Batch($name, $runId, $board, $status, $exportedCount, $lines) {
    $dir = Join-Path $work $name
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    Write-Utf8 (Join-Path $dir 'batch.jsonl') (($lines -join "`n") + "`n")
    Write-Utf8 (Join-Path $dir 'batch.manifest.json') (@"
{
  "run_id": "$runId",
  "mode": "fixture",
  "provider": "greenhouse",
  "board": "$board",
  "company": "Verify Corp",
  "fetched_count": 9,
  "exported_count": $exportedCount,
  "max_export": 50,
  "truncated": true,
  "complete_snapshot": false,
  "status": "$status"
}
"@)
    return $dir
}

function Posting($runId, $board, $n) {
    '{"run_id":"' + $runId + '","provider":"greenhouse","board":"' + $board + '",' +
    '"external_id":"VER-' + $n + '","title":"Verify Role ' + $n + '","company":"Verify Corp",' +
    '"location":"Remote","apply_url":"https://job-boards.greenhouse.io/' + $board + '/jobs/' + (500 + $n) + '",' +
    '"description":"<p>Synthetic posting for the pipeline failure-path checks.</p>",' +
    '"remote":true,"fetched_at":"2026-09-02T02:00:00Z"}'
}

Write-Host "=== B. a failed export, with a usable JSONL sitting beside it ==="
# Exactly the shape a blocked crawl leaves: the manifest says so, and a file from an
# earlier successful run is still on disk. The file alone would import perfectly.
$runB = '20260902T030000Z-verifyb00001'
$dirB = New-Batch 'b-failed-manifest' $runB 'vercel' 'failed' 2 @((Posting $runB 'vercel' 1), (Posting $runB 'vercel' 2))
$b = Import-Batch $dirB
Check 'B: refused (exit 2)' 2 $b.Code ''
Check 'B: refused for the manifest status' $true ($b.Text -match 'status=failed') `
    $(if ($b.Text -match 'refused before any write: (.+)') { $Matches[1].Trim() } else { '' })

Write-Host ""
Write-Host "=== C. manifest and file disagree ==="
$runC = '20260902T030001Z-verifyc00001'
# Manifest claims 5, the file carries 2.
$dirC = New-Batch 'c-count-mismatch' $runC 'vercel' 'ok' 5 @((Posting $runC 'vercel' 1), (Posting $runC 'vercel' 2))
$c = Import-Batch $dirC
Check 'C: refused (exit 2)' 2 $c.Code ''
Check 'C: refused for the count mismatch' $true ($c.Text -match 'manifest declares 5') `
    $(if ($c.Text -match 'refused before any write: (.+)') { $Matches[1].Trim() } else { '' })

Write-Host ""
Write-Host "=== D. a board that is not on the allowlist ==="
$runD = '20260902T030002Z-verifyd00001'
$dirD = New-Batch 'd-unlisted-board' $runD 'not-on-the-list' 'ok' 2 @((Posting $runD 'not-on-the-list' 1), (Posting $runD 'not-on-the-list' 2))
$d = Import-Batch $dirD
Check 'D: refused (exit 2)' 2 $d.Code ''
Check 'D: refused for the allowlist' $true ($d.Text -match 'not in .*sources-allowlist') `
    $(if ($d.Text -match 'refused before any write: (.+)') { $Matches[1].Trim() } else { '' })

Write-Host ""
Write-Host "=== E. two pipeline runs at once ==="
# The mutex is a kernel object, so the second run cannot take it while the first holds
# it -- and it is released the moment the first process dies, however it dies.
$job = Start-Job -ScriptBlock {
    param($dir)
    $m = New-Object System.Threading.Mutex($false, 'Global\aijob-pipeline')
    $held = $m.WaitOne(0)
    Start-Sleep -Seconds 25
    if ($held) { $m.ReleaseMutex() }
    $m.Dispose()
    return $held
} -ArgumentList $work
Start-Sleep -Seconds 3   # let the job actually take the mutex

$overlap = & powershell.exe -NoProfile -ExecutionPolicy Bypass `
    -File (Join-Path $scriptDir 'run-pipeline.ps1') -SkipImport 2>&1
$overlapCode = $LASTEXITCODE
Check 'E: second run skipped (exit 3)' 3 $overlapCode ''
Check 'E: skipped because of the mutex' $true (($overlap | Out-String) -match 'another pipeline run holds the mutex') ''
$firstHeld = Receive-Job -Job $job -Wait
Remove-Job -Job $job
Check 'E: the first holder really had it' $true ([bool]$firstHeld) ''

Write-Host ""
Write-Host "=== F. after the mutex is released, a run proceeds ==="
$after = & powershell.exe -NoProfile -ExecutionPolicy Bypass `
    -File (Join-Path $scriptDir 'run-pipeline.ps1') -SkipImport 2>&1
Check 'F: proceeds once free (exit 0)' 0 $LASTEXITCODE ''
Check 'F: it exported' $true (($after | Out-String) -match 'export ok: run=') ''

Write-Host ""
Write-Host "=== summary ==="
$failed = @($results | Where-Object { -not $_.Ok })
Write-Host ("  {0} checks, {1} passed, {2} failed" -f $results.Count, ($results.Count - $failed.Count), $failed.Count)
if ($failed.Count -gt 0) {
    $failed | ForEach-Object { Write-Host ("  FAILED: {0} (expected {1}, got {2})" -f $_.Case, $_.Expected, $_.Actual) }
    exit 1
}
