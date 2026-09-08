package aijob.exception;

/**
 * The history row does not exist <em>for this caller</em>. Cross-user ids are the same
 * 404 as a missing id -- never a 403 that would confirm the row exists.
 */
public class JobSearchHistoryNotFoundException extends RuntimeException {

    public JobSearchHistoryNotFoundException() {
        super("Job search history not found");
    }
}
