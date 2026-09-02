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

`job_catalog` also stores `canonical_url_norm` and `apply_url_norm`, used only for
indexed de-duplication lookups. They drop the scheme, a leading `www.`, the fragment,
a trailing slash and a fixed list of campaign parameters (`utm_*`, `gh_src`, `gclid`,
`fbclid`, …) — and **keep every other query parameter**, because a job identifier
frequently lives there: `?gh_jid=`, `?token=`, `?jobId=`. `ref` and `source` are kept
too; real boards use them as part of a posting's address.

They are `NULL` when the URL does not name one opening — a company home page,
`/careers`, `/jobs`, a shared application form. Those are the same URL for every
posting on a board, so admitting them as merge keys would collapse a whole board into
one row.

The display columns keep the original clickable URLs.

Every posting keeps its attribution: `source`, `canonical_url`, `apply_url`,
`first_seen_at`, `last_seen_at`.

Foreign keys are deliberately conservative. `user_saved_catalog_jobs.catalog_job_id`
is `ON DELETE RESTRICT`, so removing a catalog row can never cascade into a user's own
`jobs` rows.

## Migration safety

`schema/job_catalog_phase1.sql` is additive only: `CREATE TABLE IF NOT EXISTS`, the
FULLTEXT index and the lease columns added only when missing. It never drops, renames,
or truncates anything, and re-running it is a no-op.

**It is not atomic, and must not be described as such.** MySQL commits DDL implicitly,
so the statements do not roll back as a unit. What the script guarantees instead:

* every statement is additive and re-runnable
* it ends with a **structure gate** — a check of the expected tables, columns, indexes
  and foreign keys — and records `schema_migrations` only if that gate passes
* a partial run therefore leaves a partial schema and **no** completion marker; running
  it again finishes the job

The gate matters because `CREATE TABLE IF NOT EXISTS` is a no-op against a table that
already exists with the *wrong* shape, e.g. one left by an interrupted earlier attempt.
Without it the script would report success over a half-built schema.

`schema/job_catalog_phase1_verify.sql` runs the same gate on its own, read-only, and
exits non-zero when the structure is incomplete. `infra/aws/deploy.sh` calls it before
starting anything, and `infra/aws/release.sh` calls it right after migrating.

Credentials: apply SQL with `infra/aws/db-apply.sh`, which runs the client **inside**
the mysql container using a 0600 option file. `--env-file` does not export anything
into your shell, so `-u"${MYSQL_USER}"` typed on the host expands to nothing; and
`-p"$MYSQL_PASSWORD"` would put the password in the container's process list. The
option file avoids both. It is not absolute — a root shell inside that container could
read the file while it exists — but the password never reaches argv, a log, an image,
or this repository.

MySQL executes `infra/aws/mysql-init` only on an empty volume, so an existing
production database needs the migration applied by hand — after `infra/aws/backup.sh`.
`infra/aws/mysql-init/01-schema.sql` carries the same tables for a fresh volume.

Step-by-step commands: [infra/aws/README.md](../infra/aws/README.md).

## Collection

The collector is the same Java jar as the API, started with the `collector` Spring
profile and no servlet stack (`Dockerfile.collector`). It has no HTTP surface and
publishes no port.

### What the collector is, and is not, isolated from

It receives **database credentials only**. The production JWT signing key, the OpenAI
key and the JSearch key are not in its environment; `application-collector.yml`
supplies inert placeholders for the shared beans that demand a value, scoped strictly
to the `collector` profile.

Be precise about what that buys. It shrinks the blast radius: a compromised collector
has no production secret to leak, cannot mint a token, and cannot spend the model
budget. It is **not** full component isolation — `JwtUtil`, the OpenAI client and the
PDF export service are still constructed in this context. They are simply unreachable:
there is no servlet stack, and no scheduled task calls them.

`CollectorSecretIsolationTest` boots the whole application under the collector profile
with none of those values supplied, and `Ec2ComposeConfigTest` asserts the Compose file
does not hand them back.

### Writes are atomic per posting

Each posting's catalog row, source record and effective links are written in one
transaction, and each retirement during expiry is another. The transactional methods
live on a separate bean (`JobIngestTransactions`) for a specific reason: Spring's
transaction interceptor lives in a proxy, so a `@Transactional` method called from
inside the same object is never intercepted, and the annotation is ignored on
non-public methods entirely. On the same class these boundaries would compile, read
correctly, and do nothing.

Consequences that are covered by tests:

* if the source-record write fails after the catalog row was inserted, the catalog row
  is rolled back — no orphan row for the next run to duplicate
* one unusable posting is rolled back on its own and the rest of the run still commits
* board reads happen **before** any transaction opens, so a hung board never holds a
  transaction or a row lock

* `JobCollectorScheduler` — periodic tick, off unless `JOB_COLLECTOR_ENABLED=true`
* `JobCollectorRunner` — single-shot mode: `--aijob.collector.run-once=true`, or
  `--aijob.collector.source-id=N` for one board, then exits with 0 when every run
  finished and non-zero when a board failed or the id does not exist
* `JobSourceAdminRunner` — operator CLI: `--aijob.sources.list`,
  `--aijob.sources.add`, `--aijob.sources.duplicates`. Each ends the process (0 on
  success, 2 on bad arguments, 1 on a write failure) so a one-off admin command does
  not leave a container behind that then starts collecting on the schedule
* Only rows with `enabled = 1` **and** `crawl_allowed = 1` are ever fetched;
  `collectSource` re-checks this even when called directly
* Sources are grouped by provider so each board host is read strictly sequentially,
  and at most `JOB_COLLECTOR_MAX_CONCURRENCY` hosts are read at once
* Each source gets its own `job_ingest_runs` row; one failing board does not affect
  the others
* Requests use a timeout, a descriptive `User-Agent` with an operator contact address,
  and exponential backoff with jitter; repeated failures back a source off, capped so
  it is still retried at least daily

### One collector at a time, per source

The scheduler holds an `AtomicBoolean` so its own next tick cannot overlap a slow run.
That is **process-local and nothing more** — a manual `run --rm collector` or a
replacement container during a deploy has its own flag and would happily read the same
board at the same moment.

The guard that spans processes is a lease on the source row itself: `job_sources`
carries `locked_by` and `locked_at`, and `collectSource` claims it with a conditional
`UPDATE` before touching the network. Exactly one process wins.

* the loser records the attempt as `SKIPPED` with `SOURCE_LOCKED` and reads nothing.
  `SKIPPED` is deliberately not `FAILED`: the board was fine, and a contended source
  must not accumulate failures and back off
* the lease is released in a `finally`, so a failure or an exception inside the posting
  loop still frees the board
* a lease older than `JOB_COLLECTOR_LOCK_TIMEOUT_MINUTES` (default 60) is taken over,
  so a container killed mid-run cannot block its boards forever
* release is owner-scoped: a process whose lease already expired cannot free the claim
  of whoever took over from it

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

**Across sources**, one rule: an equal *identity URL* — a normalized URL that provably
names a single opening, matched against either URL column because one board's
canonical link is another's apply link.

There is deliberately **no** company+title+location merge. One employer routinely runs
several genuinely different openings with the same title in the same city, each with
its own requisition number, and merging those loses a real job. So:

* two postings with different requisition numbers, identical company, title and
  location, and no URL match → **two rows**
* two postings whose URLs differ only in `?gh_jid=` → **two rows**
* every posting on a board sharing one `/careers` link → **one row each**, because a
  generic page is never an identity URL
* the same posting reached through two boards under one identity URL → **one row**

When there is no strong evidence, ingestion keeps both. The `fingerprint` column is
still written on every row, but only as a *review aid*: `--aijob.sources.duplicates`
lists postings that share one so a human can look. Nothing merges on it.

### Which source owns the displayed links

Every board's own links stay on its `job_source_records` row. The catalog shows exactly
one pair, re-derived after each write by a fixed rule rather than by whoever was
collected last:

1. prefer a source record that is still `active` — so the catalog stops advertising the
   apply link of a board that dropped the posting
2. among those, prefer the earliest seen (lowest id) — so two boards collected in
   either order settle on the same link instead of flipping
3. if every record is retired, the earliest is still used, so an `EXPIRED` posting keeps
   its last known link

A board updating **its own** posting does refresh that record's links: it is the
authority on its own URLs. What it cannot do is overwrite another board's links by
merging into a shared row.

Stored links are checked with `ExternalLinks.displayUrl` first: http/https only, no
embedded credentials, control characters stripped before the scheme is tested. A link
that fails is stored as `NULL` rather than persisted; the posting itself is kept.

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

`LocalCatalogModeIsolationTest` asserts all of this. The JSearch implementation and its
tests are kept as rollback code.

**Switching `JOB_SEARCH_PRIMARY_PROVIDER` back to `JSEARCH` is not a rollback.** It
only changes which provider answers the legacy `/api/v1/jobs/search` route. The search
UI users actually see is the catalog panel in the `web` image, and it calls
`/api/v1/job-catalog/*`, which is served regardless of that setting — so flipping the
variable alone leaves everyone on the new page, reading a catalog that may be empty.

A real rollback restores the previous `web` and `backend` images together. See
[infra/aws/README.md](../infra/aws/README.md) and `infra/aws/rollback.sh`.

## Compliance boundaries

Only public, login-free, crawl-permitted ATS endpoints are collected. Sources are
operator-configured rows, never user input, and there is no API that lets anyone point
the collector at an arbitrary URL.

### Fetch targets versus display links

These are two different problems and are handled by two different rules. Conflating
them either lets the collector be pointed somewhere it should not go, or throws away
most legitimate apply links.

**Fetch targets** — what the collector connects to — are constrained by
`AtsHostAllowlist`. A `tenant_key` must match `[A-Za-z0-9][A-Za-z0-9._-]{0,99}` and
becomes a single path segment appended to a hard-coded official host; the base URL
stored on a source is checked against that provider's official hosts when the source is
created. Redirects are disabled. No URL taken from a board's response is ever
requested.

The intended property is that internal addresses, `localhost` and cloud metadata
endpoints are unreachable. That follows from the code paths above, and
`AtsHostAllowlistTest` covers the allowlist itself — but it has **not** been exercised
against a live network from inside a container, so treat it as "no code path
constructs such a request", not as a verified "SSRF risk is zero".

**Display links** — `canonical_url` and `apply_url` — are not held to that allowlist,
and must not be. A posting's apply link legitimately points at the employer's own site,
an assessment vendor or a government portal. `ExternalLinks.displayUrl` checks what
actually matters for a stored link that a browser will later render: http/https only
(so `javascript:` and `data:` can never reach an `href`), control characters removed
before the scheme is tested, and no embedded credentials. Description HTML goes through
`HtmlSanitizer`, which independently drops any non-http(s) `href`.

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
| Source lease timeout | `JOB_COLLECTOR_LOCK_TIMEOUT_MINUTES` | 60 |
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
