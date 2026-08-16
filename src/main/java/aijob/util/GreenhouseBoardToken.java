package aijob.util;

import aijob.exception.InvalidGreenhouseBoardException;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class GreenhouseBoardToken {

    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "boards.greenhouse.io",
            "job-boards.greenhouse.io"
    );

    private GreenhouseBoardToken() {
    }

    public static String parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidGreenhouseBoardException("Board token is required");
        }

        String trimmed = raw.trim();
        if (looksLikeUrl(trimmed)) {
            return tokenFromOfficialBoardUrl(trimmed);
        }
        return requireToken(trimmed);
    }

    private static boolean looksLikeUrl(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String tokenFromOfficialBoardUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new InvalidGreenhouseBoardException("Invalid Greenhouse board URL");
        }

        String host = uri.getHost();
        if (host == null || !ALLOWED_HOSTS.contains(host.toLowerCase(Locale.ROOT))) {
            throw new InvalidGreenhouseBoardException("Invalid Greenhouse board URL");
        }

        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            throw new InvalidGreenhouseBoardException("Invalid Greenhouse board URL");
        }

        String[] segments = path.split("/");
        for (String segment : segments) {
            if (!segment.isBlank()) {
                return requireToken(segment);
            }
        }
        throw new InvalidGreenhouseBoardException("Invalid Greenhouse board URL");
    }

    private static String requireToken(String token) {
        if (!TOKEN.matcher(token).matches()) {
            throw new InvalidGreenhouseBoardException("Invalid Greenhouse board token");
        }
        return token;
    }
}
