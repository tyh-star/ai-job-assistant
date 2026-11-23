# AI Job Assistant

Job-search assistant that parses PDF/DOCX resumes, matches them to job postings, and tailors a resume for a job **without inventing experience**. Spring Boot backend in `src/main/java/aijob/`, React frontend in `frontend/`, MySQL. Setup is in `README.md`; every setting is in `docs/configuration.md`.

## Commands

| What | Command |
|---|---|
| Backend tests (JUnit 5 + H2) | `mvn test` |
| One test class | `mvn test "-Dtest=V3EntryRewriteValidatorTest"` |
| Frontend tests (Vitest) | `cd frontend && npm test` |
| Frontend lint (oxlint) | `cd frontend && npm run lint` |
| Frontend type-check and build | `cd frontend && npm run build` |
| Run the backend | `mvn spring-boot:run` (needs the variables in `.env.example`) |

Quote `-D` arguments as above. The local scripts here are PowerShell (see `infra/mysql-test/`), and PowerShell can mis-parse unquoted `-D…` arguments.

`mvn test` skips two kinds of suites on purpose. Never turn them on without asking:

- **Live OpenAI tests** (`*Live*Test`) run only when their `AIJOB_*` flag is `true` and a real `OPENAI_API_KEY` is set. They cost money, and their output varies from run to run.
- **MySQL integration tests** run only when `AIJOB_MYSQL_TEST_URL` is set, and several of them truncate tables in setup. Point them only at the throwaway database from `infra/mysql-test/`.

Never run scripts in `infra/aws/` or `infra/pilot-db/` unless asked. They deploy to production or write to the database that holds real collected postings.

## Architecture

- `controller/`: REST API under `/api/v1`. Handlers return `ResponseEntity<…>`. For user-owned data, scope every lookup to the caller with `authentication.getName()`.
- `service/` (interfaces) and `service/impl/`: business logic, with constructor injection.
- `mapper/`: MyBatis with annotations (`@Select`, `@Insert` and so on). There are no XML mappers.
- `exception/`: one exception class per failure. `GlobalExceptionHandler` turns them into `ErrorResponse`.
- `tailor/v3/`: the current tailoring engine, with BASIC and ENHANCED modes. The older V2 path (`util/Conservative*` and related classes) is still the default; `AIJOB_TAILOR_ENGINE=V3` switches engines.
- `collector/`: background collector for public Greenhouse and Lever boards, with a host allowlist and per-host rate limiting.
- OpenAI is called only from `config/OpenAiConfig` and the `OpenAi*` classes in `service/impl/` and `tailor/v3/`, using Structured Outputs. Everything else is deterministic and testable offline. Keep it that way.
- `frontend/src/`: React 19, TypeScript, Vite and Tailwind. HTTP goes through `api/client.ts`, which gets its base URL from `api/url.ts`. All UI text goes through i18next (`locales/en.json`, `locales/zh-CN.json`).
- A database change touches three places: a migration in `schema/<feature>.sql`, the fresh-install script `infra/aws/mysql-init/01-schema.sql`, and the H2 copy in `src/test/resources/schema/*-h2.sql` when tests use the table.

## How tailoring stays truthful

Every V3 rewrite goes through `V3EntryRewriteValidator.validate` before it can be used. BASIC, ENHANCED and `V3HybridSelector` all call it. The checks run in this order, and the first failure returns a reason code from `V3ValidationDecision`:

1. Identity: heading, dates and employment status are unchanged.
2. Facts the user said not to use.
3. Facts borrowed from another entry.
4. Invented skills.
5. Invented credentials.
6. Numbers that aren't in the evidence.
7. Material facts that were dropped.
8. Material facts that were duplicated.
9. Action and object provenance (`V3ActionObjectProvenanceGate`).
10. Role escalation, such as "led" without evidence.

To add a check, run `/add-rule`.

## Definition of done

A change is done only when all of these hold. `/verify` checks them.

1. `mvn test` passes. If `frontend/` changed, `npm run lint`, `npm test` and `npm run build` pass too.
2. **No unsupported claims.** Nothing weakens the validator or lets tailored text gain a skill, credential, number, employer, date or leadership role that the candidate's own evidence doesn't support. Inferred skills go to the user for confirmation and are never written silently.
3. **DOCX formatting survives.** The V3 writer is equal-count: it replaces the text of existing bullet paragraphs, and it never adds, deletes or reorders paragraphs or writes headings and dates (`V3EqualCountChangeAdapter`). `ResumeDocxTailorWriterRegressionTest` and `V3PdfDocxVerticalTest` stay green.
4. **Frozen paths stay frozen.** `TailorV2PathFreezeTest` and `V3EnhancedBasicFreezeTest` pin the V2 path, the BASIC and ENHANCED prompt versions, and the validator wiring. Don't edit them to get a change through. Stop and ask.
5. **Fixtures stay synthetic.** No real names, emails, phone numbers or resumes go in `src/test/resources`. `TestResourceDocumentPrivacyScanTest` enforces this for PDF and DOCX files.
6. **No secrets in git.** Settings come from environment variables listed in `.env.example`. Tests use the fake values in `src/test/resources/application.yml`.
7. **Both languages.** New UI text goes into both `en.json` and `zh-CN.json`. Only i18next plural forms such as `_one` may differ.
8. **Tests didn't get weaker.** No assertions were deleted or loosened, no tests were disabled, and no expected values were rewritten just to match new output.

## Lessons from this repo's history

Each of these cost something once. The commit or file has the details.

- **The test suite wiped real data.** The MySQL import test truncates its schema by design, and it erased 1,379 real postings that shared that schema. Real data now lives in `aijob_catalog_pilot`, and tests only touch `aijob_import_test`. (`f20b1a4`)
- **A real resume was committed as a test fixture.** It was replaced with synthetic fixtures and a permanent privacy scan. (`b88c2b0`)
- **"Compiles for Java 21" is not "passes on Java 21".** Report only what you actually ran. `infra/mysql-test/run-tests-java21.ps1` runs the suite on a Java 21 runtime. (`89f6df0`)
- **Scripts were committed without the execute bit**, so release and rollback stopped with "Permission denied". Commit new `*.sh` files with `git update-index --chmod=+x`; `.gitattributes` keeps them LF. (`1b0dc2d`)
- **401 is not 403.** Missing, malformed, expired or revoked credentials get 401 with a JSON body. 403 is only for a signed-in user who isn't allowed. Public routes still serve a visitor who sends a stale token. (`34f50b6`)
- **Phone uploads arrive percent-encoded** (`%E7%AE%80%E5%8E%86.pdf`). File names go through `ResumeFileNames`, which decodes before it sanitizes. (`34f50b6`)
- **The test `application.yml` shadows the main one.** When you change a shared setting such as MyBatis in `src/main/resources/application.yml`, mirror it in `src/test/resources/application.yml`, or tests and production map columns differently.
- **CORS is an explicit list** (`aijob.cors.allowed-origins`), never `*`. (`16c9230`)

When a review finds a new mistake that is likely to happen again, add it here.

## Conventions

- Java 21, Spring Boot 3.5. Follow the package you're in: DTOs and entities use Lombok, and `tailor/v3` uses records.
- Every bug fix comes with a test that fails without it. Test names describe behavior, for example `rejectsMissingAndDuplicateMaterialFacts`.
- Prompts are versioned in `V3GenerationTelemetry`. Changing a prompt is a product decision: ask first, then bump the version.
- Commits follow Conventional Commits (`feat:`, `fix:`, `docs:`, `refactor:`, `chore:`), with the reason in the body.
