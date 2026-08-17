package aijob.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResumeStructure {

    public static final int STRUCTURE_FORMAT_VERSION = 2;

    private Long resumeId;
    private String reviewStatus;
    private String extractionStatus;
    private Boolean llmClassified;
    private Integer structureFormatVersion;
    private ResumeContact contact;
    private String summary;
    private FieldProvenance summaryProvenance;
    private List<ResumeBullet> summaryBullets;
    private List<ResumeSkill> skills;
    private List<ResumeExperience> experience;
    private List<ResumeEducation> education;
    private List<ResumeProject> projects;
    private List<ResumeTextBlock> unassigned;
    private Map<String, String> fieldConfidence;
    private Map<String, String> fieldProvenance;
    private LayoutProfile layoutProfile;

    public static ResumeStructure empty() {
        ResumeStructure structure = new ResumeStructure();
        structure.normalize();
        return structure;
    }

    public void normalize() {
        if (contact == null) {
            contact = new ResumeContact();
        }
        contact.normalize();
        if (summary == null) {
            summary = "";
        }
        if (summaryProvenance == null) {
            summaryProvenance = new FieldProvenance();
        }
        summaryProvenance.normalize();
        if (summaryBullets == null) {
            summaryBullets = new ArrayList<>();
        } else {
            summaryBullets.forEach(ResumeBullet::normalize);
        }
        if (skills == null) {
            skills = new ArrayList<>();
        } else {
            skills.forEach(ResumeSkill::normalize);
        }
        if (experience == null) {
            experience = new ArrayList<>();
        } else {
            experience.forEach(ResumeExperience::normalize);
        }
        if (education == null) {
            education = new ArrayList<>();
        } else {
            education.forEach(ResumeEducation::normalize);
        }
        if (projects == null) {
            projects = new ArrayList<>();
        } else {
            projects.forEach(ResumeProject::normalize);
        }
        if (fieldConfidence == null) {
            fieldConfidence = new LinkedHashMap<>();
        }
        if (fieldProvenance == null) {
            fieldProvenance = new LinkedHashMap<>();
        }
        if (unassigned == null) {
            unassigned = new ArrayList<>();
        } else {
            unassigned.forEach(ResumeTextBlock::normalize);
        }
        if (extractionStatus == null) {
            extractionStatus = "";
        }
        if (llmClassified == null) {
            llmClassified = Boolean.FALSE;
        }
        if (layoutProfile != null) {
            layoutProfile.normalize();
        }
    }

    public boolean hasCurrentLlmFormat() {
        return Boolean.TRUE.equals(llmClassified)
                && structureFormatVersion != null
                && structureFormatVersion >= STRUCTURE_FORMAT_VERSION;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResumeBullet {
        private String text;
        private List<String> sourceBlockIds;
        private Integer sourcePage;
        private String sourceText;
        private String confidence;
        private String provenance;

        public void normalize() {
            if (text == null) {
                text = "";
            }
            if (sourceBlockIds == null) {
                sourceBlockIds = new ArrayList<>();
            }
            if (sourceText == null) {
                sourceText = "";
            }
            if (confidence == null || confidence.isBlank()) {
                confidence = ResumeTextBlock.CONFIDENCE_LOW;
            }
            if (provenance == null) {
                provenance = "";
            }
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResumeContact {
        private String fullName;
        private String email;
        private String phone;
        private String location;
        private List<String> links;

        public void normalize() {
            if (fullName == null) {
                fullName = "";
            }
            if (email == null) {
                email = "";
            }
            if (phone == null) {
                phone = "";
            }
            if (location == null) {
                location = "";
            }
            if (links == null) {
                links = new ArrayList<>();
            }
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResumeSkill {
        private String name;
        private List<String> sourceBlockIds;
        private Integer sourcePage;
        private String sourceText;
        private String confidence;
        private String provenance;

        public void normalize() {
            if (name == null) {
                name = "";
            }
            if (sourceBlockIds == null) {
                sourceBlockIds = new ArrayList<>();
            }
            if (sourceText == null) {
                sourceText = "";
            }
            if (confidence == null || confidence.isBlank()) {
                confidence = ResumeTextBlock.CONFIDENCE_LOW;
            }
            if (provenance == null) {
                provenance = "";
            }
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResumeExperience {
        private String company;
        private String title;
        private String startDate;
        private String endDate;
        private String location;
        private List<String> bullets;
        private List<String> sourceBlockIds;
        private Integer sourcePage;
        private String sourceText;
        private String confidence;
        private String provenance;

        public void normalize() {
            if (company == null) {
                company = "";
            }
            if (title == null) {
                title = "";
            }
            if (startDate == null) {
                startDate = "";
            }
            if (endDate == null) {
                endDate = "";
            }
            if (location == null) {
                location = "";
            }
            if (bullets == null) {
                bullets = new ArrayList<>();
            }
            if (sourceBlockIds == null) {
                sourceBlockIds = new ArrayList<>();
            }
            if (sourceText == null) {
                sourceText = "";
            }
            if (confidence == null || confidence.isBlank()) {
                confidence = ResumeTextBlock.CONFIDENCE_LOW;
            }
            if (provenance == null) {
                provenance = "";
            }
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResumeEducation {
        private String school;
        private String degree;
        private String dates;
        private String details;
        private List<String> sourceBlockIds;
        private Integer sourcePage;
        private String sourceText;
        private String confidence;
        private String provenance;

        public void normalize() {
            if (school == null) {
                school = "";
            }
            if (degree == null) {
                degree = "";
            }
            if (dates == null) {
                dates = "";
            }
            if (details == null) {
                details = "";
            }
            if (sourceBlockIds == null) {
                sourceBlockIds = new ArrayList<>();
            }
            if (sourceText == null) {
                sourceText = "";
            }
            if (confidence == null || confidence.isBlank()) {
                confidence = ResumeTextBlock.CONFIDENCE_LOW;
            }
            if (provenance == null) {
                provenance = "";
            }
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResumeProject {
        private String name;
        private String organizationOrType;
        private String dates;
        private List<String> bullets;
        private List<String> links;
        private List<String> sourceBlockIds;
        private Integer sourcePage;
        private String sourceText;
        private String confidence;
        private String provenance;

        public void normalize() {
            if (name == null) {
                name = "";
            }
            if (organizationOrType == null) {
                organizationOrType = "";
            }
            if (dates == null) {
                dates = "";
            }
            if (bullets == null) {
                bullets = new ArrayList<>();
            }
            if (links == null) {
                links = new ArrayList<>();
            }
            if (sourceBlockIds == null) {
                sourceBlockIds = new ArrayList<>();
            }
            if (sourceText == null) {
                sourceText = "";
            }
            if (confidence == null || confidence.isBlank()) {
                confidence = ResumeTextBlock.CONFIDENCE_LOW;
            }
            if (provenance == null) {
                provenance = "";
            }
        }
    }
}
