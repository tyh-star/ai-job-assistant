package aijob.exception;

/** No draft with that id exists for the caller -- including a draft that exists but
 * belongs to a different account, which must be indistinguishable from one that does
 * not exist at all. */
public class ResumeBuilderDraftNotFoundException extends RuntimeException {

    public ResumeBuilderDraftNotFoundException(String message) {
        super(message);
    }
}
