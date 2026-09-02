# Scheduled runs on Ubuntu

Four unit files: a pipeline service and its 6-hourly timer, a retention service and its
daily timer.

**Nothing here is installed or enabled by the repository.** The files were written and
statically validated; no timer has ever fired. See *What is and is not verified* below —
the distinction matters, and a green `systemd-analyze verify` is not a run.

## Install

```bash
# 1. Settings and credentials. pipeline.env is not secret; pipeline-db.env is.
sudo install -d -o root -g aijob -m 0750 /etc/aijob
sudo install -o root -g aijob -m 0644 pipeline.env.example    /etc/aijob/pipeline.env
sudo install -o root -g aijob -m 0640 pipeline-db.env.example /etc/aijob/pipeline-db.env
sudo -e /etc/aijob/pipeline-db.env      # fill in the connection and password

# 2. The shared state volume. The SAME volume for every container that can run this:
#    the lock and the back-off state are both per-filesystem.
docker volume create aijob-pipeline-state

# 3. The units.
sudo install -m 0644 aijob-*.service aijob-*.timer /etc/systemd/system/
sudo systemctl daemon-reload
```

## Try it once before putting it on a timer

```bash
sudo systemctl start aijob-pipeline.service   # one run, now
journalctl -u aijob-pipeline.service -n 100 --no-pager
```

Only then:

```bash
sudo systemctl enable --now aijob-pipeline.timer
sudo systemctl enable --now aijob-retention.timer
```

## Pause and resume

```bash
sudo systemctl stop aijob-pipeline.timer          # stop scheduling; a running job finishes
sudo systemctl disable --now aijob-pipeline.timer # and do not come back after a reboot
sudo systemctl enable --now aijob-pipeline.timer  # resume
```

To stop collecting **one board** without stopping the timer, set `"enabled": false` on
its entry in `config/sources-allowlist.json` and rebuild the image. The entry and its
reasoning stay in the file.

## Look at what happened

```bash
systemctl list-timers aijob-\*                    # when it last ran and when it runs next
journalctl -u aijob-pipeline.service -n 200       # the last run's output
journalctl -u aijob-pipeline.service --since '2 days ago' | grep -E '^(===|  )'

# per-board state: failures, next attempt, pauses
docker run --rm -v aijob-pipeline-state:/var/lib/aijob \
  --entrypoint /opt/aijob/pipeline-status.sh aijob-pipeline:local

# the last few run summaries (counts and statuses, no posting content)
docker run --rm -v aijob-pipeline-state:/var/lib/aijob \
  --entrypoint /opt/aijob/pipeline-status.sh aijob-pipeline:local --runs 5
```

Local files and `journalctl` only. Nothing mails, posts to a webhook, or alerts anywhere:
if you want to be paged, wire that up deliberately.

## Exit codes, and why the unit treats 3 as success

| code | meaning |
|---|---|
| 0 | every board handled |
| 1 | at least one board failed — a real failure, and the unit fails |
| 2 | bad arguments |
| **3** | **another run held the lock; this one did nothing** |

`SuccessExitStatus=3` is in the service file. A held lock is the mechanism working: the
previous run overran, or an operator started one by hand. Letting that mark the unit
failed would put a red line in `systemctl status` for correct behaviour, and a status
output with routine red in it is one nobody reads. Every other non-zero code stays a
failure with its reason in the journal.

## Back-off

Per provider/board, in the state volume, so it survives restarts. Live and fixture are
counted separately — a fixture run says nothing about whether a real board is healthy.

| consecutive failures | next attempt |
|---|---|
| 1 | 6 hours |
| 2 | 12 hours |
| 3+ | 24 hours |

A success resets the count. A skipped board (backed off, or leased elsewhere) is not a
failure and does not advance it.

**A refusal pauses the board instead of backing it off.** `ROBOTS_DISALLOWED`,
`BLOCKED_BOT_CHALLENGE`, `REFUSED_403`, `HTTP_401`, `HTTP_407`: these are a server saying
no, and a timer that keeps asking is both useless and rude. The board stops until a person
clears it:

```bash
docker run --rm -v aijob-pipeline-state:/var/lib/aijob \
  --entrypoint /opt/aijob/pipeline-status.sh aijob-pipeline:local \
  --resume greenhouse/vercel --mode live
```

### The 429 limitation, stated plainly

A `429` gets at least 6 hours, never less, whatever its position on the ladder.

What that is **not**: a guarantee of honouring the server's `Retry-After`. The exporter
honours it *within* a run — its HTTP client waits and retries, capping its own wait at 30
seconds — but the header value is consumed there and never reaches the manifest, so the
pipeline cannot read it. The 6-hour floor is far longer than any wait the exporter itself
would perform, so in practice it exceeds the request. **If a server asked for more than 6
hours, we would not know.** Closing that gap means having the exporter record the header
it saw; that is a change to the exporter and has not been made.

## Retention

Three stages: whole, then summary-only, then gone.

| | default | what happens |
|---|---|---|
| successful batches | 14 days | JSONL and logs deleted, summary and manifests kept |
| failed batches | 30 days | same, but later — failures are diagnosed after the fact |
| everything | 90 days | the run directory is removed |

`retention.sh` is a **dry run unless `--apply`**. It only looks inside the managed batch
root, re-resolves each directory to confirm it is still under that root, refuses to follow
a symlink, and skips any run without a `pipeline-run.json` — that is either a run in
progress or one that died, and neither is safe to delete. It never touches the lock
directory, the state directory, credentials, or a database volume.

The retention *timer* passes `--apply`. A scheduled prune that only printed what it would
do would let the disk fill while looking healthy.

## What is and is not verified

**Verified:** the unit files parse (`systemd-analyze verify`), the pipeline itself runs on
Linux end to end, the back-off ladder, the pause behaviour, restart persistence, lock
contention, and the retention boundaries — all with controllable time, so no test waited
six hours for anything.

**Not verified:** a timer actually waking the service. No timer has been installed or
enabled, on this machine or anywhere else. `OnUnitActiveSec=6h`, `Persistent=true` and the
`RandomizedDelaySec` jitter are unexercised configuration. The first real tick will be on
the host you install this on, and it should be watched.

**Not verified:** anything on AWS. Nothing here has been near the production database or
an EC2 instance.
