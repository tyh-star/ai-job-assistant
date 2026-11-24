"""Tests for check_batches.py. Run: python3 -m unittest discover tools/jsonl_quality

All data here is synthetic: example.com boards, made-up companies and titles.
"""

import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from datetime import datetime, timezone
from pathlib import Path

import check_batches as cb

NOW = datetime(2026, 11, 24, 12, 0, tzinfo=timezone.utc)


def record(n, **overrides):
    base = {
        "run_id": "run-1", "provider": "greenhouse", "board": "acme",
        "external_id": str(1000 + n), "title": f"Engineer {n}", "company": "Acme Example",
        "location": "Toronto, ON", "apply_url": f"https://boards.example.com/acme/jobs/{1000 + n}",
        "description": "Build things.", "remote": False,
        "posted_at": "2026-11-20T10:00:00Z", "work_mode": "hybrid",
        "employment_type": "full_time", "countries": ["CA"],
        "fetched_at": "2026-11-24T11:00:00Z",
    }
    base.update(overrides)
    return base


def manifest(count, **overrides):
    base = {"run_id": "run-1", "mode": "fixture", "provider": "greenhouse", "board": "acme",
            "company": "Acme Example", "fetched_count": count, "exported_count": count,
            "truncated": False, "complete_snapshot": True, "status": "ok"}
    base.update(overrides)
    return base


class BatchTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def write_batch(self, name, records, manifest_data=None, raw_lines=None):
        batch = self.root / name
        batch.mkdir()
        lines = raw_lines if raw_lines is not None else [json.dumps(r) for r in records]
        (batch / cb.JSONL_NAME).write_text("\n".join(lines) + "\n", encoding="utf-8")
        data = manifest_data if manifest_data is not None else manifest(len(records))
        (batch / cb.MANIFEST_NAME).write_text(json.dumps(data), encoding="utf-8")
        return batch

    def check(self, batch):
        return cb.check_batch(batch, now=NOW)


class ImporterRuleTests(BatchTestCase):
    """Each of these would make JobExportImporter refuse the batch."""

    def test_clean_batch_has_no_errors_or_warnings(self):
        report = self.check(self.write_batch("ok", [record(1), record(2)]))
        self.assertEqual(report.errors, [])
        self.assertEqual(report.warnings, [])
        self.assertEqual(report.record_count, 2)
        self.assertEqual(report.completeness["location"], 100.0)

    def test_count_mismatch_is_an_error(self):
        report = self.check(self.write_batch("short", [record(1)], manifest(2)))
        self.assertIn("file holds 1 records but the manifest declares 2", report.errors)

    def test_missing_required_field_is_an_error(self):
        report = self.check(self.write_batch("missing", [record(1, title="  ")]))
        self.assertIn("line 1 is missing title", report.errors)

    def test_duplicate_identity_is_an_error(self):
        report = self.check(self.write_batch("dup", [record(1), record(1, title="Copy")]))
        self.assertIn("line 2 repeats greenhouse/acme/1001 from line 1", report.errors)

    def test_record_from_another_run_is_an_error(self):
        report = self.check(self.write_batch("mixed", [record(1, run_id="run-0")]))
        self.assertTrue(any("run_id 'run-0'" in e for e in report.errors))

    def test_invalid_json_line_is_an_error(self):
        lines = [json.dumps(record(1)), "{not json"]
        report = self.check(self.write_batch("broken", [], manifest(2), raw_lines=lines))
        self.assertTrue(any(e.startswith("line 2 is not valid JSON") for e in report.errors))

    def test_failed_export_is_an_error(self):
        report = self.check(self.write_batch("failed", [], manifest(0, status="error", error="timeout")))
        self.assertTrue(any("status is 'error' (timeout)" in e for e in report.errors))

    def test_external_id_with_whitespace_is_an_error(self):
        report = self.check(self.write_batch("ws", [record(1, external_id=" 1001")]))
        self.assertIn("line 1 external_id has surrounding whitespace", report.errors)

    def test_blank_lines_are_skipped_like_the_importer(self):
        lines = [json.dumps(record(1)), "", "   ", json.dumps(record(2))]
        report = self.check(self.write_batch("blank", [], manifest(2), raw_lines=lines))
        self.assertEqual(report.errors, [])
        self.assertEqual(report.record_count, 2)


class QualityWarningTests(BatchTestCase):
    """Checks the importer does not make; they lower quality but don't block an import."""

    def test_bad_timestamp_is_a_warning_not_an_error(self):
        report = self.check(self.write_batch("date", [record(1, posted_at="last Tuesday")]))
        self.assertEqual(report.errors, [])
        self.assertTrue(any("posted_at is not an ISO-8601 timestamp" in w for w in report.warnings))

    def test_future_fetch_time_is_a_warning(self):
        report = self.check(self.write_batch("future", [record(1, fetched_at="2026-12-01T00:00:00Z")]))
        self.assertTrue(any("fetched_at is in the future" in w for w in report.warnings))

    def test_same_page_after_removing_tracking_params_is_a_warning(self):
        url = "https://boards.example.com/acme/jobs/1001"
        report = self.check(self.write_batch("url", [
            record(1, apply_url=url),
            record(2, apply_url=url + "/?utm_source=newsletter#apply"),
        ]))
        self.assertIn("line 2 apply_url points at the same page as line 1", report.warnings)

    def test_non_http_link_is_a_warning(self):
        report = self.check(self.write_batch("mailto", [record(1, apply_url="mailto:jobs@example.com")]))
        self.assertIn("line 1 apply_url is not an http(s) link", report.warnings)

    def test_sample_batch_is_flagged(self):
        report = self.check(self.write_batch("sample", [record(1)], manifest(1, truncated=True)))
        self.assertTrue(any("sample" in w for w in report.warnings))

    def test_completeness_counts_empty_optional_fields(self):
        report = self.check(self.write_batch("sparse", [
            record(1), record(2, description="", countries=[]),
            record(3, description=None), record(4)]))
        self.assertEqual(report.completeness["description"], 50.0)
        self.assertEqual(report.completeness["countries"], 75.0)


class CommandLineTests(BatchTestCase):
    def run_main(self, *args):
        out = io.StringIO()
        with redirect_stdout(out):
            code = cb.main([str(a) for a in args])
        return code, out.getvalue()

    def test_run_directory_with_one_bad_board_exits_1(self):
        run = self.root
        self.write_batch("greenhouse-acme", [record(1)])
        self.write_batch("greenhouse-beta", [record(1)], manifest(3))
        code, out = self.run_main(run)
        self.assertEqual(code, 1)
        self.assertIn("2 batches, 2 records, 1 with errors", out)

    def test_strict_fails_on_warnings(self):
        batch = self.write_batch("warn", [record(1, posted_at="soon")])
        self.assertEqual(self.run_main(batch)[0], 0)
        self.assertEqual(self.run_main(batch, "--strict")[0], 1)

    def test_json_output_is_parseable(self):
        batch = self.write_batch("json", [record(1)])
        code, out = self.run_main(batch, "--json")
        data = json.loads(out)
        self.assertEqual(code, 0)
        self.assertTrue(data[0]["importable"])


if __name__ == "__main__":
    unittest.main()
