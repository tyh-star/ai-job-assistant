---
name: verify
description: Check the current change against the definition of done in CLAUDE.md. Runs the offline backend and frontend test suites, then reviews the diff for unsupported claims, DOCX format changes, edits to frozen paths, weakened tests, real personal data in fixtures, and secrets. Use before committing, before opening a PR, or when asked to verify or review a change.
---

# Verify the current change

Review the change against **Definition of done** in `CLAUDE.md` and report what you find. Don't fix anything unless asked. This is the check, not the repair.

## 1. Find the change

- `git status` and `git diff HEAD` cover staged and unstaged work. If the tree is clean, review the last commit with `git show HEAD`. If the user named a branch or PR, diff it against `main` with `git diff main...HEAD`.
- Note which areas it touches: `tailor/v3/`, the V2 path in `util/`, DOCX and PDF services, `src/test/resources/`, `frontend/`, `schema/`, configuration.

## 2. Run the checks

- Always: `mvn test`
- If anything under `frontend/` changed: `cd frontend && npm run lint && npm test && npm run build`

Don't set the `AIJOB_*` live-test flags or `AIJOB_MYSQL_TEST_URL`. Those suites cost money or truncate a database. If the change needs them, say so in the report instead.

Write down each command and its result. If a command couldn't run (no JDK, no `node_modules`, no network), report it as **not run**, never as passed.

## 3. Review the diff

Answer each check with pass, fail or n/a, and cite `file:line` for anything that isn't a pass.

1. **Truthfulness.** Does anything weaken `V3EntryRewriteValidator`, such as a check that was removed, reordered or bypassed, or an allow-list that was widened? Could tailored text now gain a skill, credential, number, employer, date or leadership role that the evidence doesn't support? Do inferred skills still go to the user for confirmation?
2. **DOCX format.** Does any write path add, delete or reorder paragraphs, or write headings or dates? If `ResumeDocxTailorWriterRegressionTest` or `V3PdfDocxVerticalTest` changed, is the change justified, or was the test just loosened?
3. **Frozen paths.** Were `TailorV2PathFreezeTest`, `V3EnhancedBasicFreezeTest` or the prompt versions in `V3GenerationTelemetry` edited? That's a fail unless the user asked for it.
4. **Weaker tests.** Look for assertions that were deleted or loosened, tests that were disabled or skipped, and expected values rewritten to match new output without a reason.
5. **Privacy.** New files under `src/test/resources` are synthetic. There are no real names, emails or phone numbers anywhere in the diff, and no resume text is written to logs.
6. **Secrets and config.** There are no keys, passwords or tokens in code or config. New settings are read from the environment and listed in `.env.example` and `docs/configuration.md`. Shared settings are mirrored in the test `application.yml`.
7. **Schema.** A new or changed table has a `schema/` migration, a matching change in `infra/aws/mysql-init/01-schema.sql`, and an H2 copy if tests use it.
8. **UI text.** New keys exist in both `en.json` and `zh-CN.json`.
9. **Scripts.** New `*.sh` files are executable: `git ls-files -s` shows mode `100755`.

## 4. Report

1. The commands you ran and their results.
2. A table of the nine checks with pass, fail or n/a, and the evidence for each.
3. Problems in order of severity, each with `file:line` and a suggested fix.
4. One final line: **READY** or **NOT READY**.

If you found a mistake that the checks above didn't catch and that is likely to happen again, propose one line for "Lessons from this repo's history" in `CLAUDE.md`, so the next review catches it.
