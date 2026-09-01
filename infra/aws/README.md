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
| `infra/aws/deploy.sh` | Build and start |
| `infra/aws/backup.sh` | App-user mysqldump + uploads volume tar |
| `schema/job_catalog_phase1.sql` | Additive migration for the job catalog tables |
| `docs/job-catalog.md` | Catalog and collector operations guide |

Published ports: **80/tcp, 443/tcp, 443/udp**. Do not map 8080 or 3306.

## Schema initialization

`schema/*.sql` in the repo is **not** a safe empty-database bootstrap: `jobs_discovery.sql` and `resume_pdf_phase2.sql` are `ALTER TABLE` scripts that fail if `jobs` / `resumes` do not already exist.

First boot uses `infra/aws/mysql-init/01-schema.sql` (MySQL runs `/docker-entrypoint-initdb.d` **only** when `mysql_data` is empty). It creates:

`users`, `resumes`, `jobs`, `tailor_proposals`, `resume_evidence_items`, `job_requirement_profiles`,
`schema_migrations`, `job_sources`, `job_catalog`, `job_source_records`, `job_ingest_runs`, `user_saved_catalog_jobs`

The application user is `MYSQL_USER` (not `root`). Repeatable recovery if the volume already exists and tables are missing:

```bash
docker compose -p aijob --env-file infra/aws/.env.aws -f infra/aws/docker-compose.ec2.yml exec -T mysql \
  mysql -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" "${MYSQL_DATABASE}" \
  < infra/aws/mysql-init/01-schema.sql
```

Do not apply `schema/*.sql` ALTERs to a fresh database. Do not change business DDL.

## Job catalog and ATS collection

User search reads the self-hosted `job_catalog` table. A separate `collector` container
fills that table from public ATS boards (Greenhouse and Lever in this phase). The
collector publishes no port and is only reachable on the Compose network.

Full reference: [docs/job-catalog.md](../../docs/job-catalog.md).

### 1. Back up first

Never apply the migration to production without a fresh backup:

```bash
./infra/aws/backup.sh
```

### 2. Apply the additive migration

`schema/job_catalog_phase1.sql` only creates new tables and is safe to re-run; it never
drops, renames, or truncates anything, and it records itself in `schema_migrations`.
MySQL runs `mysql-init` only on an empty volume, so an existing database needs this
applied by hand:

```bash
docker compose -p aijob --env-file infra/aws/.env.aws -f infra/aws/docker-compose.ec2.yml exec -T mysql \
  mysql -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" "${MYSQL_DATABASE}" \
  < schema/job_catalog_phase1.sql
```

Confirm it registered:

```sql
SELECT version, applied_at FROM schema_migrations ORDER BY applied_at;
```

### 3. Add a legal ATS source

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

### 4. Do one controlled run before scheduling

Run a single collection in a throwaway container, so nothing is scheduled yet:

```bash
docker compose -p aijob --env-file infra/aws/.env.aws -f infra/aws/docker-compose.ec2.yml \
  run --rm collector --aijob.collector.run-once=true
```

To rehearse one board only, pass its id: `--aijob.collector.source-id=1`.

### 5. Inspect the result

```sql
SELECT id, source_id, status, fetched_count, inserted_count, updated_count,
       unchanged_count, expired_count, failed_count, error_summary
  FROM job_ingest_runs ORDER BY id DESC LIMIT 20;

SELECT status, COUNT(*) FROM job_catalog GROUP BY status;
```

`error_summary` holds a short classified reason only — no response bodies, no keys.

### 6. Enable the schedule

Once a manual run looks right, set `JOB_COLLECTOR_ENABLED=true` in `infra/aws/.env.aws`
and restart just the collector:

```bash
docker compose -p aijob --env-file infra/aws/.env.aws -f infra/aws/docker-compose.ec2.yml \
  up -d --no-deps collector
```

While disabled the container stays up and healthy but idle, so a `false` value is not a
deployment failure.

### 7. Switch search to the local catalog

Set `JOB_SEARCH_PRIMARY_PROVIDER=LOCAL_CATALOG` and restart the backend:

```bash
docker compose -p aijob --env-file infra/aws/.env.aws -f infra/aws/docker-compose.ec2.yml \
  up -d --no-deps backend
```

In this mode the JSearch and Adzuna beans are not created at all, so user search cannot
reach an external job API — not even on zero results or a database error.

### 8. Rolling back without losing data

Set `JOB_COLLECTOR_ENABLED=false` to stop collecting, and
`JOB_SEARCH_PRIMARY_PROVIDER=JSEARCH` to put search back on the previous provider, then
restart `backend` and `collector`. Leave the new tables in place: they hold the collected
catalog and the `user_saved_catalog_jobs` mapping, and dropping them would break saved
jobs. A rollback never needs a DDL change.

### 9. Never destroy volumes

Do **not** run `docker compose down -v`, and never delete `mysql_data` or
`uploads_data`. Use `up -d --no-deps <service>` for restarts, or `down` without `-v`.

### 10. Size expectations

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
git clone -b deploy/aws-ec2-public git@github.com:tyh-star/ai-job-assistant.git /opt/ai-job-assistant
cd /opt/ai-job-assistant
cp infra/aws/.env.aws.example infra/aws/.env.aws
nano infra/aws/.env.aws
```

Fill every empty secret on the server. Required names:

- `PUBLIC_HOST` — `<Elastic-IP>.sslip.io`
- `MYSQL_DATABASE`, `MYSQL_USER`, `MYSQL_PASSWORD`, `MYSQL_ROOT_PASSWORD`
- `AIJOB_DB_URL`, `AIJOB_DB_USERNAME`, `AIJOB_DB_PASSWORD` (app user, not root)
- `AIJOB_JWT_SECRET` (at least 32 bytes)
- `AIJOB_JWT_EXPIRATION`
- `OPENAI_API_KEY`
- `JOB_SEARCH_PRIMARY_PROVIDER` — `LOCAL_CATALOG` serves search from the local catalog; `JSEARCH` is the rollback value
- `JSEARCH_API_KEY` — may stay empty while `JOB_SEARCH_PRIMARY_PROVIDER=LOCAL_CATALOG`
- `JOB_COLLECTOR_ENABLED` — keep `false` until you have read [docs/job-catalog.md](../../docs/job-catalog.md)
- `JOB_COLLECTOR_CONTACT_EMAIL` — a real mailbox; it is sent to job boards in the `User-Agent`
- `AIJOB_STORAGE_ROOT` (keep `/data/uploads`)
- `AIJOB_CORS_ALLOWED_ORIGINS` — public site origin **including the scheme**, for example `https://203.0.113.10.sslip.io`. Comma-separate additional origins. Do not use `*`.
- `SERVER_FORWARD_HEADERS_STRATEGY` — keep `framework` so Spring honors Caddy `X-Forwarded-Proto`

Keep `AIJOB_TAILOR_ENGINE=V3` so Quick Optimize (BASIC) and Enhanced are both available.

```bash
chmod +x infra/aws/deploy.sh infra/aws/backup.sh
./infra/aws/deploy.sh
```

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
