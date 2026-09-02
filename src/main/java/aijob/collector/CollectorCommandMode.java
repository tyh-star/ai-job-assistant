package aijob.collector;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Whether this collector process was started to run one command and exit, rather than to
 * sit there collecting on a schedule.
 *
 * <p>The two modes have to be mutually exclusive, and {@code @Order} does not achieve
 * that. Ordering only sequences the {@code ApplicationRunner}s; the scheduler is not a
 * runner. {@code @EnableScheduling} is active for the whole {@code collector} profile, so
 * a long import with {@code JOB_COLLECTOR_ENABLED=true} in the environment would have the
 * periodic tick fire underneath it: an import and a crawl writing to the same board at
 * once, from one process, which the per-source lease cannot separate because it is the
 * same process holding it.
 *
 * <p>So the exclusion is declared here, read once from the command line, and consulted by
 * {@link JobCollectorScheduler} before it does anything.
 */
@Component
@Profile("collector")
public class CollectorCommandMode {

    /**
     * Arguments that mean "do this, then exit". Every one of them belongs to a runner that
     * ends the process when it is finished.
     */
    private static final String[] ONE_SHOT_ARGS = {
            JobExportImportRunner.JSONL_ARG,
            JobSourceAdminRunner.LIST_ARG,
            JobSourceAdminRunner.ADD_ARG,
            JobSourceAdminRunner.DUPLICATES_ARG,
            JobCollectorRunner.RUN_ONCE_ARG,
            JobCollectorRunner.SOURCE_ID_ARG,
    };

    private final boolean oneShot;

    // Explicit: a second, package-private constructor exists for tests, so Spring cannot
    // pick one by itself.
    @Autowired
    public CollectorCommandMode(ApplicationArguments args) {
        boolean found = false;
        for (String name : ONE_SHOT_ARGS) {
            if (args.containsOption(name)) {
                found = true;
                break;
            }
        }
        this.oneShot = found;
    }

    CollectorCommandMode(boolean oneShot) {
        this.oneShot = oneShot;
    }

    /**
     * @return true when this process exists to run one command; the periodic collection
     *         tick must stay out of its way.
     */
    public boolean isOneShotCommand() {
        return oneShot;
    }
}
