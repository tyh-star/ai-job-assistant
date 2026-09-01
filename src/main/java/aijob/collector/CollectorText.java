package aijob.collector;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

public final class CollectorText {

    private CollectorText() {
    }

    public static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /**
     * Parses the offset-bearing timestamps Greenhouse returns
     * ({@code 2016-01-14T10:55:28-05:00}) into UTC.
     *
     * @return {@code null} when the value is absent or unparseable; a posting with an
     *         unknown date is still worth keeping.
     */
    public static LocalDateTime parseIsoDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.strip();
        try {
            return OffsetDateTime.parse(text).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // Fall through to the local-datetime form.
        }
        try {
            return LocalDateTime.parse(text);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    /** Lever publishes {@code createdAt} as epoch milliseconds, not seconds. */
    public static LocalDateTime fromEpochMillis(Long epochMillis) {
        if (epochMillis == null || epochMillis <= 0) {
            return null;
        }
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC);
    }
}
