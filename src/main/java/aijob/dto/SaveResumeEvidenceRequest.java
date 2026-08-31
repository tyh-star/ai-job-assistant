package aijob.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SaveResumeEvidenceRequest {

    private String topic;
    private String category;
    private String answerStatus;
    private String entryKey;
    private List<String> relatedNodeIds = new ArrayList<>();
    private String context;
    private String action;
    private String result;
    private String metric;
    private Boolean confirmed;
}
