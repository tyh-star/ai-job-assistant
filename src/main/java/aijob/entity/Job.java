package aijob.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Job {

    private Long id;

    private Long userId;

    private String company;

    private String title;

    private String location;

    private String jobUrl;

    private String description;

    private String source;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
