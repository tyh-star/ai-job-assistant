package aijob.dto;

/**
 * Result of saving a catalog posting.
 *
 * <p>{@code jobId} is an id in the user's own {@code jobs} table, so Match,
 * Quick Optimize and Enhanced Tailor keep working unchanged.
 */
public record SaveCatalogJobResponse(
        Long jobId,
        String catalogJobId,
        boolean alreadySaved,
        JobResponse job
) {
}
