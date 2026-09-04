package aijob.freehire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * The {@code meta} block on every FreeHire list response: {@code total}/{@code limit}/
 * {@code offset}, plus {@code ignored_params} — confirmed live 2026-09-03 by sending
 * {@code country=it} (singular) alongside a real {@code countries=us} filter: the
 * response applied {@code countries=us} and reported
 * {@code [{"param":"country","did_you_mean":"countries"}]}, proving the two are
 * reported independently rather than one filter failure voiding the whole query.
 *
 * <p>This is the field that keeps a filter FreeHire silently drops from reading as "the
 * search succeeded" — {@code FreeHireJobCatalogService} surfaces it as a warning rather
 * than dropping it, exactly because a caller who sent {@code country=it} expecting Italy
 * and got the unfiltered catalog back would otherwise have no way to tell.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FreeHireMetaJson(
        long total,
        int limit,
        int offset,
        @JsonProperty("ignored_params") List<FreeHireIgnoredParamJson> ignoredParams
) {
    static final FreeHireMetaJson EMPTY = new FreeHireMetaJson(0, 0, 0, List.of());

    public List<FreeHireIgnoredParamJson> ignoredParamsOrEmpty() {
        return ignoredParams == null ? List.of() : ignoredParams;
    }
}
