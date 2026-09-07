package aijob.exception;

/**
 * A catalog posting's own upstream detail lookup ({@code GET /jobs/{slug}} against
 * FreeHire) came back 404, even though the SAME posting was just listed in a search
 * result. Real, expected: a listing can be pulled by the source between being indexed
 * and being fetched individually (confirmed live -- see {@code FreeHireClient#getBySlug}).
 *
 * <p>Deliberately a SEPARATE type from {@link JobNotFoundException}, not a subclass of
 * it: {@code JobNotFoundException} covers this site's OWN 404s (an unowned personal job,
 * a malformed catalog id, ...), which must never be confused with "the upstream posting
 * itself is gone" -- the two have different causes and different correct frontend
 * responses (a generic retry vs. "this specific posting expired, refresh your search").
 * {@link aijob.exception.GlobalExceptionHandler} maps this to its own stable, matchable
 * message ({@code "JOB_DETAIL_NO_LONGER_AVAILABLE"}) so the frontend's {@code
 * apiErrorKey} can route it to specific copy instead of a generic error.
 */
public class JobDetailUnavailableException extends RuntimeException {

    public static final String CODE = "JOB_DETAIL_NO_LONGER_AVAILABLE";

    public JobDetailUnavailableException(String message) {
        super(message);
    }
}
