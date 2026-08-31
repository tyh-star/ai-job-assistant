# Configuration and secrets

This project does not store database passwords or JWT secrets in source. Set them in the environment before starting `AiJobApplication`.

## Variable names

| Variable | Bound to |
|---|---|
| `AIJOB_DB_URL` | `spring.datasource.url` |
| `AIJOB_DB_USERNAME` | `spring.datasource.username` |
| `AIJOB_DB_PASSWORD` | `spring.datasource.password` |
| `AIJOB_JWT_SECRET` | `jwt.secret` (must be at least 32 bytes) |
| `OPENAI_API_KEY` | `openai.api-key` |
| `OPENAI_MODEL` | `openai.model` (optional; default `gpt-4o`) |
| `AIJOB_STORAGE_ROOT` | `aijob.storage.root` (optional; default `uploads`) |
| `ADZUNA_APP_ID` | Adzuna app id (environment only; never sent to the frontend or logged) |
| `ADZUNA_APP_KEY` | Adzuna app key (environment only; never sent to the frontend or logged) |
| `ADZUNA_COUNTRY_CODE` | Adzuna country path segment (environment only; current product default `ca` if unset) |
| `JSEARCH_API_KEY` | JSearch API key (environment only; never sent to the frontend or logged) |
| `JSEARCH_API_BASE_URL` | JSearch base URL (environment only; default `https://api.openwebninja.com`) |
| `JOB_SEARCH_PRIMARY_PROVIDER` | Job search provider name (environment only; default `JSEARCH`) |
| `JSEARCH_MAX_PAGES_PER_SEARCH` | Max JSearch pages per search session (not a secret; default `10`, about 100 raw results) |
| `AIJOB_TAILOR_ENGINE` | Tailor engine (`V2` or `V3`; not a secret; default `V2`). V3-1 does not change the live V2 path. |

Copy `.env.example` only as a checklist of names. Do not put real values in Git, README, screenshots, or command history you will share.

Optional: `AIJOB_JWT_EXPIRATION` (milliseconds; default `86400000`).

## IntelliJ — `AiJobApplication`

1. Run → Edit Configurations → `AiJobApplication` (or add a Spring Boot configuration for `aijob.AiJobApplication`).
2. Environment variables / Env file:
   - Prefer filling **Environment variables** in the run configuration.
   - Names must match the table above.
   - Values stay in IntelliJ locally; do not paste them into chat, Git, or docs.
3. Working directory should be the repo root so the default storage folder `uploads` resolves as intended.
4. Apply → Run.

IntelliJ run configurations are not used by Maven. `spring-boot:run` will **not** inherit those Environment variables.

## PowerShell (temporary, current session only)

```powershell
$env:AIJOB_DB_URL = "jdbc:mysql://localhost:3306/AIjob?useSSL=false&serverTimezone=UTC"
$env:AIJOB_DB_USERNAME = "<your-local-username>"
$env:AIJOB_DB_PASSWORD = "<your-local-password>"
$env:AIJOB_JWT_SECRET = "<your-local-jwt-secret-32-plus-bytes>"
$env:OPENAI_API_KEY = "<your-openai-key>"
$env:OPENAI_MODEL = "gpt-4o"
$env:AIJOB_STORAGE_ROOT = "uploads"
```

Then start the app from **the same** terminal:

```powershell
& "$env:USERPROFILE\.local\apache-maven-3.9.9\bin\mvn.cmd" -DskipTests spring-boot:run
```

Close the terminal when finished so the variables do not linger for unrelated commands.

Do not print these values with `echo`/`Write-Output`. Do not put them in `git` commits.

## Maven `spring-boot:run` vs IntelliJ

| Start method | Sees IntelliJ Run Configuration env? |
|---|---|
| Green arrow on `AiJobApplication` | Yes |
| Maven `spring-boot:run` from IntelliJ Maven tool window | No, unless that Maven runner also has env vars |
| PowerShell `mvn spring-boot:run` | Only variables set in that PowerShell session |

If the process starts then immediately fails with placeholder errors for `AIJOB_DB_PASSWORD` or `AIJOB_JWT_SECRET`, the environment was not applied to that process.

## Tests

`src/test/resources/application.yml` and `@SpringBootTest` properties use **independent test-only** H2 credentials and a test JWT string. They must not use your local MySQL password. Test storage is `target/aijob-test-uploads`, not `uploads/`.
