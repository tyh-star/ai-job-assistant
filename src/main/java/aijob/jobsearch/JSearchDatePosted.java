package aijob.jobsearch;

import aijob.dto.JobSearchRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

public final class JSearchDatePosted {

    private JSearchDatePosted() {
    }

    public static String queryValue(Integer postedWithinDays) {
        if (postedWithinDays == null) {
            return "all";
        }
        return switch (postedWithinDays) {
            case 1 -> "today";
            case 3 -> "3days";
            case 7 -> "week";
            case 14, 30 -> "month";
            default -> {
                if (!JobSearchRequest.ALLOWED_POSTED_WITHIN_DAYS.contains(postedWithinDays)) {
                    throw new IllegalArgumentException("postedWithinDays must be 1, 3, 7, 14, or 30");
                }
                yield "all";
            }
        };
    }

    public static boolean withinPostedWindow(String postedAt, Integer postedWithinDays, Clock clock) {
        if (postedWithinDays == null || postedWithinDays != 14) {
            return true;
        }
        Instant posted = parsePostedAt(postedAt);
        if (posted == null) {
            return false;
        }
        Instant cutoff = clock.instant().minusSeconds(14L * 24 * 60 * 60);
        return !posted.isBefore(cutoff);
    }

    static Instant parsePostedAt(String postedAt) {
        if (postedAt == null || postedAt.isBlank()) {
            return null;
        }
        String value = postedAt.strip();
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
