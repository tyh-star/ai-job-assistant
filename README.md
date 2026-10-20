# AI Job Assistant

A full-stack job-search assistant that analyzes resumes, matches them against real job postings, and generates tailored resumes without inventing experience the candidate doesn't have.

**Stack:** Java 21 · Spring Boot 3.5 · Spring Security + JWT · MyBatis · MySQL · OpenAI API · React 19 · TypeScript · Vite · Tailwind CSS · Docker · AWS EC2

## Features

- **Resume parsing** – Extracts structured content from PDF and DOCX resumes (Apache PDFBox, Apache POI), including nested tables and complex layouts.
- **AI analysis with Structured Outputs** – Uses the OpenAI Responses API with JSON schemas so every analysis comes back in a predictable, validated shape.
- **Evidence-based tailoring** – Rewrites a resume for a specific job while rejecting claims that aren't backed by the candidate's own evidence, and flags inferred skills for the user to confirm.
- **Format-preserving DOCX editing** – Applies targeted edits to the original Word file instead of regenerating it, so fonts, spacing and layout survive. PDF export via LibreOffice.
- **Self-hosted job catalog** – A background collector pulls postings from public Greenhouse and Lever boards, normalizes and de-duplicates them, and serves search from a local catalog.
- **Resume builder, quality scoring and application tracking** – Create resumes from scratch, score them, and keep a history of searches and applications.
- **Bilingual UI** – English and Chinese (i18next).

## Architecture

```text
Browser ──HTTPS──▶ Caddy + React (web)
                        │ /api/*
                        ▼
                  Spring Boot API ──▶ MySQL
                        │
                        ├──▶ OpenAI API
                        └──▶ uploads volume

Collector container ──▶ Greenhouse / Lever boards ──▶ job_catalog (MySQL)
```

- REST API under `/api/v1`, documented with OpenAPI (springdoc).
- Stateless JWT authentication with token versioning, so logins can be revoked server-side.
- The job collector runs as a separate, smaller container (no exposed port, non-root user), with per-host rate limiting and an allowlist of approved job boards.

## Project structure

```text
src/main/java/aijob/   Spring Boot backend (controllers, services, security, collector, tailor engine)
src/test/              Backend unit, integration and regression tests
frontend/              React + TypeScript web app
schema/                MySQL schema and migration scripts
infra/aws/             Docker Compose deployment to EC2 (Caddy, deploy, rollback, backup, smoke tests)
infra/pipeline/        Job-collection pipeline scripts
docs/                  Design notes and configuration reference
```

## Getting started

**Requirements:** Java 21, Maven, Node.js 20+, MySQL 8, an OpenAI API key.

```bash
# 1. Configure environment
cp .env.example .env          # fill in database credentials, JWT secret and OPENAI_API_KEY

# 2. Create the database schema
#    Run the SQL files in schema/ against your MySQL database.

# 3. Start the backend (http://localhost:8080)
mvn spring-boot:run

# 4. Start the frontend (http://localhost:5173)
cd frontend
npm install
npm run dev
```

See [`docs/configuration.md`](docs/configuration.md) for every configuration option.

## Testing

```bash
mvn test                    # backend tests (JUnit, H2)
cd frontend && npm test     # frontend tests (Vitest, Testing Library)
```

The backend suite includes regression tests that check DOCX edits preserve the original document's formatting.

## Deployment

The app is deployed with Docker Compose on AWS EC2 behind Caddy, which handles HTTPS automatically. MySQL and the backend port are only reachable on the internal Docker network. Deploy, rollback, backup and post-deploy smoke-test scripts live in [`infra/aws/`](infra/aws/README.md).
