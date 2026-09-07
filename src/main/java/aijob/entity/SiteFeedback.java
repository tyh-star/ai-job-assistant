package aijob.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SiteFeedback {

    private Long id;

    private Long userId;

    private int rating;

    private String comment;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
