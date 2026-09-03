# Proves the boundary between the test schema and the pilot schema, by trying to cross it.
#
#   .\infra\pilot-db\verify-isolation.ps1
#
# Every check here attempts something that MUST fail, and fails the run if it succeeds.
# A permission boundary nobody has pushed against is a belief, not a boundary.

[CmdletBinding()]
param(
    [string]$MysqlContainer = 'mysql-test',
    [string]$Network = 'aijob-mysqltest_default'
)

$ErrorActionPreference = 'Stop'
$env:PATH = "$env:ProgramFiles\Docker\Docker\resources\bin;$env:PATH"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = (Resolve-Path (Join-Path $here '..\..')).Path

function Read-EnvFile($path) {
    $h = @{}
    foreach ($line in Get-Content $path) {
        $t = $line.Trim()
        if ($t -eq '' -or $t.StartsWith('#')) { continue }
        $n, $v = $t -split '=', 2
        $h[$n.Trim()] = $v
    }
    return $h
}

$pilot = Read-EnvFile (Join-Path $here '.env.pilot-db')
$test = Read-EnvFile (Join-Path $repo 'infra\mysql-test\.env.mysql-test')

$PASS = 0; $FAIL = 0
function Check($name, $expected, $actual, $detail) {
    if ("$expected" -eq "$actual") {
        Write-Host ("  {0,-56} expected={1,-9} actual={2,-9} PASS" -f $name, $expected, $actual); $script:PASS++
    } else {
        Write-Host ("  {0,-56} expected={1,-9} actual={2,-9} FAIL" -f $name, $expected, $actual); $script:FAIL++
    }
    if ($detail) { Write-Host "      $detail" }
}

function TrySql($user, $pw, $db, $q) {
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $args = @('run','--rm','--network',$Network,'-e',"MYSQL_PWD=$pw",'mysql:8.4',
              'mysql','-h',$MysqlContainer,"-u$user",'--batch','--skip-column-names')
    if ($db) { $args += @('-D',$db) }
    $args += @('-e',$q)
    $out = & docker @args 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    return [pscustomobject]@{ Ok = ($code -eq 0); Output = ($out | Out-String) }
}

$pilotSchema = $pilot['AIJOB_PILOT_SCHEMA']
$pilotUser = $pilot['AIJOB_PILOT_DB_USERNAME']
$pilotPw = $pilot['AIJOB_PILOT_DB_PASSWORD']
$adminUser = $pilot['AIJOB_PILOT_ADMIN_USERNAME']
$adminPw = $pilot['AIJOB_PILOT_ADMIN_PASSWORD']
$testUser = $test['AIJOB_MYSQL_TEST_USERNAME']
$testPw = $test['AIJOB_MYSQL_TEST_PASSWORD']

Write-Host "=== 1. the TEST account cannot reach the pilot schema ==="
$r = TrySql $testUser $testPw '' "SELECT COUNT(*) FROM ``$pilotSchema``.job_catalog"
Check 'test account SELECT on pilot' $false $r.Ok 'this is the account the truncating suite runs as'
$r = TrySql $testUser $testPw '' "TRUNCATE TABLE ``$pilotSchema``.job_catalog"
Check 'test account TRUNCATE on pilot' $false $r.Ok 'the exact operation that destroyed the data before'
$r = TrySql $testUser $testPw '' "DROP DATABASE ``$pilotSchema``"
Check 'test account DROP DATABASE pilot' $false $r.Ok
$r = TrySql $testUser $testPw '' "SELECT COUNT(*) FROM aijob_import_test.job_catalog"
Check 'test account still works on its OWN schema' $true $r.Ok 'isolation must not break the suite'

Write-Host ""
Write-Host "=== 2. the PILOT pipeline account cannot change structure ==="
$r = TrySql $pilotUser $pilotPw $pilotSchema "CREATE TABLE isolation_probe (id INT)"
Check 'pilot account CREATE TABLE' $false $r.Ok 'the pipeline imports and queries; it has no DDL'
$r = TrySql $pilotUser $pilotPw $pilotSchema "DROP TABLE job_catalog"
Check 'pilot account DROP TABLE' $false $r.Ok
$r = TrySql $pilotUser $pilotPw $pilotSchema "ALTER TABLE job_catalog ADD COLUMN probe INT"
Check 'pilot account ALTER TABLE' $false $r.Ok
$r = TrySql $pilotUser $pilotPw $pilotSchema "TRUNCATE TABLE job_catalog"
Check 'pilot account TRUNCATE' $false $r.Ok 'TRUNCATE needs DROP, which it does not have'
$r = TrySql $pilotUser $pilotPw $pilotSchema "SELECT COUNT(*) FROM job_catalog"
Check 'pilot account SELECT works' $true $r.Ok 'it must still be able to do its job'

Write-Host ""
Write-Host "=== 3. the PILOT account cannot reach the test schema ==="
$r = TrySql $pilotUser $pilotPw '' "SELECT COUNT(*) FROM aijob_import_test.job_catalog"
Check 'pilot account SELECT on test schema' $false $r.Ok 'the boundary runs both ways'

Write-Host ""
Write-Host "=== 4. the migration account has DDL, and only where it should ==="
$r = TrySql $adminUser $adminPw $pilotSchema "CREATE TABLE isolation_probe (id INT)"
Check 'admin account CREATE TABLE on pilot' $true $r.Ok
if ($r.Ok) { [void](TrySql $adminUser $adminPw $pilotSchema "DROP TABLE isolation_probe") }
$r = TrySql $adminUser $adminPw '' "SELECT COUNT(*) FROM aijob_import_test.job_catalog"
Check 'admin account reach into the test schema' $false $r.Ok

Write-Host ""
Write-Host "=== 5. a missing pilot config fails; it does not fall back ==="
$tmpHome = Join-Path $env:TEMP ("aijob-nocfg-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmpHome -Force | Out-Null
Copy-Item (Join-Path $here 'replay-to-pilot.ps1') $tmpHome
$prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
$out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $tmpHome 'replay-to-pilot.ps1') 2>&1 | Out-String
$code = $LASTEXITCODE
$ErrorActionPreference = $prev
Remove-Item $tmpHome -Recurse -Force -ErrorAction SilentlyContinue
Check 'replay refuses without .env.pilot-db' 1 $code
Check 'and says it will not fall back' $true ($out -match 'does NOT fall back')
Check 'no test credential was substituted' $false ($out -match 'aijob_import_test')

Write-Host ""
Write-Host "=== summary ==="
Write-Host "  $($PASS + $FAIL) checks, $PASS passed, $FAIL failed"
if ($FAIL -gt 0) { exit 1 }
