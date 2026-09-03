# Fingerprints the pilot schema: row counts, the posting identity set, and a content
# checksum.
#
#   .\infra\pilot-db\pilot-fingerprint.ps1 [-Label before]
#
# Taken before and after the destructive test suite runs. Comparing the two is what turns
# "the test should not touch the pilot schema" into evidence that it did not.
#
# The checksum covers CONTENT, not just counts: a suite that deleted a row and inserted a
# different one would leave the count alone. It is ordered by identity so it does not
# depend on how the server happens to return rows.
#
# Read with the PIPELINE account, deliberately -- the same account the importer uses. If
# that account could not read this, the isolation would be in the wrong place.

[CmdletBinding()]
param(
    [string]$Label = 'fingerprint',
    [string]$MysqlContainer = 'mysql-test',
    [string]$Network = 'aijob-mysqltest_default',
    [string]$OutDir = ''
)

$ErrorActionPreference = 'Stop'
$env:PATH = "$env:ProgramFiles\Docker\Docker\resources\bin;$env:PATH"

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$pilotEnv = Join-Path $here '.env.pilot-db'
if (-not (Test-Path $pilotEnv)) { Write-Host "MISSING $pilotEnv"; exit 1 }

$pilot = @{}
foreach ($line in Get-Content $pilotEnv) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#')) { continue }
    $n, $v = $t -split '=', 2
    $pilot[$n.Trim()] = $v
}
$schema = $pilot['AIJOB_PILOT_SCHEMA']
$user = $pilot['AIJOB_PILOT_DB_USERNAME']
$pw = $pilot['AIJOB_PILOT_DB_PASSWORD']

if (-not $OutDir) { $OutDir = Join-Path $env:TEMP 'aijob-pilot-fingerprints' }
New-Item -ItemType Directory -Path $OutDir -Force | Out-Null

function Sql($q) {
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $out = & docker run --rm --network $Network -e "MYSQL_PWD=$pw" mysql:8.4 `
        mysql -h $MysqlContainer -u"$user" -D $schema --batch --skip-column-names -e $q 2>&1
    $ErrorActionPreference = $prev
    return $out
}

# Counts.
$counts = Sql @"
SELECT CONCAT_WS('|',
  (SELECT COUNT(*) FROM job_sources),
  (SELECT COUNT(*) FROM job_catalog),
  (SELECT COUNT(*) FROM job_source_records),
  (SELECT COUNT(*) FROM job_ingest_runs),
  (SELECT COUNT(*) FROM job_catalog WHERE status='ACTIVE'),
  (SELECT COUNT(DISTINCT apply_url) FROM job_catalog));
"@
$parts = ("$($counts | Select-Object -First 1)").Trim() -split '\|'
if ($parts.Count -lt 6) { Write-Host "could not read counts:"; $counts | Select-Object -First 3; exit 1 }

# The identity set plus a content hash per posting, ordered so the digest is stable.
# description_text is included: a suite that rewrote bodies while keeping identities
# would otherwise pass.
$rows = Sql @"
SELECT MD5(GROUP_CONCAT(line ORDER BY line SEPARATOR '\n')) FROM (
  SELECT CONCAT_WS('~', s.provider_type, s.tenant_key, r.external_job_id,
                   MD5(CONCAT_WS('|', c.title, c.company_name, c.location_text,
                                 c.apply_url, c.status, COALESCE(c.country_code,''),
                                 COALESCE(c.region,''), COALESCE(c.city,''),
                                 MD5(c.description_text)))) AS line
    FROM job_source_records r
    JOIN job_sources s ON s.id = r.source_id
    JOIN job_catalog  c ON c.id = r.catalog_job_id
) t;
"@
$contentHash = ("$($rows | Select-Object -First 1)").Trim()

$identities = Sql @"
SELECT CONCAT_WS('/', s.provider_type, s.tenant_key, r.external_job_id)
  FROM job_source_records r JOIN job_sources s ON s.id = r.source_id
 ORDER BY 1;
"@
$idFile = Join-Path $OutDir "$Label-identities.txt"
[System.IO.File]::WriteAllLines($idFile, @($identities | Where-Object { $_ }))
$idHash = (Get-FileHash $idFile -Algorithm SHA256).Hash

$record = [ordered]@{
    label = $Label
    taken_at = (Get-Date).ToUniversalTime().ToString('o')
    schema = $schema
    boards = [int]$parts[0]
    catalog_rows = [int]$parts[1]
    source_records = [int]$parts[2]
    ingest_runs = [int]$parts[3]
    active = [int]$parts[4]
    distinct_apply_urls = [int]$parts[5]
    identity_count = @($identities | Where-Object { $_ }).Count
    identity_sha256 = $idHash
    content_md5 = $contentHash
}
$jsonFile = Join-Path $OutDir "$Label.json"
$record | ConvertTo-Json | Set-Content -Path $jsonFile -Encoding utf8

Write-Host "=== pilot fingerprint [$Label] ==="
foreach ($k in $record.Keys) { Write-Host ("  {0,-22} {1}" -f $k, $record[$k]) }
Write-Host "  saved: $jsonFile"
