package aijob.collector.jsonl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The boards this deployment is allowed to collect from.
 *
 * <p>Checked here as well as in the pipeline script, deliberately. The script decides
 * what to export; this decides what may be written to the catalog, and they are different
 * questions with different attack surfaces. A batch that arrives by another route — an
 * operator running the CLI by hand, a file copied from elsewhere, a stale directory — is
 * refused at the boundary that matters even though the script never saw it.
 *
 * <p>The file is read fresh on each import rather than cached. An import is a rare,
 * operator-triggered event, and reading the current file means an entry disabled five
 * minutes ago is actually disabled.
 */
@Component
public class SourceAllowlist {

    private static final Logger log = LoggerFactory.getLogger(SourceAllowlist.class);

    /** Bounded: this is a hand-maintained config file, not a data feed. */
    private static final long MAX_BYTES = 1L * 1024 * 1024;

    private final Path path;
    private final ObjectMapper objectMapper;

    public SourceAllowlist(@Value("${aijob.collector.allowlist-file:config/sources-allowlist.json}") String file,
                           ObjectMapper objectMapper) {
        this.path = Path.of(file);
        this.objectMapper = objectMapper;
    }

    /**
     * @throws JobExportRejectedException when the pair is not listed and enabled, or when
     *         the file itself cannot be read — an unreadable allowlist denies everything
     *         rather than allowing everything
     */
    public void requireAllowed(String provider, String board) {
        Entry match = load().stream()
                .filter(e -> equalsIgnoreCase(e.provider(), provider)
                        && equalsIgnoreCase(e.board(), board))
                .findFirst()
                .orElse(null);

        if (match == null) {
            throw new JobExportRejectedException(
                    "Source " + provider + "/" + board + " is not in " + path
                            + "; add it there, with the evidence for collecting it, before"
                            + " importing a batch from it");
        }
        if (!match.enabled()) {
            throw new JobExportRejectedException(
                    "Source " + provider + "/" + board + " is present in " + path
                            + " but disabled");
        }
        if (match.evidence() == null) {
            // The point of the file is the reasoning, not the list. An entry with no
            // stated basis is one nobody checked.
            throw new JobExportRejectedException(
                    "Source " + provider + "/" + board + " has no `evidence` in " + path);
        }
    }

    /** Every enabled pair, for the pipeline to iterate. */
    public List<Entry> enabled() {
        return load().stream().filter(Entry::enabled).toList();
    }

    private List<Entry> load() {
        if (!Files.isRegularFile(path)) {
            throw new JobExportRejectedException(
                    "No source allowlist at " + path.toAbsolutePath()
                            + "; refusing to import rather than treating a missing file as"
                            + " permission to collect from anything");
        }
        try {
            if (Files.size(path) > MAX_BYTES) {
                throw new JobExportRejectedException(path + " is larger than " + MAX_BYTES + " bytes");
            }
            File parsed = objectMapper.readValue(Files.readAllBytes(path), File.class);
            if (parsed == null || parsed.sources() == null) {
                throw new JobExportRejectedException(path + " declares no sources");
            }
            Set<String> seen = parsed.sources().stream()
                    .map(e -> key(e.provider(), e.board()))
                    .collect(Collectors.toSet());
            if (seen.size() != parsed.sources().size()) {
                // A duplicated pair means two entries disagree about one board, and which
                // one wins would come down to file order.
                throw new JobExportRejectedException(path + " lists the same provider/board twice");
            }
            return parsed.sources();
        } catch (IOException ex) {
            throw new JobExportRejectedException(
                    "Cannot read the source allowlist " + path + ": " + ex.getMessage());
        }
    }

    private static String key(String provider, String board) {
        return String.valueOf(provider).toLowerCase(Locale.ROOT) + "/"
                + String.valueOf(board).toLowerCase(Locale.ROOT);
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record File(int version, List<Entry> sources) {
    }

    /**
     * One allowed board. {@code evidence} is free-form on purpose — it is written for a
     * person reviewing the change, and pinning its shape would only invite filling fields
     * in to satisfy a schema.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Entry(String provider, String board, String company, boolean enabled,
                        Object evidence) {
    }
}
