package aijob.jobsearch;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class GreenhouseJobUrl {

    private static final Set<String> ALLOWED_HOSTS = Set.of(
            "boards.greenhouse.io",
            "job-boards.greenhouse.io"
    );
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern JOB_ID = Pattern.compile("^[0-9]+$");

    private GreenhouseJobUrl() {
    }

    public record OfficialJob(String boardToken, String jobId) {
    }

    public static Optional<OfficialJob> parseOfficialJobUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = URI.create(raw.strip());
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        if (uri.getHost() == null || uri.getScheme() == null) {
            return Optional.empty();
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            return Optional.empty();
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!ALLOWED_HOSTS.contains(host)) {
            return Optional.empty();
        }
        if (uri.getUserInfo() != null) {
            return Optional.empty();
        }
        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String[] segments = path.split("/");
        String token = null;
        String jobId = null;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment == null || segment.isBlank()) {
                continue;
            }
            if (token == null) {
                token = segment;
                continue;
            }
            if ("jobs".equalsIgnoreCase(segment) && i + 1 < segments.length) {
                jobId = segments[i + 1];
                break;
            }
        }
        if (token == null || jobId == null) {
            return Optional.empty();
        }
        if (!TOKEN.matcher(token).matches() || !JOB_ID.matcher(jobId).matches()) {
            return Optional.empty();
        }
        return Optional.of(new OfficialJob(token, jobId));
    }
}
