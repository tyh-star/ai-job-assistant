# Self-hosted FreeHire — feasibility audit (read-only)

Audit only. No production change, no interface switch, nothing committed.

## Pinned upstream

| | |
|---|---|
| Repo | https://github.com/strelov1/freehire |
| Local clone | `C:\ai job\freehire-upstream` (fresh, was absent before) |
| **Pinned SHA** | `1c7b51e812478d8a873862b190c4ca94cad26c11` |
| Commit date | 2026-09-01 (branch `main`) |
| License | **MIT** — self-hosting and modification permitted; keep `LICENSE` and attribution |
| Stack | Go 1.25 + Fiber v2, PostgreSQL 18 (pgvector), sqlc, Meilisearch 1.49, Redis, Docker Compose |

Upstream production runs bare-metal systemd (`deploy/`, 46 units + 283 per-board timers),
not Compose. `docker-compose.yml` is the dev topology and is what a self-host starts from.

## Minimal service set for our use case

We need **search + detail reads only**. Not accounts, not CV, not the SvelteKit UI.

| Service | Needed? | Why |
|---|---|---|
| `db` (pgvector/pgvector:pg18) | **yes** | job storage; migration 0092 needs `CREATE EXTENSION vector`, so stock postgres will not do |
| `redis` | **yes** | upstream calls it "a required dependency, not an optional one" (backs `internal/ratelimit`) |
| `meilisearch` | **yes for us** | `/jobs/search` is served from Meili; an empty `MEILI_MASTER_KEY` disables the search endpoint without breaking the rest |
| `app` | **yes** | the Go API |
| `minio` + `minio-init` | no (but blocks) | résumé upload only; `app` has `depends_on: minio-init: service_completed_successfully`, so dropping it needs a compose override |
| `web` (SvelteKit SSR) | **no** | we keep our own React frontend |

Schema: `migrations/` (160 files, ~6.5k lines) is mounted at
`/docker-entrypoint-initdb.d` and applied by Postgres **on first volume init only**.
Afterwards it is `cmd/migrate` (which reads `/app/migrations` inside the image).

Server boot requires `DATABASE_URL`, `JWT_SECRET` (≥32 bytes, fail-fast), `PORT`,
`FRONTEND_ORIGIN`, `REDIS_URL`; `MEILI_URL` + `MEILI_MASTER_KEY` for search.
LLM (`LLM_BASE_URL`/`LLM_API_KEY`/`LLM_MODEL`) and `PII_FILTER_URL` are empty by
default and degrade gracefully — we leave them empty.

**Packaging gap found:** the `Dockerfile` builds `hire, ingest, enrich, reindex,
tg-ingest, tg-extract, backfill-derive, liveness, notify, import-collections,
recount-companies, migrate` — **`search-drain` is not among them.** Upstream queues
each ingest write into `search_outbox` and expects `cmd/search-drain` to push it into
the live index every 1–2 min. Docker-only, we either add it to the Dockerfile or run
periodic full `reindex` (a rebuild is 90–180 s per push at upstream scale and
transiently holds ~2x index disk).

## Read API we would consume

Public, unauthenticated, rate-limited:

| Route | Use |
|---|---|
| `GET /api/v1/jobs/search` | faceted + keyword search; descriptions are the index's ≤1000-rune preview |
| `GET /api/v1/agent/jobs/search` | **same query, full verbatim descriptions** rehydrated from Postgres; `description_format` selectable |
| `GET /api/v1/jobs/{slug}` | detail, full description |
| `GET /health` | health check |

`agent/jobs/search` is the one that matters for us: Match/Tailor need the full JD text,
and this returns it in one call instead of N detail fetches.

Jobs are addressed by `public_slug`, not an internal id.

## Sources

`sources/` ships **~200 provider YAML files** listing company boards — e.g.
`greenhouse.yml` 14,189 lines, `workday.yml` 13,161, `paylocity.yml` 18,957,
`bamboohr.yml` 19,287. `docs/sources.md` states the live catalogue is 3,300,615 open
postings across 294,282 companies from 225 sources.

**No source discovery is needed to start.** `cmd/harvest-*` (ats, boards, linkedin,
loxo, orphans, role, erecruiter) exists to find *new* boards later.

Credential-gated sources are few and explicit — only these env names are read by any
adapter:

| Source | Env | Note |
|---|---|---|
| Adzuna | `ADZUNA_APP_ID` + `ADZUNA_APP_KEY` | both required or the provider is not registered |
| Reed (UK) | `REED_API_KEY` | HTTP Basic |
| USAJobs | `USAJOBS_API_KEY` | free, US federal |
| WhatJobs | `WHATJOBS_PUBLISHER_IDS` | vendor-issued publisher account |
| (optional) | `SOURCES_PROXY_URL` | outbound proxy |

Everything else — Greenhouse, Lever, Ashby, Workday, SmartRecruiters, Workable,
Recruitee, Personio, BambooHR, Teamtailor, iCIMS … — needs **no credential**.

## Resource risk on a 4 GB EC2

Measured **by upstream, at their full-catalogue scale** (not by us):

- ~2.7M-document Meili index; every push re-merges the whole inverted index —
  **90–180 s per push regardless of batch size**
- a rebuild also builds HNSW skill vectors: **~+10 GB index**, and a swap transiently
  holds ~2x index disk
- `jobs` at ~8M rows; `backfill-derive` ~15 h; aggregator-suppression dedup ~23 h

**Estimated for us (not measured):** 4 GB has to hold Postgres + Meilisearch + Redis +
the Go app simultaneously. Meilisearch wants its working set resident. Full catalogue is
plainly out of reach; a deliberately scoped subset of `sources/*.yml` is the only viable
shape, and the scoping factor is unknown until measured. The skill embedder is
`userProvided` (no model runs at index time), so that specific cost is avoided.

## Integration seam (design only, not implemented)

Thinnest Java change: one new `JobSearchProvider` implementation calling our own
FreeHire over the compose network, mapping its wire shape onto the **existing**
`JobCatalog*` DTOs so `JobCatalogController` and `JobCatalogSearch.tsx` keep their
contract. Save still writes a personal `jobs` snapshot and goes through the existing
Match/Tailor path. Auth, JWT, résumés, V2/V3, Enhanced, Writer unchanged. The current
`job_catalog` tables and Java collector stay in place — no table dropped, no production
data migrated.

## Environment blocker

No Docker, no Docker Compose, no Go, no psql on this machine. Nothing was installed and
no container test was run, so **nothing here is container-verified**.
