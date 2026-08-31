package aijob.jobsearch;

import aijob.dto.JobSearchRequest;
import aijob.exception.JobSearchSessionExpiredException;
import aijob.exception.JobSearchSessionNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CaffeineSearchSessionStoreTest {

    @Test
    void wrongUserIsTreatedAsMissing() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-21T00:00:00Z"));
        CaffeineSearchSessionStore store = new CaffeineSearchSessionStore(clock);
        SearchSession session = store.create(
                1L,
                "JSEARCH",
                JobSearchCapability.FULL_DESCRIPTION,
                new JobSearchRequest("java", null, null, 1, 10),
                List.of());
        assertThrows(JobSearchSessionNotFoundException.class, () -> store.requireForUser(session.id(), 2L));
        assertEquals(session.id(), store.requireForUser(session.id(), 1L).id());
    }

    @Test
    void create_storesNextCursorOnSession() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-21T00:00:00Z"));
        CaffeineSearchSessionStore store = new CaffeineSearchSessionStore(clock);
        SearchSession session = store.create(
                1L,
                "JSEARCH",
                JobSearchCapability.FULL_DESCRIPTION,
                new JobSearchRequest("java", null, null, 1, 10),
                List.of(),
                "next-page-token");
        assertEquals("next-page-token", store.requireForUser(session.id(), 1L).nextCursor());
    }

    @Test
    void expiredSessionReturns410() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-21T00:00:00Z"));
        CaffeineSearchSessionStore store = new CaffeineSearchSessionStore(clock);
        SearchSession session = store.create(
                1L,
                "JSEARCH",
                JobSearchCapability.FULL_DESCRIPTION,
                new JobSearchRequest("java", null, null, 1, 10),
                List.of());
        clock.advance(Duration.ofMinutes(16));
        assertThrows(JobSearchSessionExpiredException.class, () -> store.requireForUser(session.id(), 1L));
        assertThrows(JobSearchSessionNotFoundException.class, () -> store.requireForUser(UUID.randomUUID(), 1L));
    }

    @Test
    void replaceKeepsOriginalExpiry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-21T00:00:00Z"));
        CaffeineSearchSessionStore store = new CaffeineSearchSessionStore(clock);
        SearchSession session = store.create(
                1L,
                "JSEARCH",
                JobSearchCapability.FULL_DESCRIPTION,
                new JobSearchRequest("java", null, null, 1, 10),
                List.of(),
                "cursor-1");
        Instant expiresAt = session.expiresAt();
        clock.advance(Duration.ofMinutes(5));
        SearchSession replaced = store.replace(session.withNextPage(List.of(), "cursor-2", "hash"));
        assertEquals(expiresAt, replaced.expiresAt());
        assertEquals(2, replaced.currentPage());
        assertEquals("cursor-2", replaced.nextCursor());
        clock.advance(Duration.ofMinutes(11));
        assertThrows(JobSearchSessionExpiredException.class, () -> store.requireForUser(session.id(), 1L));
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
