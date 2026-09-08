package aijob.dto;

import java.util.List;

/**
 * Counts of jobs the caller currently has marked {@code applied=true}, bucketed by the
 * caller's IANA timezone. {@code applied_at} is stored as UTC wall time (see
 * {@code JobMapper#markApplied}); bucketing happens in Java, never via MySQL
 * {@code CONVERT_TZ}.
 *
 * <p>{@code daily} always includes every local date in the requested window, including
 * zeros. Repeat-confirming the same job does not create a second event -- there is no
 * apply-event table; this is a snapshot of currently-applied rows.
 */
public record JobApplicationStatsResponse(
        int todayCount,
        int last7DaysCount,
        int last30DaysCount,
        List<DailyCount> daily,
        int totalApplied
) {
    public record DailyCount(String date, int count) {
    }
}
