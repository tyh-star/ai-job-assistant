package aijob.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserSavedCatalogJob {

    private Long id;

    private Long userId;

    private Long catalogJobId;

    private Long personalJobId;

    private LocalDateTime createdAt;
}
