package aijob.dto;

import aijob.enums.PdfExportStatus;
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
    private String pdfDownloadUrl;
    private PdfExportStatus pdfExportStatus;
    private List<TailorChange> changes;
    /** True when no tailored file has been written yet. Not the same as "no suggestions". */
    private boolean unchanged;
    /** True when the proposal contains at least one suggested change. */
    private boolean hasSuggestions;
    private int appliedChangeCount;
    private int rejectedChangeCount;
    private List<RejectedTailorChange> rejectedChanges;
    private Long proposalId;
    private String status;
    private LocalDateTime expiresAt;
    private int addedSuggestionCount;
    private Boolean hasMoreSuggestions;
    private List<String> exploredNodeIds;
    private List<String> eligibleNodeIds;
    private List<String> remainingNodeIds;
    private List<String> noChangeNodeIds;
    private String coverageStatus;
    private String engineVersion;
    private String productMode;
    private Integer rewrittenEntryCount;
    private Integer unchangedEntryCount;
    private List<V3EntryReview> entryReviews;
    private List<Long> evidenceItemIds;
    private List<V3EvidenceUsage> evidenceUsage;
    private List<V3SkillReview> skillReviews;

    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_NO_SUPPORTED_GAP = "NO_SUPPORTED_GAP";
    public static final String STATUS_NEEDS_USER_EVIDENCE = "NEEDS_USER_EVIDENCE";
    public static final String STATUS_GENERATION_REJECTED = "GENERATION_REJECTED";
}
