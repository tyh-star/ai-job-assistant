package aijob.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Resume {

    private Long id;

    private Long userId;

    private String fileName;

    private String fileType;

    private String filePath;

    private String extractedText;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
