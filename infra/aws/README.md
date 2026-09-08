# Public AWS EC2 deployment

HTTPS on ports 80/443 (Caddy + React) reverse-proxies `/api/*` to Spring Boot on the Docker network. MySQL 3306 and backend 8080 are not published.

```
HTTPS public URL
    ↓
Caddy + React  (web)
    ↓ /api
Spring Boot    (backend:8080, internal)
    ↓
MySQL          (mysql:3306, internal)
Spring Boot → persistent uploads volume
```

This package deploys an **empty** database and empty uploads volume. Do not copy a local dump, local `uploads/`, or real resumes onto the server.

## Architecture files

| File | Role |
|---|---|
| `Dockerfile` | Java 21 Spring Boot jar + LibreOffice Writer for PDF |
| `Dockerfile.collector` | Same jar, JRE-only image, non-root, no LibreOffice, no HTTP port |
| `frontend/Dockerfile` | Vite build with `VITE_API_BASE_URL=/api/v1`, then Caddy |
| `infra/aws/docker-compose.ec2.yml` | `mysql`, `backend`, `collector`, `web` |
| `infra/aws/Caddyfile` | HTTPS, `/api` proxy, SPA fallback |
| `infra/aws/.env.aws.example` | Names only; copy to `.env.aws` on the server |
| `infra/aws/mysql-init/01-schema.sql` | Empty-DB `CREATE TABLE` matching current production tables |
| `infra/aws/ec2-bootstrap.sh` | Docker + read-only deploy key |
| `infra/aws/release.sh` | Ordered release: verify SHA, back up, build, migrate, verify, start, smoke |
| `infra/aws/rollback.sh` | Start the previously recorded web + backend images, without rebuilding |
| `infra/aws/db-apply.sh` | Runs one .sql file in the mysql container via a 0600 option file |
| `infra/aws/deploy.sh` | Plain restart when the schema is already in place |
| `infra/aws/backup.sh` | App-user mysqldump + uploads volume tar |
| `schema/job_catalog_phase1.sql` | Additive migration + structure gate for the job catalog tables |
| `schema/job_catalog_phase1_verify.sql` | Read-only structure check; non-zero exit when incomplete |
| `docs/job-catalog.md` | Catalog and collector operations guide |

Published ports: **80/tcp, 443/tcp, 443/udp**. Do not map 8080 or 3306.

## Schema initialization

`schema/*.sql` in the repo is **not** a safe empty-database bootstrap: `jobs_discovery.sql` and `resume_pdf_phase2.sql` are `ALTER TABLE` scripts that fail if `jobs` / `resumes` do not already exist.

First boot uses `infra/aws/mysql-init/01-schema.sql` (MySQL runs `/docker-entrypoint-initdb.d` **only** when `mysql_data` is empty). It creates:

`users`, `resumes`, `jobs`, `tailor_proposals`, `resume_evidence_items`, `job_requirement_profiles`,
`schema_migrations`, `job_sources`, `job_catalog`, `job_source_records`, `job_ingest_runs`, `user_saved_catalog_jobs`

The application user is `MYSQL_USER` (not `root`). Repeatable recovery if the volume already exists and tables are missing:

```bash
./infra/aws/db-apply.sh infra/aws/mysql-init/01-schema.sql
```

Always use `db-apply.sh` rather than typing a `mysql` command yourself. Two things go wrong otherwise:

- `--env-file` feeds **Compose's** variable interpolation. It exports nothing into your shell, so a host-side `-u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}"` expands to empty strings and the connection fails to authenticate. The credentials only exist inside the mysql container.
- Even run inside the container, `-p"$MYSQL_PASSWORD"` puts the password in that container's process list. `db-apply.sh` passes it through a 0600 option file instead.

The password is never echoed, never written into the repo or an image, and no shell tracing is enabled. This is MySQL's own recommended mechanism; it is not absolute — a root shell inside the mysql container could read the option file while the command runs — but the password never reaches argv, a log, or version control.

Do not apply `schema/*.sql` ALTERs to a fresh database. Do not change business DDL.

## Job catalog and ATS collection

User search reads the self-hosted `job_catalog` table. A separate `collector` container
fills that table from public ATS boards (Greenhouse and Lever in this phase). The
collector publishes no port and is only reachable on the Compose network.

Full reference: [docs/job-catalog.md](../../docs/job-catalog.md).

### 1. Release, in order

One command does the whole sequence, and the order is not cosmetic. `collector` and the
catalog routes read tables that do **not** exist on an already-populated volume until
the migration is applied by hand — MySQL runs `mysql-init` only on an *empty* volume.
Start them first and the collector's heartbeat fails, its healthcheck fails, and the
deploy times out with a misleading message.

```bash
./infra/aws/release.sh <commit-sha>
```

It performs, and stops at the first failure:

1. **verify the release commit** — refuses unless `HEAD` is exactly the SHA you passed and the tree is clean
2. **back up** database and uploads (`backup.sh`)
3. **validate** the Compose configuration
4. **record** the currently running images, tagging each by ID as `aijob-<service>:rollback-<stamp>` so a rebuild cannot orphan them, into `infra/aws/releases/<stamp>.env`
5. **build** backend, web and collector — without starting anything
6. **start MySQL only**
7. **migrate** (`schema/job_catalog_phase1.sql`) and then **verify the structure** (`schema/job_catalog_phase1_verify.sql`)
8. **start backend, then web**
9. **start collector**
10. **health checks and read-only smoke**

The `web` image is rebuilt and restarted too. It has to be: the catalog search page
lives there, and shipping a new backend against an old `web` leaves the UI and the API
out of step.

`deploy.sh` remains for a plain restart when the schema is already in place; it now
verifies the structure first and refuses with a pointer to `release.sh` rather than
starting containers that cannot come up healthy.

#### Migration safety

`schema/job_catalog_phase1.sql` is additive and re-runnable: it creates tables only when
missing, adds the FULLTEXT index and the collector lease columns only when missing, and
never drops, renames, or truncates anything.

It is **not atomic** — MySQL commits DDL implicitly, so the statements do not roll back
as a unit, and nothing here should be described as transactional. What it guarantees
instead is that it ends with a structure gate (expected tables, columns, indexes and
foreign keys) and writes its `schema_migrations` row **only if that gate passes**. A
partial run therefore leaves a partial schema and no completion marker; running it
again finishes the job.

That gate exists because `CREATE TABLE IF NOT EXISTS` is a no-op against a table that
already exists with the *wrong* shape — for example one left by an interrupted earlier
attempt. Without it the script would record success over a half-built schema.

Re-check the structure at any time, read-only:

```bash
./infra/aws/db-apply.sh schema/job_catalog_phase1_verify.sql
```

Recovery from a failed migration: read the counts it printed, fix or drop **only** the
incomplete new tables — never `jobs`, `users`, `resumes` or anything else that existed
before — and run the release again.

### 2. Add a legal ATS source

There is no public API for source management, on purpose: a user-supplied URL must
never reach the fetcher. Insert rows directly, using the board's public token as
`tenant_key` (never a full URL). Read the board's terms first.

```sql
INSERT INTO job_sources (provider_type, display_name, tenant_key, enabled, crawl_allowed, terms_url)
VALUES ('GREENHOUSE', 'Example Corp', 'examplecorp', 0, 0, 'https://example.com/terms');
```

A source is fetched only when `enabled = 1` **and** `crawl_allowed = 1`. Set both only
after you have confirmed the board is public, needs no login, and permits automated
access:

```sql
UPDATE job_sources SET enabled = 1, crawl_allowed = 1
 WHERE provider_type = 'GREENHOUSE' AND tenant_key = 'examplecorp';
```

`schema/job_catalog_phase1_sample_sources.sql` seeds two placeholder rows with both
flags off, so applying it collects nothing.

There is also an operator CLI in the collector container, which never leaves a process
running afterwards:

```bash
docker compose -p aijob --env-file infra/aws/.env.aws -f infra/aws/docker-compose.ec2.yml \
  run --rm collector --aijob.sources.list=true
```

`--aijob.sources.add=true` (with `--aijob.sources.provider`, `--aijob.sources.tenant`,
`--aijob.sources.display-name`, `--aijob.sources.terms-url`) creates a source with both
flags off. `--aijob.sources.duplicates=true` lists postings that share a
company+title+location fingerprint — candidates for a human to review, never merged
automatically. Exit status: 0 on success, 2 on bad arguments, 1 on a write failure.

### 3. Do one controlled run before scheduling

Run a single collection in a throwaway container, so nothing is scheduled yet:

```bash
docker compose -p aijob --env-file infra/aws/.env.aws -f infra/aws/docker-compose.ec2.yml \
  run --rm collector --aijob.collector.run-once=true
```

To rehearse one board only, pass its id: `--aijob.collector.source-id=1`.

### 4. Inspect the result

```sql
SELECT id, source_id, status, fetched_count, inserted_count, updated_count,
       unchanged_count, expired_count, failed_count, error_summary
  FROM job_ingest_runs ORDER BY id DESC LIMIT 20;

SELECT status, COUNT(*) FROM job_catalog GROUP BY status;
```

`error_summary` holds a short classified reason only — no response bodies, no keys.

### 5. Enable the schedule

Once a manual run looks right, set `JOB_COLLECTOR_ENABLED=true` in `infra/aws/.env.aws`
and restart just the collector:

```bash
docker compose -p aijob --env-file infra/aws/.env.aws -f infra/aws/docker-compose.ec2.yml \
  up -d --no-deps collector
```

While disabled the container stays up and healthy but idle, so a `false` value is not a
deployment failure.

### 6. Switch search to the local catalog

Set `JOB_SEARCH_PRIMARY_PROVIDER=LOCAL_CATALOG` and restart the backend:

```bash
docker compose -p aijob --env-file infra/aws/.env.aws -f infra/aws/docker-compose.ec2.yml \
  up -d --no-deps backend
```

In this mode the JSearch and Adzuna beans are not created at all, so user search cannot
reach an external job API — not even on zero results or a database error.

This affects the legacy `/api/v1/jobs/search` route. The catalog search page users see
calls `/api/v1/job-catalog/*` and is served either way, so do not reach for this
variable when you mean to roll back — see step 7.

### 7. Rolling back without losing data

**Flipping `JOB_SEARCH_PRIMARY_PROVIDER` back to `JSEARCH` is not a rollback.** That
variable only decides which provider answers the legacy `/api/v1/jobs/search` route.
The search page users actually see is the catalog panel, it lives in the `web` image,
and it calls `/api/v1/job-catalog/*`, which is served regardless of that setting. Flip
the variable alone and everyone stays on the new page, reading a catalog that may be
empty, with no way to reach JSearch.

A rollback restores the previous **web and backend images together**:

```bash
./infra/aws/rollback.sh infra/aws/releases/<stamp>.env
```

It stops the collector, checks that every recorded image tag still exists locally,
prints how many personal jobs were saved from the catalog and asks you to confirm, then
starts the recorded `backend` and `web` with `--no-build` — so Compose cannot rebuild
current source over the image you are trying to return to.

What is kept, deliberately:

- `job_catalog`, `job_source_records`, `job_ingest_runs`, `job_sources`
- `user_saved_catalog_jobs`, the mapping from a catalog posting to the personal `jobs`
  row a user saved. Dropping it would orphan real user data.

No DDL change is needed: the new tables are additive and an older backend ignores them.

One compatibility point that was checked rather than assumed: personal jobs saved from
the catalog carry `source = 'JOB_CATALOG'`, a value the previous release never wrote. In
that release `source` is a plain string on both the entity and the API DTO, and the
frontend renders an unrecognised source through an i18n lookup with the raw value as its
default. So those rows display as the literal text `JOB_CATALOG` — cosmetically odd,
not an error. `rollback.sh` counts them and pauses so you can confirm this against the
exact version you are going back to.

Then keep collection off:

```
JOB_COLLECTOR_ENABLED=false
```

### 8. Never destroy volumes

Do **not** run `docker compose down -v`, and never delete `mysql_data` or
`uploads_data`. Use `up -d --no-deps <service>` for restarts, or `down` without `-v`.

### 9. Size expectations

This instance is a t3.medium with 30 GB. Phase 1 targets **50k–200k** `ACTIVE`/`EXPIRED`
postings. It is not sized for millions of jobs; see
[docs/job-catalog.md](../../docs/job-catalog.md) for the scale-out triggers.

## Storage

| Volume | Mount | Contents |
|---|---|---|
| `mysql_data` | `/var/lib/mysql` | MySQL data |
| `uploads_data` | `/data/uploads` | Resumes and tailored DOCX/PDF (`AIJOB_STORAGE_ROOT`) |
| `caddy_data` | `/data` | ACME certificates |
| `caddy_config` | `/config` | Caddy config state |

## EC2 target (AWS console)

Create these resources yourself in the console. Do not paste AWS access keys, database passwords, JWT, OpenAI, or JSearch keys into chat.

1. Region: **ca-central-1**
2. AMI: **Ubuntu Server 24.04 LTS** (amd64)
3. Instance: **t3.medium**
4. Storage: **30 GB gp3**
5. Allocate and associate an **Elastic IP**
6. Security group inbound:
   - **80/tcp** from `0.0.0.0/0`
   - **443/tcp** from `0.0.0.0/0`
   - **22/tcp** from **your current public IPv4 only**
   - Do **not** open **8080** or **3306**
7. Key pair: your SSH key

Suggested hostname without a custom domain:

```text
<Elastic-IP>.sslip.io
```

Example shape only: `203.0.113.10.sslip.io` (use the real Elastic IP).

## Commands after the instance exists

SSH as `ubuntu`, then:

```bash
Copy `infra/aws/ec2-bootstrap.sh` to the instance (private repo; do not rely on raw.githubusercontent.com), then:

```bash
sudo bash ./ec2-bootstrap.sh
```

Add the printed **public** key to GitHub → repo **Settings → Deploy keys → Add deploy key** (read-only, no write). Do not commit the private key.

```bash
export GIT_SSH_COMMAND='ssh -i ~/.ssh/ai-job-assistant-deploy -o IdentitiesOnly=yes'
git clone -b deploy/aws-ec2-public git@github.com:<github-owner>/ai-job-assistant.git /opt/ai-job-assistant
cd /opt/ai-job-assistant
cp infra/aws/.env.aws.example infra/aws/.env.aws
nano infra/aws/.env.aws
```

Fill every empty secret on the server. Required names:

- `PUBLIC_HOST` — `<Elastic-IP>.sslip.io`
- `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`
- `AIJOB_DB_URL`, `AIJOB_DB_USERNAME`, `AIJOB_DB_PASSWORD` (app user, not root)
- `AIJOB_JWT_SECRET` (at least 32 bytes) — backend only; the collector is not given it
- `AIJOB_JWT_EXPIRATION`
- `OPENAI_API_KEY` — backend only
- `JOB_SEARCH_PRIMARY_PROVIDER` — `LOCAL_CATALOG` serves the legacy `/jobs/search` route from the local catalog. This is **not** a rollback switch; see step 7.
- `JSEARCH_API_KEY` — may stay empty while `JOB_SEARCH_PRIMARY_PROVIDER=LOCAL_CATALOG`
- `JOB_COLLECTOR_ENABLED` — keep `false` until you have read [docs/job-catalog.md](../../docs/job-catalog.md)
- `JOB_COLLECTOR_CONTACT_EMAIL` — a real mailbox; it is sent to job boards in the `User-Agent`
- `JOB_COLLECTOR_LOCK_TIMEOUT_MINUTES` — how long one collector process may hold a source's lease (default 60)

The `collector` service receives **database credentials only**. It signs no token, calls
no model and calls no job-search API, so `AIJOB_JWT_SECRET`, `OPENAI_API_KEY` and
`JSEARCH_API_KEY` are deliberately not in its environment; `application-collector.yml`
supplies inert placeholders for the shared beans that demand a value. That shrinks what
a compromised collector could leak — it is not full component isolation, because those
beans still exist in the context, merely unreachable.
- `AIJOB_STORAGE_ROOT` (keep `/data/uploads`)
- `AIJOB_CORS_ALLOWED_ORIGINS` — public site origin **including the scheme**, for example `https://203.0.113.10.sslip.io`. Comma-separate additional origins. Do not use `*`.
- `SERVER_FORWARD_HEADERS_STRATEGY` — keep `framework` so Spring honors Caddy `X-Forwarded-Proto`

Keep `AIJOB_TAILOR_ENGINE=V3` so Quick Optimize (BASIC) and Enhanced are both available.

```bash
chmod +x infra/aws/*.sh
./infra/aws/release.sh "$(git rev-parse HEAD)"
```

On a first, empty deployment `deploy.sh` is equivalent, because `mysql-init` builds the
full schema on an empty volume. Use `release.sh` on any instance that already has data.

Wait until `web` is up, then open `https://<PUBLIC_HOST>/`. Caddy requests a Let's Encrypt certificate via HTTP-01 on port 80.

Backup later:

```bash
./infra/aws/backup.sh
```

## Local compose check

From the repo root, with Docker installed:

```bash
docker compose -p aijob --env-file infra/aws/.env.aws.example -f infra/aws/docker-compose.ec2.yml config
```

A live local smoke test must use a **throwaway** Compose project name, placeholder secrets that are not production keys, and must not call real OpenAI/JSearch. Stop the project afterward:

```bash
docker compose -p aijob-smoke --env-file /tmp/aijob-smoke.env -f infra/aws/docker-compose.ec2.yml down
```

## Frontend API

The production image builds with `VITE_API_BASE_URL=/api/v1` so the browser talks to the same origin. Caddy forwards `/api/*` to `backend:8080` without stripping the path (`/api/v1/auth/login` stays `/api/v1/auth/login`). Frontend endpoints are relative (`/auth/login`); do not prefix them with `/api/v1` again.

Browsers send an `Origin` header on JSON POSTs even for same-origin pages. Set `AIJOB_CORS_ALLOWED_ORIGINS` to the public HTTPS origin **including the scheme** (`https://<PUBLIC_HOST>`). Multiple origins are comma-separated. Do not use `*` origins or patterns. JWT is in `Authorization`; `allowCredentials` stays false. Do not treat a 403 on `/api/v1/auth/register` as a reason to widen `permitAll`.

Caddy sets `X-Forwarded-Proto`. Keep `SERVER_FORWARD_HEADERS_STRATEGY=framework` so Spring treats the request as HTTPS behind the proxy.

Read-only public smoke (no accounts, no OpenAI/JSearch):

```bash
./infra/aws/smoke.sh "$PUBLIC_HOST"
```
