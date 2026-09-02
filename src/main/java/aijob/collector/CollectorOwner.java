package aijob.collector;

import java.lang.management.ManagementFactory;
import java.util.UUID;

/**
 * Identifies one collector process when it claims a source.
 *
 * <p>The value has to be unique per <em>process</em>, not per host: the scheduled
 * collector container and an operator's one-off {@code run --rm collector} run on the
 * same machine, and a lease is only ever released by the process that took it.
 */
final class CollectorOwner {

    /** {@code job_sources.locked_by} is VARCHAR(64). */
    private static final int MAX_LENGTH = 64;

    private static final String THIS_PROCESS = build();

    private CollectorOwner() {
    }

    static String forThisProcess() {
        return THIS_PROCESS;
    }

    private static String build() {
        // "pid@host" from the JVM, plus a random suffix so two containers started from
        // the same image, with the same pid namespace, still differ.
        String runtime = ManagementFactory.getRuntimeMXBean().getName()
                .replaceAll("[^A-Za-z0-9@._-]", "-");
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String owner = runtime + "-" + unique;
        return owner.length() <= MAX_LENGTH ? owner : owner.substring(owner.length() - MAX_LENGTH);
    }
}
