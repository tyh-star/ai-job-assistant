package aijob.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobSearchResponse {

    private UUID searchSessionId;
    private String provider;
    private int page;
    private int pageSize;
    private Boolean hasMore;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long totalCount;
    private List<JobSearchResult> results = new ArrayList<>();
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Boolean limitReached;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Integer loadedResultCount;

    public JobSearchResponse(
            UUID searchSessionId,
            String provider,
            int page,
            int pageSize,
            Boolean hasMore,
            Long totalCount,
            List<JobSearchResult> results) {
        this(searchSessionId, provider, page, pageSize, hasMore, totalCount, results, null, null);
    }
}
