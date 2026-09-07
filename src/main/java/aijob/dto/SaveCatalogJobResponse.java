package aijob.dto;

/**
 * Result of saving a catalog posting, or of confirming/revoking that the user applied to
 * one (which implicitly saves it first if it was not already saved -- see
 * {@code JobCatalogService#markApplied}, since {@code applied}/{@code appliedAt} live on
 * the same personal {@code jobs} row a save creates and are read from {@code job}'s own
 * fields rather than duplicated at this level).
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
