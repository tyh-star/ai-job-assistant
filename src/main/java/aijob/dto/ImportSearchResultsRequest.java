package aijob.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
public class ImportSearchResultsRequest {

    @NotNull
    private UUID searchSessionId;

    private List<String> selectedExternalJobIds = new ArrayList<>();
}
