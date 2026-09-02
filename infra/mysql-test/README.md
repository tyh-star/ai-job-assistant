# Isolated MySQL 8.4 for the import integration test

`JobExportImportMySqlIntegrationTest` answers questions H2 cannot: whether the production
DDL executes, whether `utf8mb4_unicode_ci` folds two external ids that differ only in
case, whether emoji and astral-plane characters survive, and whether `SAMPLE_IMPORT` fits
its column. Those answers are only worth having from a real MySQL **8.4**.

This directory brings one up that touches nothing else on the machine.

## What it is isolated from

| | This test container | The machine's existing MySQL | The production stack |
|---|---|---|---|
| Port | `127.0.0.1:3307` | `3306` | not on this machine |
| Compose project | `aijob-mysqltest` | not Compose | `aijob` |
| Volume | `aijob-mysqltest_mysqltest_data` (created empty) | its own data dir | `aijob_mysql_data` |
| Database | `aijob_import_test` | untouched | untouched |
| Account | `aijob_test`, granted `aijob_import_test.*` only | untouched | untouched |

No host directory and no existing volume is mounted, so this container has no path to any
other database's files. Publishing on `127.0.0.1` rather than `0.0.0.0` keeps a throwaway
database with a guessable name off the network.

## Prerequisites

Docker is **not installed** on this machine. See the checklist at the bottom.

## Steps

**1. Generate the credentials.** Two independent passwords, written to a git-ignored file
readable only by you. Neither is printed, and neither is ever passed as a command-line
argument.

```powershell
.\infra\mysql-test\new-credentials.ps1
```

**2. Confirm the setup without revealing it.** Reports presence, file ownership and
whether git ignores the file — never a value.

```powershell
.\infra\mysql-test\check-env.ps1
```

**3. Start the container.**

```powershell
docker compose -p aijob-mysqltest --env-file infra/mysql-test/.env.mysql-test -f infra/mysql-test/docker-compose.mysql-test.yml up -d
```

**4. Run the test.** The credentials go into the Maven child process's environment, not
onto a command line.

```powershell
.\infra\mysql-test\run-mysql-tests.ps1
```

**5. Stop it when done.** `down` without `-v` keeps the volume; add `-v` only when you
actually want the scratch data gone.

```powershell
docker compose -p aijob-mysqltest -f infra/mysql-test/docker-compose.mysql-test.yml down
```

## What the test checks before it writes anything

A database whose name happens to contain "test" is not evidence. Before the first DDL
statement, and therefore before the truncate that would destroy data, it verifies against
the live connection:

1. `SELECT VERSION()` starts with `8.4` — results from another version would not mean what
   they claim.
2. `SELECT DATABASE()` is exactly `aijob_import_test` — an exact match, not a substring.
3. `SHOW GRANTS FOR CURRENT_USER()` includes `` `aijob_import_test`.* `` and holds no
   server-wide privilege beyond `USAGE` — an account that can reach other schemas must not
   be handed to a test that truncates.
4. The schema contains no table this project does not own.

Any of those failing aborts the run.

## Credential handling

* Generated locally by `new-credentials.ps1` using a cryptographic RNG, never by hand and
  never supplied over chat.
* Written only to `infra/mysql-test/.env.mysql-test`, which is in `.gitignore` and whose
  ACL is reset to the current user alone (inheritance switched off — the Windows
  equivalent of `chmod 600`).
* Never printed, never a command-line argument, never in shell history.
* `run-mysql-tests.ps1` removes both passwords from the shell environment when the run
  finishes.
* Root and application passwords are generated separately, so the scratch account's
  password is not also the server's.

These credentials protect a container holding synthetic postings on loopback. They grant
nothing anywhere else, and the existing MySQL on 3306 is never contacted.

## Installing Docker (do this yourself; nothing here installs software)

This machine already satisfies the hardware prerequisites — checked read-only:

* CPU virtualization enabled in firmware: **yes** (`VirtualizationFirmwareEnabled=True`)
* SLAT: **yes**
* Windows 11 Home, build 26200 — Docker Desktop runs on Home via the **WSL 2 backend**
  (the Hyper-V backend is not available on Home; WSL 2 is what you want anyway)
* Port 3307: **free**

Official steps:

1. Install WSL 2 — <https://learn.microsoft.com/windows/wsl/install>
   ```powershell
   wsl --install
   ```
   Run it in an **elevated** PowerShell, then reboot. (`wsl --status` currently reports no
   distribution installed, so this step is required.)
2. Install Docker Desktop for Windows —
   <https://docs.docker.com/desktop/setup/install/windows-install/>
   Download the installer from that page and keep **"Use WSL 2 instead of Hyper-V"**
   checked.
3. Start Docker Desktop once, let it finish first-run setup, then confirm:
   ```powershell
   docker version
   docker compose version
   ```

Nothing in this repository installs, downloads or elevates anything.
