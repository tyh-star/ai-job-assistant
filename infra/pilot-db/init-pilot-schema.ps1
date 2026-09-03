# Creates the pilot schema and its two accounts on the isolated test MySQL, then builds
# the tables from the same migration files a deployment applies.
#
#   .\infra\pilot-db\init-pilot-schema.ps1
#
# ONLY touches the confirmed local test instance on 127.0.0.1:3307. Never 3306, never AWS.
# An existing schema is inspected, never dropped or recreated.
#
# WHAT ISOLATION THIS DOES AND DOES NOT BUY
# -----------------------------------------
# Two schemas in ONE MySQL instance is LOGICAL separation. It stops the integration suite
# from truncating pilot data, which is the actual problem. It does NOT give the pilot its
# own instance, its own volume, or any independent failure domain: losing the container or
# the volume loses both. Do not describe this as disaster isolation.

[CmdletBinding()]
param(
    [string]$MysqlContainer = 'mysql-test',
    [string]$Network = 'aijob-mysqltest_default'
)

$ErrorActionPreference = 'Stop'
$env:PATH = "$env:ProgramFiles\Docker\Docker\resources\bin;$env:PATH"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = (Resolve-Path (Join-Path $here '..\..')).Path
$pilotEnv = Join-Path $here '.env.pilot-db'
$testEnv = Join-Path $repo 'infra\mysql-test\.env.mysql-test'

foreach ($f in @($pilotEnv, $testEnv)) {
    if (-not (Test-Path $f)) { Write-Host "MISSING $f"; exit 1 }
}

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

$pilot = Read-EnvFile $pilotEnv
$test = Read-EnvFile $testEnv

# The admin connection. root on the TEST CONTAINER only -- the one instance this whole
# setup has ever talked to. It is used here and handed to nothing else.
$rootPw = $test['AIJOB_MYSQL_TEST_ROOT_PASSWORD']
if (-not $rootPw) { Write-Host "No admin credential in $testEnv -- stopping. Not guessing."; exit 1 }

$running = & docker ps --filter "name=$MysqlContainer" --format '{{.Names}}'
if (-not $running) { Write-Host "The test MySQL container is not running."; exit 1 }

function Invoke-Sql {
    param([string]$User, [string]$Password, [string]$Sql, [string]$Database = '')
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $args = @('run', '--rm', '--network', $Network, '-e', "MYSQL_PWD=$Password", 'mysql:8.4',
              'mysql', '-h', $MysqlContainer, "-u$User", '--batch', '--skip-column-names')
    if ($Database) { $args += @('-D', $Database) }
    $args += @('-e', $Sql)
    $out = & docker @args 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    return [pscustomobject]@{ Output = $out; ExitCode = $code }
}

# --- verify the instance before changing anything -----------------------------
$ver = Invoke-Sql -User root -Password $rootPw -Sql "SELECT VERSION()"
if ($ver.ExitCode -ne 0) {
    Write-Host "Cannot reach the test instance as an administrator. Stopping."
    Write-Host ($ver.Output | Select-Object -First 3)
    exit 1
}
$version = ($ver.Output | Select-Object -First 1).ToString().Trim()
Write-Host "instance   : $MysqlContainer, MySQL $version"
if (-not $version.StartsWith('8.4')) { Write-Host "expected 8.4 -- stopping"; exit 1 }

$schema = $pilot['AIJOB_PILOT_SCHEMA']
$existing = (Invoke-Sql -User root -Password $rootPw -Sql `
    "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE schema_name='$schema'").Output |
    Select-Object -First 1
if ("$existing".Trim() -eq '1') {
    $tables = (Invoke-Sql -User root -Password $rootPw -Sql `
        "SELECT COUNT(*) FROM information_schema.TABLES WHERE table_schema='$schema'").Output |
        Select-Object -First 1
    Write-Host "schema     : $schema already exists with $("$tables".Trim()) tables -- inspecting, NOT recreating"
} else {
    Write-Host "schema     : creating $schema"
}

# --- schema and accounts ------------------------------------------------------
# utf8mb4_unicode_ci to match production, so the collation behaviour under test is the
# behaviour deployed. external_job_id gets utf8mb4_0900_bin from the migration itself.
$pilotUser = $pilot['AIJOB_PILOT_DB_USERNAME']
$pilotPw = $pilot['AIJOB_PILOT_DB_PASSWORD']
$adminUser = $pilot['AIJOB_PILOT_ADMIN_USERNAME']
$adminPw = $pilot['AIJOB_PILOT_ADMIN_PASSWORD']
$testUser = $test['AIJOB_MYSQL_TEST_USERNAME']

$ddl = @"
CREATE DATABASE IF NOT EXISTS ``$schema`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS '$adminUser'@'%' IDENTIFIED BY '$adminPw';
ALTER USER '$adminUser'@'%' IDENTIFIED BY '$adminPw';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '$adminUser'@'%';
GRANT ALL PRIVILEGES ON ``$schema``.* TO '$adminUser'@'%';

CREATE USER IF NOT EXISTS '$pilotUser'@'%' IDENTIFIED BY '$pilotPw';
ALTER USER '$pilotUser'@'%' IDENTIFIED BY '$pilotPw';
REVOKE ALL PRIVILEGES, GRANT OPTION FROM '$pilotUser'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON ``$schema``.* TO '$pilotUser'@'%';

FLUSH PRIVILEGES;
"@
# Deliberately NOT granted to the pipeline account: CREATE, ALTER, DROP, INDEX,
# REFERENCES, CREATE TEMPORARY TABLES, LOCK TABLES. It imports and queries; it has no
# business changing the shape of anything, and an account that cannot cannot be made to.
#
# Deliberately NOT granted to EITHER pilot account: anything on aijob_import_test. And
# the test account is not touched here at all -- it keeps exactly the grants it had.

$r = Invoke-Sql -User root -Password $rootPw -Sql $ddl
if ($r.ExitCode -ne 0) {
    Write-Host "Schema/account setup failed:"; $r.Output | Select-Object -First 6; exit 1
}
Write-Host "accounts   : $pilotUser (data only), $adminUser (DDL) -- both scoped to $schema"

# --- tables, from the real migration files ------------------------------------
# Applied as the MIGRATION account, not root and not the pipeline account.
$migrations = @(
    'infra/aws/mysql-init/01-schema.sql',
    'schema/job_catalog_phase1.sql',
    'schema/job_catalog_external_id_exact_identity.sql',
    'schema/job_catalog_external_id_no_pad.sql'
)
foreach ($m in $migrations) {
    $path = Join-Path $repo $m
    if (-not (Test-Path $path)) { Write-Host "MISSING migration $m"; exit 1 }
    $sql = Get-Content $path -Raw
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $out = $sql | & docker run --rm -i --network $Network -e "MYSQL_PWD=$adminPw" mysql:8.4 `
        mysql -h $MysqlContainer -u"$adminUser" -D $schema 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $prev
    if ($code -ne 0) {
        Write-Host "migration FAILED: $m"; $out | Select-Object -First 8; exit 1
    }
    Write-Host "migration  : applied $m"
}

# --- verify the boundary, rather than assuming it -----------------------------
Write-Host ""
Write-Host "=== grants, read back from the server ==="
foreach ($u in @($pilotUser, $adminUser, $testUser)) {
    $g = Invoke-Sql -User root -Password $rootPw -Sql "SHOW GRANTS FOR '$u'@'%'"
    Write-Host "  $u"
    $g.Output | ForEach-Object { "      $_" } | Write-Host
}

Write-Host ""
Write-Host "=== structure ==="
$t = Invoke-Sql -User root -Password $rootPw -Sql `
    "SELECT table_name FROM information_schema.TABLES WHERE table_schema='$schema' ORDER BY table_name"
Write-Host "  tables in $schema : $(($t.Output | Where-Object { $_ }) -join ', ')"

Write-Host ""
Write-Host "NOTE: two schemas in ONE instance is logical separation. It stops the test suite"
Write-Host "      truncating pilot data. It is NOT an independent failure domain -- the same"
Write-Host "      container and the same volume hold both."
