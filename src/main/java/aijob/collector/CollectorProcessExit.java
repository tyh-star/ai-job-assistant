package aijob.collector;

/**
 * Ends a one-shot collector process with a status an operator (or a script) can act on.
 *
 * <p>Behind an interface so the CLI runners can be unit-tested: the production
 * implementation really does terminate the JVM, which a test cannot survive.
 */
@FunctionalInterface
public interface CollectorProcessExit {

    /** @param code 0 on success; anything else means the command did not do its job. */
    void exit(int code);
}
