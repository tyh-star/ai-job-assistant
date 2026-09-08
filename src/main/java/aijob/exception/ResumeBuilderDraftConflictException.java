package aijob.exception;

/** The caller's {@code version} is stale -- someone else's save (another tab, another
 * device) already landed since it was read. The caller must reload rather than
 * silently overwrite. */
public class ResumeBuilderDraftConflictException extends RuntimeException {

    public ResumeBuilderDraftConflictException(String message) {
        super(message);
    }
}
