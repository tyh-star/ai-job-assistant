package aijob.dto;

import aijob.enums.TailorMode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties("generatedFilePath")
public class TailoredResumeResponse {

    private Long resumeId;
    private Long jobId;
    private TailorMode mode;
    private String generatedFileName;

    @JsonIgnore
    private String generatedFilePath;

    private String downloadUrl;
    private List<TailorChange> changes;
    private boolean unchanged;
    private int appliedChangeCount;
    private int rejectedChangeCount;
    private List<RejectedTailorChange> rejectedChanges;
    private Long proposalId;
    private String status;
    private LocalDateTime expiresAt;
}
