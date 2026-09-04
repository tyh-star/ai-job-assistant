package aijob.freehire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** The envelope {@code GET /agent/jobs/search} answers with: {@code {data, meta}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FreeHireSearchResponseJson(
        List<FreeHireJobJson> data,
        FreeHireMetaJson meta
) {
    public List<FreeHireJobJson> dataOrEmpty() {
        return data == null ? List.of() : data;
    }

    public FreeHireMetaJson metaOrEmpty() {
        return meta == null ? FreeHireMetaJson.EMPTY : meta;
    }
}
