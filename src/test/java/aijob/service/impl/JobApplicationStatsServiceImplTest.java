package aijob.service.impl;

import aijob.dto.JobApplicationStatsResponse;
import aijob.entity.Job;
import aijob.entity.User;
import aijob.exception.InvalidApplicationStatsException;
import aijob.mapper.JobMapper;
import aijob.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobApplicationStatsServiceImplTest {

    private static final String EMAIL = "alex.morgan@example.com";
    private static final ZoneOffset UTC = ZoneOffset.UTC;

    private UserMapper userMapper;
    private JobMapper jobMapper;
    private JobApplicationStatsServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        jobMapper = mock(JobMapper.class);
        User user = new User();
        user.setId(11L);
        user.setEmail(EMAIL);
        when(userMapper.findByEmail(EMAIL)).thenReturn(user);
    }

    @Test
    void utcMidnightIsBucketedInTheCallerTimezoneNotUtcDate() {
        // 2026-03-09T03:00Z is still 2026-03-08 22:00 in America/Toronto (EST, UTC-5).
        Clock clock = Clock.fixed(Instant.parse("2026-03-09T12:00:00Z"), UTC);
        service = new JobApplicationStatsServiceImpl(userMapper, jobMapper, clock);
        when(jobMapper.findCurrentlyAppliedByUserId(11L)).thenReturn(List.of(
                applied(LocalDateTime.of(2026, 3, 9, 0, 30)),
                applied(LocalDateTime.of(2026, 3, 8, 12, 0))
        ));

        JobApplicationStatsResponse stats = service.getStats(EMAIL, 7, "America/Toronto");

        assertEquals(0, stats.todayCount());
        assertEquals(2, stats.last7DaysCount());
        assertEquals(2, stats.totalApplied());
        assertEquals(7, stats.daily().size());
        assertEquals("2026-03-09", stats.daily().get(6).date());
        assertEquals(0, stats.daily().get(6).count());
        assertEquals("2026-03-08", stats.daily().get(5).date());
        assertEquals(2, stats.daily().get(5).count());
    }

    @Test
    void todayYesterdaySeventhAndThirtiethBoundariesAndOutsideRange() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-07T16:00:00Z"), UTC);
        service = new JobApplicationStatsServiceImpl(userMapper, jobMapper, clock);
        when(jobMapper.findCurrentlyAppliedByUserId(11L)).thenReturn(List.of(
                applied(LocalDateTime.of(2026, 9, 7, 15, 0)),
                applied(LocalDateTime.of(2026, 9, 6, 15, 0)),
                applied(LocalDateTime.of(2026, 9, 1, 15, 0)),
                applied(LocalDateTime.of(2026, 8, 9, 15, 0)),
                applied(LocalDateTime.of(2026, 8, 8, 15, 0))
        ));

        JobApplicationStatsResponse seven = service.getStats(EMAIL, 7, "UTC");
        assertEquals(1, seven.todayCount());
        assertEquals(3, seven.last7DaysCount());
        assertEquals(4, seven.last30DaysCount());
        assertEquals(7, seven.daily().size());
        assertEquals("2026-09-01", seven.daily().get(0).date());
        assertEquals(1, seven.daily().get(0).count());
        assertEquals("2026-09-07", seven.daily().get(6).date());
        assertEquals(1, seven.daily().get(6).count());
        assertEquals(3, seven.daily().stream().mapToInt(JobApplicationStatsResponse.DailyCount::count).sum());

        JobApplicationStatsResponse thirty = service.getStats(EMAIL, 30, "UTC");
        assertEquals(30, thirty.daily().size());
        assertEquals("2026-08-09", thirty.daily().get(0).date());
        assertEquals(1, thirty.daily().get(0).count());
        assertEquals(0, thirty.daily().stream()
                .filter(day -> day.date().equals("2026-08-08"))
                .count());
        assertEquals(4, thirty.daily().stream().mapToInt(JobApplicationStatsResponse.DailyCount::count).sum());
        assertEquals(5, thirty.totalApplied());

        JobApplicationStatsResponse ninety = service.getStats(EMAIL, 90, "UTC");
        assertEquals(90, ninety.daily().size());
        assertEquals(1, ninety.daily().stream()
                .filter(day -> day.date().equals("2026-08-08"))
                .mapToInt(JobApplicationStatsResponse.DailyCount::count)
                .sum());
        assertEquals(5, ninety.daily().stream().mapToInt(JobApplicationStatsResponse.DailyCount::count).sum());
    }

    @Test
    void missingDaysAreZeroFilled() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-07T16:00:00Z"), UTC);
        service = new JobApplicationStatsServiceImpl(userMapper, jobMapper, clock);
        when(jobMapper.findCurrentlyAppliedByUserId(11L)).thenReturn(List.of());
        JobApplicationStatsResponse stats = service.getStats(EMAIL, 7, "America/Toronto");
        assertEquals(7, stats.daily().size());
        assertEquals(0, stats.todayCount());
        assertEquals(0, stats.daily().stream().mapToInt(JobApplicationStatsResponse.DailyCount::count).sum());
    }

    @Test
    void dstSpringForwardStillBucketsByLocalDate() {
        // America/Toronto 2026-03-08 02:00 EST -> 03:00 EDT.
        Clock clock = Clock.fixed(Instant.parse("2026-03-08T12:00:00Z"), UTC);
        service = new JobApplicationStatsServiceImpl(userMapper, jobMapper, clock);
        when(jobMapper.findCurrentlyAppliedByUserId(11L)).thenReturn(List.of(
                applied(LocalDateTime.of(2026, 3, 8, 6, 30)),
                applied(LocalDateTime.of(2026, 3, 8, 7, 30))
        ));

        JobApplicationStatsResponse stats = service.getStats(EMAIL, 7, "America/Toronto");
        assertEquals(2, stats.todayCount());
        assertEquals("2026-03-08", stats.daily().get(6).date());
        assertEquals(2, stats.daily().get(6).count());
    }

    @Test
    void dstFallBackStillBucketsByLocalDate() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-01T18:00:00Z"), UTC);
        service = new JobApplicationStatsServiceImpl(userMapper, jobMapper, clock);
        when(jobMapper.findCurrentlyAppliedByUserId(11L)).thenReturn(List.of(
                applied(LocalDateTime.of(2026, 11, 1, 5, 30)),
                applied(LocalDateTime.of(2026, 11, 1, 6, 30))
        ));
        JobApplicationStatsResponse stats = service.getStats(EMAIL, 7, "America/Toronto");
        assertEquals(2, stats.todayCount());
        assertEquals("2026-11-01", stats.daily().get(6).date());
        assertEquals(2, stats.daily().get(6).count());
    }

    @Test
    void torontoMidnightBoundaryUsesTheRealUtcInstant() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-09T12:00:00Z"), UTC);
        service = new JobApplicationStatsServiceImpl(userMapper, jobMapper, clock);
        when(jobMapper.findCurrentlyAppliedByUserId(11L)).thenReturn(List.of(
                applied(LocalDateTime.of(2026, 3, 9, 3, 59, 59)),
                applied(LocalDateTime.of(2026, 3, 9, 4, 0, 0))
        ));
        JobApplicationStatsResponse stats = service.getStats(EMAIL, 7, "America/Toronto");
        assertEquals(1, stats.todayCount());
        assertEquals("2026-03-08", stats.daily().get(5).date());
        assertEquals(1, stats.daily().get(5).count());
        assertEquals("2026-03-09", stats.daily().get(6).date());
        assertEquals(1, stats.daily().get(6).count());
    }

    @Test
    void rejectsUnknownTimezoneAndDisallowedDays() {
        Clock clock = Clock.fixed(Instant.parse("2026-03-09T03:00:00Z"), UTC);
        service = new JobApplicationStatsServiceImpl(userMapper, jobMapper, clock);
        assertThrows(InvalidApplicationStatsException.class,
                () -> service.getStats(EMAIL, 14, "America/Toronto"));
        assertThrows(InvalidApplicationStatsException.class,
                () -> service.getStats(EMAIL, 30, "Not/AZone"));
        assertThrows(InvalidApplicationStatsException.class,
                () -> service.getStats(EMAIL, 30, "+01:00"));
    }

    @Test
    void savedButNotAppliedAndNullTimestampDoNotInflateDailyBuckets() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-07T15:00:00Z"), UTC);
        service = new JobApplicationStatsServiceImpl(userMapper, jobMapper, clock);
        Job noTimestamp = applied(null);
        when(jobMapper.findCurrentlyAppliedByUserId(11L)).thenReturn(List.of(noTimestamp));

        JobApplicationStatsResponse stats = service.getStats(EMAIL, 30, "America/Toronto");
        assertEquals(1, stats.totalApplied());
        assertEquals(0, stats.todayCount());
        assertEquals(0, stats.daily().stream().mapToInt(JobApplicationStatsResponse.DailyCount::count).sum());
    }

    private static Job applied(LocalDateTime appliedAt) {
        Job job = new Job();
        job.setApplied(true);
        job.setAppliedAt(appliedAt);
        return job;
    }
}
