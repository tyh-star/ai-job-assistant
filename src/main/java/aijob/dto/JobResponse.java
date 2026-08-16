package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class JobResponse {

    private Long id;
    private String company;
    private String title;
    private String location;
    private String jobUrl;
    private String description;
    private String source;
    private String status;
    private String externalJobId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
