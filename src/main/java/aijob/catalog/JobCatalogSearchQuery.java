package aijob.catalog;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Resolved, already-validated parameters for a catalog search.
 *
 * <p>{@code useFullText} decides which keyword predicate the mapper emits, so the
 * MySQL FULLTEXT index is used in production while H2 tests stay on LIKE.
 */
@Data
public class JobCatalogSearchQuery {

    private String keyword;

    /** Pre-escaped {@code %kw%} form for the LIKE branch. */
    private String keywordLike;

    /** Pre-escaped {@code +kw*} form for the MySQL BOOLEAN MODE branch. */
    private String keywordBoolean;

    private boolean useFullText;

    private String location;

    private String countryCode;

    private String remoteType;

    private String employmentType;

    private LocalDateTime postedAfter;

    private List<String> statuses;

    /** Either {@code RELEVANCE} or {@code POSTED_AT}. */
    private String sort;

    private int offset;

    private int limit;
}
