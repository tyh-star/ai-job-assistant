package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceQuestion {

    private String id;
    private String requirementId;
    private String questionType;
    private String topic;
    private String priority;
    private String promptZh;
    private String promptEn;
    private List<String> missingActions = new ArrayList<>();
    private List<EntryOption> entryOptions = new ArrayList<>();
}
