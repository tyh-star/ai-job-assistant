package aijob.freehire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** The envelope {@code GET /geo/cities} answers with: {@code {"data": [<cityMatch>...]}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FreeHireGeoCitiesResponseJson(List<FreeHireGeoCityJson> data) {

    public List<FreeHireGeoCityJson> dataOrEmpty() {
        return data == null ? List.of() : data;
    }
}
