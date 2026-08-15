package aijob.dto;

import aijob.enums.TailorMode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TailoredResumeResponse {

    private Long resumeId;
    private Long jobId;
    private TailorMode mode;
    private String generatedFileName;
    private String generatedFilePath;
    private List<TailorChange> changes;
    private boolean unchanged;
    private int appliedChangeCount;
    private int rejectedChangeCount;
}
