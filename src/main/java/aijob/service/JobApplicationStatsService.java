package aijob.service;

import aijob.dto.JobApplicationStatsResponse;

public interface JobApplicationStatsService {

    /**
     * Snapshot of jobs this caller currently has {@code applied=true}. {@code days} is
     * the length of the {@code daily} series only (7, 30, or 90). {@code timeZone} must
     * be an IANA id from {@link java.time.ZoneId#getAvailableZoneIds()}.
     */
    JobApplicationStatsResponse getStats(String email, Integer days, String timeZone);
}
