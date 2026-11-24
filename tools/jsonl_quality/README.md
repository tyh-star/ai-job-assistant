# JSONL batch quality report

`check_batches.py` reads the batches the job pipeline exports (`batch.jsonl` and `batch.manifest.json` in each board directory) and reports their data quality, without touching the database.

`JobExportImporter` is still the gate: it refuses a bad batch before writing anything. This script is the report you read before and after a run:

- **Errors** mirror the importer's rules, so you can see which batches it would refuse and why: a failed export, a record count that disagrees with the manifest, invalid JSON, missing required fields (`external_id`, `title`, `company`, `apply_url`), records from another run or board, and repeated postings.
- **Warnings** are checks the importer doesn't make: dates that aren't ISO-8601, fetch times in the future, links that aren't http(s), two postings pointing at the same page once tracking parameters are removed, and batches that are only a sample of the board.
- **Completeness** shows how often each optional field (location, description, posted date, work mode, employment type, countries) is filled.

## Usage

```bash
# one board, or a whole run directory with several boards
python3 tools/jsonl_quality/check_batches.py path/to/run-dir

# machine-readable output
python3 tools/jsonl_quality/check_batches.py path/to/run-dir --json

# also fail on warnings, for use in a scheduled check
python3 tools/jsonl_quality/check_batches.py path/to/run-dir --strict
```

The exit status is 0 when no batch has errors, 1 when any does (or when `--strict` is set and there are warnings), and 2 for bad arguments. It uses only the Python standard library.

## Tests

```bash
python3 -m unittest discover -s tools/jsonl_quality
```

The tests build synthetic batches in a temporary directory. When the importer's rules change, update the matching checks and tests here.
