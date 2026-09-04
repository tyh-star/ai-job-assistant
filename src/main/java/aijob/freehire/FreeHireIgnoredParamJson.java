package aijob.freehire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One query param FreeHire's filter did not read. See {@link FreeHireMetaJson}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FreeHireIgnoredParamJson(
        String param,
        @JsonProperty("did_you_mean") String didYouMean
) {
}
