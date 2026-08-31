package aijob.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ScanEnhancedOpportunityRequest {

    private List<String> skippedQuestionIds = new ArrayList<>();
}
