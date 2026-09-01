# Self-hosted job catalog and ATS collection (Phase 1)

User job search reads a catalog this deployment collected itself. There is no
outbound job-search API call in the request path. A separate background container
collects postings from public ATS boards, normalizes and de-duplicates them, and
writes them to `job_catalog`.

Phase 1 supports **Greenhouse** and **Lever**. The catalog holds *indexed public
postings* — the boards an operator explicitly configured — not "every job in the
world".

## Data flow

```text
job_sources  (operator-approved public boards)
    │  collector container, on a schedule or a single-shot run
    ▼
GreenhouseBoardAdapter / LeverBoardAdapter  →  NormalizedJob
    │  upsert + de-duplication + soft expiry
    ▼
job_catalog          shared, normalized postings
job_source_records   one row per (source, external job), content hash, raw payload
job_ingest_runs      one row per collection attempt, counts + classified error
    │
    │  GET /api/v1/job-catalog/search   (JWT, local reads only)
    ▼
frontend search page
    │  POST /api/v1/job-catalog/{id}/save
    ▼
jobs                     the user's own snapshot (unchanged table, unchanged IDs)
user_saved_catalog_jobs  maps (user, catalog job) → personal job id
    │
    ▼
existing Match / Quick Optimize / Enhanced Tailor, by personal job id
```

## Tables

`jobs` keeps its current meaning: jobs a user created, imported, or saved. It is not
turned into a shared pool, and no existing job ID changes.

| Table | Holds |
|---|---|
| `job_sources` | Provider type, public tenant key, interval, `enabled`, `crawl_allowed`, terms URL, failure counters |
| `job_catalog` | Normalized shared postings, `status` in `ACTIVE`/`EXPIRED`/`REMOVED`, `fingerprint`, normalized URL columns |
| `job_source_records` | `(source_id, external_job_id)` unique, content hash, raw payload, first/last seen, link to a catalog row |
| `job_ingest_runs` | Start/finish, status, fetched/inserted/updated/unchanged/expired/failed counts, short `error_summary` |
| `user_saved_catalog_jobs` | `(user_id, catalog_job_id)` unique, plus the `personal_job_id` |

`job_catalog` also stores `canonical_url_norm` and `apply_url_norm`: scheme-, `www`-,
query- and trailing-slash-stripped copies used only for indexed de-duplication
lookups. The display columns keep the original clickable URLs.

Every posting keeps its attribution: `source`, `canonical_url`, `apply_url`,
`first_seen_at`, `last_seen_at`.

Foreign keys are deliberately conservative. `user_saved_catalog_jobs.catalog_job_id`
is `ON DELETE RESTRICT`, so removing a catalog row can never cascade into a user's own
`jobs` rows.

## Migration safety

`schema/job_catalog_phase1.sql` is additive only. It creates tables with
`CREATE TABLE IF NOT EXISTS`, adds the FULLTEXT index only when it is missing, and
records itself in `schema_migrations`, so re-running it is a no-op. It never drops,
renames, or truncates anything.

MySQL executes `infra/aws/mysql-init` only on an empty volume, so an existing
production database needs the migration applied by hand — after `infra/aws/backup.sh`.
`infra/aws/mysql-init/01-schema.sql` carries the same tables for a fresh volume.

Step-by-step commands: [infra/aws/README.md](../infra/aws/README.md).

## Collection

The collector is the same Java jar as the API, started with the `collector` Spring
profile and no servlet stack (`Dockerfile.collector`). It has no HTTP surface and
publishes no port.

* `JobCollectorScheduler` — periodic tick, off unless `JOB_COLLECTOR_ENABLED=true`
* `JobCollectorRunner` — single-shot mode: `--aijob.collector.run-once=true`, or
  `--aijob.collector.source-id=N` for one board, then exits
* Only rows with `enabled = 1` **and** `crawl_allowed = 1` are ever fetched;
  `collectSource` re-checks this even when called directly
* Sources are grouped by provider so each board host is read strictly sequentially,
  and at most `JOB_COLLECTOR_MAX_CONCURRENCY` hosts are read at once
* Each source gets its own `job_ingest_runs` row; one failing board does not affect
  the others
* Requests use a timeout, a descriptive `User-Agent` with an operator contact address,
  and exponential backoff with jitter; repeated failures back a source off, capped so
  it is still retried at least daily

### Greenhouse

`GET https://boards-api.greenhouse.io/v1/boards/{tenant_key}/jobs?content=true`.
`tenant_key` is the public board token. HTML content is sanitized and a plain-text
version is extracted for search.

### Lever

`GET https://api.lever.co/v0/postings/{tenant_key}?mode=json`, paged. `createdAt` is
epoch milliseconds; the `lists` section (requirements and similar) is folded into the
description.

### Adding a provider later

Implement `JobBoardAdapter` (`providerType()` and `fetch(JobSource) → List<NormalizedJob>`),
add the enum value to `AtsProviderType`, and add the official hosts to
`AtsHostAllowlist`. Nothing else in ingestion, search, or the frontend changes. Ashby,
SmartRecruiters, Workday and generic `JobPosting` pages fit this contract.

## Upsert, de-duplication, expiry

**Within a source**, `(source_id, external_job_id)` is the identity. The stored hash
is of *normalized content*, not the raw payload, because boards bump timestamps and
tracking fields on every fetch:

* same hash → `unchanged_count + 1`, timestamps touched, and an expired posting that
  reappeared is restored to `ACTIVE`
* different hash → source record and catalog row updated, `updated_count + 1`
* no record → both created, `inserted_count + 1`

**Across sources**, in order:

1. equal normalized apply URL
2. equal normalized canonical URL
3. equal `company + title + location` SHA-256 fingerprint

If none match, a second row is kept. Merging on title alone is not allowed, and the
fingerprint step is skipped entirely for postings with no location, because
company + title routinely describes several genuinely different openings.

**Expiry** is soft; nothing is physically deleted. A posting is only expired by a
complete, successful run, and only after it has been missing for longer than
`JOB_EXPIRY_GRACE_HOURS` (default 72). If any posting in a run was unusable, that run
expires nothing. A posting still published by another source stays `ACTIVE`. A posting
that reappears is restored.

Failed runs never expire anything: an unreachable board is not evidence that its jobs
are gone.

## Search

All routes require a JWT, exactly like the rest of `/api/v1` outside
`/api/v1/auth/**`.

| Route | Purpose |
|---|---|
| `GET /api/v1/job-catalog/search` | `q`, `location`, `country`, `remoteType`, `employmentType`, `postedWithinDays`, `page`, `size`, `sort` |
| `GET /api/v1/job-catalog/{id}` | Detail, with sanitized HTML |
| `POST /api/v1/job-catalog/{id}/save` | Copy into the caller's own `jobs`, idempotent |

Pagination is server-side and `size` is capped. Only `ACTIVE` postings are returned by
default, sorted by relevance and `posted_at`. Keyword search uses MySQL InnoDB
FULLTEXT on `(title, company_name, description_text)`; `SqlDialect` detects the
database at startup and falls back to `LIKE` on H2 in tests. Raw payloads are never
returned to a browser, and description HTML is passed through `HtmlSanitizer` first.

`save` runs in a transaction: it reads the catalog row, creates a personal `jobs` row
in the existing format, and inserts the `user_saved_catalog_jobs` mapping. Saving the
same posting again returns the existing personal job instead of creating a duplicate.
The personal row is a snapshot — later catalog edits do not rewrite it, so Match and
Tailor never see the job text change under them. Expired postings can still be saved;
`REMOVED` ones cannot.

## Why `LOCAL_CATALOG` cannot call JSearch

`JOB_SEARCH_PRIMARY_PROVIDER=LOCAL_CATALOG` prevents the outbound providers from
being *constructed*, not just from being called: `ExternalJobSearchEnabledCondition`
gates the JSearch and Adzuna beans and their HTTP clients. A bean that does not exist
cannot be reached by a fallback, a retry, or a future code path, so the guarantee does
not depend on anyone remembering a convention.

Consequences:

* zero results stay zero results; there is no remote retry
* a database error surfaces as a local search error and never silently falls back
* no JSearch key is needed to boot in this mode
* asking for JSearch by name fails while the mode is active

`LocalCatalogModeIsolationTest` asserts all of this. The JSearch implementation and
its tests are kept for now as rollback code; switching
`JOB_SEARCH_PRIMARY_PROVIDER` back to `JSEARCH` restores the previous behaviour with
no DDL change.

## Compliance boundaries

Only public, login-free, crawl-permitted ATS endpoints are collected. Sources are
operator-configured rows, never user input, and there is no API that lets anyone point
the collector at an arbitrary URL.

`AtsHostAllowlist` enforces this: a `tenant_key` must match
`[A-Za-z0-9][A-Za-z0-9._-]{0,99}` and becomes a single path segment on a hard-coded
official host, and any absolute URL must be `https`, on that provider's official host,
with no embedded credentials and no port override. Internal addresses, `localhost` and
cloud metadata endpoints are therefore unreachable by construction. Redirects are
disabled.

Not done, and not to be added: LinkedIn, Indeed or Glassdoor scraping, CAPTCHA
bypasses, account pools, proxy pools, browser-fingerprint spoofing, or collecting
job-seeker personal data. `error_summary` stores a short classified reason only — never
keys, response bodies, or personal data.

Raw payloads for long-gone postings are purged by housekeeping after
`JOB_COLLECTOR_RAW_PAYLOAD_RETENTION_DAYS` (default 30), while attribution and URLs
survive.

## Scale limits on the current instance

The target for this phase is **50k–200k** `ACTIVE`/`EXPIRED` postings on a t3.medium
with 30 GB. Do not bulk-import millions of jobs.

Guards in place:

| Limit | Setting | Default |
|---|---|---|
| Sources per tick | `JOB_COLLECTOR_BATCH_SIZE` | 100 |
| Postings per source per run | `JOB_COLLECTOR_MAX_JOBS_PER_SOURCE` | 2000 |
| Hosts read concurrently | `JOB_COLLECTOR_MAX_CONCURRENCY` | 2 |
| Description length | `JOB_COLLECTOR_MAX_DESCRIPTION_CHARS` | 100000 |
| Stored payload size | `JOB_COLLECTOR_MAX_RAW_PAYLOAD_CHARS` | 200000 |
| Payload retention | `JOB_COLLECTOR_RAW_PAYLOAD_RETENTION_DAYS` | 30 |
| Run-log retention | `JOB_COLLECTOR_RUN_HISTORY_RETENTION_DAYS` | 30 |
| Collector DB connections | `JOB_COLLECTOR_DB_POOL_MAX` | 3 |
| Search page size | capped server-side | — |

Size monitoring:

```sql
SELECT status, COUNT(*) AS postings FROM job_catalog GROUP BY status;

SELECT table_name,
       ROUND((data_length + index_length) / 1024 / 1024) AS mb
  FROM information_schema.TABLES
 WHERE table_schema = DATABASE()
   AND table_name IN ('job_catalog', 'job_source_records', 'job_ingest_runs')
 ORDER BY mb DESC;
```

Plan a scale-out when any of these is true:

* `job_catalog` passes 300k–500k rows
* catalog search latency degrades noticeably
* EBS usage passes 70%
* the collector and backend start competing for memory

Only then consider a dedicated PostgreSQL, Meilisearch/OpenSearch, Redis, and a
separate collector instance. None of those are part of this phase.
