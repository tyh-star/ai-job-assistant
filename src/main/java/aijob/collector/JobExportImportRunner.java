package aijob.collector;

import aijob.collector.jsonl.JobExportImportSummary;
import aijob.collector.jsonl.JobExportImporter;
import aijob.collector.jsonl.JobExportLockedException;
import aijob.collector.jsonl.JobExportRejectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Imports an exported JSONL batch from the command line.
 *
 * <p>Deliberately a CLI and not an HTTP route: the file is operator-supplied, and an
 * endpoint that ingests a path would be a way to point this deployment at an arbitrary
 * file. Nothing here is scheduled either — an import happens when someone runs it.
 *
 * <pre>
 * docker compose ... run --rm collector \
 *   --aijob.import.jsonl=/data/vercel-greenhouse.jsonl \
 *   --aijob.import.manifest=/data/vercel-greenhouse.manifest.json
 * </pre>
 *
 * <p>The manifest argument is required. Deriving it from the JSONL name would make it
 * skippable, and importing a JSONL without checking the batch record beside it is the
 * one thing this path must not allow: a failed export leaves a manifest saying so, and
 * possibly a stale JSONL from an earlier run, right next to each other.
 *
 * <p>Exit status: 0 on success, 2 when the batch was refused (nothing was written),
 * 3 when another process holds the board's lease and the import was skipped, 1 when the
 * import itself failed.
 */
@Component
@Profile("collector")
@Order(1)
public class JobExportImportRunner implements ApplicationRunner {

    static final String JSONL_ARG = "aijob.import.jsonl";
    static final String MANIFEST_ARG = "aijob.import.manifest";

    private static final Logger log = LoggerFactory.getLogger(JobExportImportRunner.class);

    private final JobExportImporter importer;
    private final CollectorProcessExit processExit;

    public JobExportImportRunner(JobExportImporter importer, CollectorProcessExit processExit) {
        this.importer = importer;
        this.processExit = processExit;
    }

    @Override
    public void run(ApplicationArguments args) {
        String jsonl = single(args, JSONL_ARG);
        if (jsonl == null) {
            // Not an import invocation: this is the collector starting normally.
            return;
        }
        // Exclusivity, stated rather than inferred from runner ordering: an import that
        // also asked for a crawl is an ambiguous instruction, and the one it would
        // silently get is "import only", because this runner ends the process first.
        if (args.containsOption(JobCollectorRunner.RUN_ONCE_ARG)
                || args.containsOption(JobCollectorRunner.SOURCE_ID_ARG)) {
            log.error("--{} cannot be combined with --{} or --{}: importing a file and"
                            + " crawling a board are separate runs",
                    JSONL_ARG, JobCollectorRunner.RUN_ONCE_ARG, JobCollectorRunner.SOURCE_ID_ARG);
            processExit.exit(2);
            return;
        }
        String manifest = single(args, MANIFEST_ARG);
        if (manifest == null) {
            log.error("--{} is required alongside --{}: a batch is only importable together"
                    + " with the manifest that describes it", MANIFEST_ARG, JSONL_ARG);
            processExit.exit(2);
            return;
        }

        int status;
        try {
            JobExportImportSummary summary =
                    importer.importBatch(Path.of(jsonl), Path.of(manifest));
            log.info("Imported batch {} for {}/{}: read={} inserted={} updated={} unchanged={} "
                            + "failed={} expired={} complete_snapshot={}",
                    summary.runId(), summary.provider(), summary.board(), summary.recordsRead(),
                    summary.inserted(), summary.updated(), summary.unchanged(), summary.failed(),
                    summary.expired(), summary.completeSnapshot());
            if (!summary.completeSnapshot()) {
                log.info("This batch was a SAMPLE, not a full reading of the board. Postings it"
                        + " did not carry were left untouched, not closed.");
            }
            status = summary.failed() == 0 ? 0 : 1;
        } catch (JobExportLockedException ex) {
            // Not a failure of this batch: another process holds the board. Its own exit
            // code, so a caller can retry later instead of investigating a bad file.
            log.warn("Import skipped: {}", ex.getMessage());
            status = 3;
        } catch (JobExportRejectedException ex) {
            log.error("Batch refused before any write: {}", ex.getMessage());
            status = 2;
        } catch (RuntimeException ex) {
            log.error("Import failed ({})", ex.getClass().getSimpleName());
            status = 1;
        }
        processExit.exit(status);
    }

    private static String single(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(values.size() - 1);
    }
}
