package aijob.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class JobSearchHistory {

    private Long id;
    private Long userId;
    private String keywords;
    private String city;
    private String cityCountry;
    private String country;
    private String workMode;
    private String employmentType;
    private Integer postedWithin;
    private String sortBy;
    private String criteriaHash;
    private int searchCount;
    private LocalDateTime createdAt;
    private LocalDateTime lastSearchedAt;
}
