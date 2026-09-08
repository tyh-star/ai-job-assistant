package aijob.service.impl;

import aijob.dto.JobApplicationStatsResponse;
import aijob.entity.Job;
import aijob.entity.User;
import aijob.exception.InvalidApplicationStatsException;
import aijob.exception.UserNotFoundException;
import aijob.mapper.JobMapper;
import aijob.mapper.UserMapper;
import aijob.service.JobApplicationStatsService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Counts currently-applied personal jobs. There is no apply-event log: repeating
 * {@code markApplied} on the same row does not create a second timestamp
 * ({@code COALESCE} in {@code JobMapper#markApplied}), and {@code clearApplied} drops
 * the row from this snapshot entirely. Saved-but-not-applied jobs are never counted.
 *
 * <p>{@code jobs.applied_at} is a {@code TIMESTAMP} written as UTC wall time via
 * {@code LocalDateTime.now(ZoneOffset.UTC)} / {@code LocalDateTime.ofInstant(..., UTC)}.
 * Buckets are computed here with {@link java.time.Instant} + the caller's IANA {@link ZoneId} so
 * this does not depend on MySQL timezone tables.
 */
@Service
public class JobApplicationStatsServiceImpl implements JobApplicationStatsService {

    static final Set<Integer> ALLOWED_DAYS = Set.of(7, 30, 90);

    private final UserMapper userMapper;
    private final JobMapper jobMapper;
    private final Clock clock;

    public JobApplicationStatsServiceImpl(
            UserMapper userMapper,
            JobMapper jobMapper,
            @Qualifier("jobSearchClock") Clock clock) {
        this.userMapper = userMapper;
        this.jobMapper = jobMapper;
        this.clock = clock;
    }

    @Override
    public JobApplicationStatsResponse getStats(String email, Integer days, String timeZone) {
        int window = days == null ? 30 : days;
        if (!ALLOWED_DAYS.contains(window)) {
            throw new InvalidApplicationStatsException("days must be 7, 30, or 90");
        }
        if (timeZone == null || timeZone.isBlank() || !ZoneId.getAvailableZoneIds().contains(timeZone)) {
            throw new InvalidApplicationStatsException("timeZone must be a valid IANA zone");
        }
        ZoneId zone = ZoneId.of(timeZone);

        User user = userMapper.findByEmail(email);
        if (user == null) {
            throw new UserNotFoundException("User not found");
        }

        List<Job> applied = jobMapper.findCurrentlyAppliedByUserId(user.getId());
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        Map<LocalDate, Integer> byDate = new HashMap<>();
        int totalApplied = applied.size();
        for (Job job : applied) {
            LocalDateTime appliedAt = job.getAppliedAt();
            if (appliedAt == null) {
                continue;
            }
            LocalDate localDate = appliedAt.toInstant(ZoneOffset.UTC).atZone(zone).toLocalDate();
            byDate.merge(localDate, 1, Integer::sum);
        }

        int todayCount = byDate.getOrDefault(today, 0);
        int last7 = countFrom(byDate, today.minusDays(6), today);
        int last30 = countFrom(byDate, today.minusDays(29), today);

        LocalDate start = today.minusDays(window - 1L);
        List<JobApplicationStatsResponse.DailyCount> daily = new ArrayList<>(window);
        for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
            daily.add(new JobApplicationStatsResponse.DailyCount(
                    day.toString(),
                    byDate.getOrDefault(day, 0)));
        }

        return new JobApplicationStatsResponse(todayCount, last7, last30, daily, totalApplied);
    }

    private static int countFrom(Map<LocalDate, Integer> byDate, LocalDate startInclusive, LocalDate endInclusive) {
        int total = 0;
        for (LocalDate day = startInclusive; !day.isAfter(endInclusive); day = day.plusDays(1)) {
            total += byDate.getOrDefault(day, 0);
        }
        return total;
    }
}
