#!/usr/bin/env python3
"""Data-quality report for exported job-posting batches.

The pipeline writes one directory per board, holding ``batch.jsonl`` and
``batch.manifest.json`` (see ``infra/pipeline/run-pipeline.sh``). The Java importer
(``JobExportImporter``) is the gate: it refuses a bad batch before writing anything.
This script is the report. It reads the same files without touching the database and
answers two questions:

* Errors: would the importer refuse this batch? The checks mirror the importer's rules.
* Warnings and completeness: is the data good enough to be useful? These are checks the
  importer does not make, such as unparseable dates, non-HTTP links, repeated apply URLs
  and how often optional fields are empty.

Usage:
    python3 tools/jsonl_quality/check_batches.py RUN_DIR [RUN_DIR ...]
    python3 tools/jsonl_quality/check_batches.py RUN_DIR --json

A RUN_DIR may be one board directory or a directory holding several. Exit status is 0
when no batch has errors, 1 when at least one does (or when --strict is set and there are
warnings), and 2 for bad arguments.

Standard library only, so it runs on the pipeline host without installing anything.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

MANIFEST_NAME = "batch.manifest.json"
JSONL_NAME = "batch.jsonl"

# Kept in step with JobExportImporter and JobExportManifest.
OK_STATUSES = {"ok", "ok_empty"}
MAX_RECORDS = 5000
REQUIRED_MANIFEST_FIELDS = ("run_id", "provider", "board")
REQUIRED_RECORD_FIELDS = ("external_id", "title", "company", "apply_url")

# Fields the importer accepts when empty, so emptiness is a quality signal, not an error.
OPTIONAL_FIELDS = ("location", "description", "posted_at", "work_mode",
                   "employment_type", "countries")
DATE_FIELDS = ("posted_at", "fetched_at")

# Marketing parameters that never identify a posting. A subset of CatalogFingerprint's
# list, enough to spot two postings that point at the same page.
TRACKING_PARAMS = {
    "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content", "utm_id",
    "gh_src", "gclid", "fbclid", "msclkid", "_ga", "_gl",
}

# A fetch time this far in the future means a wrong clock, not a time zone quirk.
CLOCK_SKEW = timedelta(hours=1)


@dataclass
class BatchReport:
    path: str
    provider: str | None = None
    board: str | None = None
    run_id: str | None = None
    status: str | None = None
    declared_count: int | None = None
    record_count: int = 0
    truncated: bool = False
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    completeness: dict[str, float] = field(default_factory=dict)

    @property
    def importable(self) -> bool:
        return not self.errors


def is_blank(value) -> bool:
    if value is None:
        return True
    if isinstance(value, str):
        return not value.strip()
    if isinstance(value, (list, dict)):
        return len(value) == 0
    return False


def parse_timestamp(value: str) -> datetime | None:
    """Parse an ISO-8601 timestamp, accepting a trailing Z. Returns None if invalid."""
    text = value.strip()
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


def normalize_url(value: str) -> str | None:
    """Lower-case scheme and host, drop the fragment, tracking parameters and a trailing slash."""
    parts = urlsplit(value.strip())
    if parts.scheme.lower() not in ("http", "https") or not parts.netloc:
        return None
    query = urlencode(sorted((k, v) for k, v in parse_qsl(parts.query, keep_blank_values=True)
                             if k.lower() not in TRACKING_PARAMS))
    path = parts.path.rstrip("/") or "/"
    return urlunsplit((parts.scheme.lower(), parts.netloc.lower(), path, query, ""))


def check_manifest(manifest_path: Path, report: BatchReport) -> dict | None:
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except UnicodeDecodeError:
        report.errors.append("manifest is not valid UTF-8")
        return None
    except json.JSONDecodeError as exc:
        report.errors.append(f"manifest is not valid JSON: {exc.msg} (line {exc.lineno})")
        return None
    if not isinstance(manifest, dict):
        report.errors.append("manifest is not a JSON object")
        return None

    report.provider = manifest.get("provider")
    report.board = manifest.get("board")
    report.run_id = manifest.get("run_id")
    report.status = manifest.get("status")
    report.truncated = bool(manifest.get("truncated"))

    if report.status not in OK_STATUSES:
        detail = f" ({manifest['error']})" if manifest.get("error") else ""
        report.errors.append(f"manifest status is {report.status!r}{detail}; the exporter did not finish")
    for name in REQUIRED_MANIFEST_FIELDS:
        if is_blank(manifest.get(name)):
            report.errors.append(f"manifest {name} is missing")

    count = manifest.get("exported_count")
    if not isinstance(count, int) or isinstance(count, bool) or count < 0:
        report.errors.append("manifest has no usable exported_count")
    elif count > MAX_RECORDS:
        report.errors.append(f"manifest declares {count} records, above the {MAX_RECORDS} limit")
    else:
        report.declared_count = count

    if report.truncated or manifest.get("complete_snapshot") is not True:
        report.warnings.append("batch is a sample, not the whole board (truncated or not a complete snapshot)")
    return manifest


def read_records(jsonl_path: Path, report: BatchReport) -> list[tuple[int, dict]]:
    try:
        text = jsonl_path.read_bytes().decode("utf-8")
    except UnicodeDecodeError:
        report.errors.append("export is not valid UTF-8")
        return []
    records = []
    for number, line in enumerate(text.splitlines(), start=1):
        if not line.strip():
            continue  # the importer skips blank lines too
        try:
            record = json.loads(line)
        except json.JSONDecodeError as exc:
            report.errors.append(f"line {number} is not valid JSON: {exc.msg}")
            continue
        if not isinstance(record, dict):
            report.errors.append(f"line {number} is not a JSON object")
            continue
        records.append((number, record))
    return records


def check_records(records: list[tuple[int, dict]], manifest: dict, report: BatchReport,
                  now: datetime) -> None:
    seen_identities: dict[str, int] = {}
    seen_urls: dict[str, int] = {}
    filled = {name: 0 for name in OPTIONAL_FIELDS}

    for number, record in records:
        if manifest.get("run_id") and record.get("run_id") != manifest.get("run_id"):
            report.errors.append(f"line {number} has run_id {record.get('run_id')!r}, "
                                 f"not the manifest's {manifest.get('run_id')!r}")
        if (record.get("provider"), record.get("board")) != (manifest.get("provider"), manifest.get("board")):
            report.errors.append(f"line {number} is {record.get('provider')}/{record.get('board')}, "
                                 f"not {manifest.get('provider')}/{manifest.get('board')}")

        for name in REQUIRED_RECORD_FIELDS:
            if is_blank(record.get(name)):
                report.errors.append(f"line {number} is missing {name}")
        external_id = record.get("external_id")
        if isinstance(external_id, str) and external_id.strip() and external_id != external_id.strip():
            report.errors.append(f"line {number} external_id has surrounding whitespace")

        identity = f"{record.get('provider')}/{record.get('board')}/{external_id}"
        if not is_blank(external_id):
            if identity in seen_identities:
                report.errors.append(f"line {number} repeats {identity} from line {seen_identities[identity]}")
            else:
                seen_identities[identity] = number

        apply_url = record.get("apply_url")
        if isinstance(apply_url, str) and apply_url.strip():
            normalized = normalize_url(apply_url)
            if normalized is None:
                report.warnings.append(f"line {number} apply_url is not an http(s) link")
            elif normalized in seen_urls:
                report.warnings.append(f"line {number} apply_url points at the same page as line "
                                       f"{seen_urls[normalized]}")
            else:
                seen_urls[normalized] = number

        for name in DATE_FIELDS:
            value = record.get(name)
            if is_blank(value):
                continue
            parsed = parse_timestamp(value) if isinstance(value, str) else None
            if parsed is None:
                report.warnings.append(f"line {number} {name} is not an ISO-8601 timestamp: {value!r}")
            elif name == "fetched_at" and parsed > now + CLOCK_SKEW:
                report.warnings.append(f"line {number} fetched_at is in the future: {value}")

        for name in OPTIONAL_FIELDS:
            if not is_blank(record.get(name)):
                filled[name] += 1

    report.record_count = len(records)
    if report.declared_count is not None and report.record_count != report.declared_count:
        report.errors.append(f"file holds {report.record_count} records but the manifest declares "
                             f"{report.declared_count}")
    if records:
        report.completeness = {name: round(100 * count / len(records), 1) for name, count in filled.items()}


def check_batch(batch_dir: Path, now: datetime | None = None) -> BatchReport:
    now = now or datetime.now(timezone.utc)
    report = BatchReport(path=str(batch_dir))
    manifest = check_manifest(batch_dir / MANIFEST_NAME, report)
    if manifest is None:
        return report

    jsonl_path = batch_dir / JSONL_NAME
    if not jsonl_path.is_file():
        if report.status == "ok" and report.declared_count:
            report.errors.append(f"no {JSONL_NAME} beside a manifest that declares {report.declared_count} records")
        return report
    check_records(read_records(jsonl_path, report), manifest, report, now)
    return report


def find_batches(paths: list[Path]) -> list[Path]:
    batches = []
    for path in paths:
        if (path / MANIFEST_NAME).is_file():
            batches.append(path)
        else:
            batches.extend(sorted(p.parent for p in path.rglob(MANIFEST_NAME)))
    return batches


def print_text(reports: list[BatchReport]) -> None:
    for r in reports:
        verdict = "OK" if r.importable else "WOULD BE REFUSED"
        print(f"\n{r.provider}/{r.board}  [{verdict}]  {r.path}")
        print(f"  records: {r.record_count} (manifest says {r.declared_count}), status: {r.status}")
        if r.completeness:
            print("  completeness: " + ", ".join(f"{k} {v}%" for k, v in r.completeness.items()))
        for message in r.errors:
            print(f"  ERROR    {message}")
        for message in r.warnings:
            print(f"  WARNING  {message}")
    bad = sum(not r.importable for r in reports)
    records = sum(r.record_count for r in reports)
    warnings = sum(len(r.warnings) for r in reports)
    print(f"\n{len(reports)} batches, {records} records, {bad} with errors, {warnings} warnings")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Report data quality of exported job batches.")
    parser.add_argument("paths", nargs="+", type=Path, help="board or run directories")
    parser.add_argument("--json", action="store_true", help="print the report as JSON")
    parser.add_argument("--strict", action="store_true", help="exit 1 on warnings as well as errors")
    args = parser.parse_args(argv)

    missing = [str(p) for p in args.paths if not p.is_dir()]
    if missing:
        parser.error("not a directory: " + ", ".join(missing))
    batch_dirs = find_batches(args.paths)
    if not batch_dirs:
        parser.error(f"no {MANIFEST_NAME} found under the given paths")

    reports = [check_batch(d) for d in batch_dirs]
    if args.json:
        print(json.dumps([r.__dict__ | {"importable": r.importable} for r in reports], indent=2))
    else:
        print_text(reports)

    failed = any(r.errors for r in reports) or (args.strict and any(r.warnings for r in reports))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
