package aijob.freehire;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The envelope {@code GET /jobs/{slug}} answers with: {@code {data: <job>}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FreeHireJobDetailResponseJson(FreeHireJobJson data) {
}
