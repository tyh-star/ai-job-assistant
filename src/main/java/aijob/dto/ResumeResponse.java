package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeResponse {

    private Long id;
    private String fileName;
    private String fileType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String origin;
    private String reviewStatus;
    private Long parentResumeId;
    private Long canonicalResumeId;
    private boolean fileAvailable;
    private String downloadUrl;
}
