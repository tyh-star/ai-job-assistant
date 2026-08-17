package aijob.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LayoutProfile {

    public static final int VERSION = 1;

    public static final String PAGE_LETTER = "LETTER";
    public static final String PAGE_A4 = "A4";
    public static final String PAGE_UNKNOWN = "UNKNOWN";

    public static final String ALIGN_LEFT = "LEFT";
    public static final String ALIGN_CENTER = "CENTER";

    public static final String DATE_LEFT = "LEFT";
    public static final String DATE_RIGHT = "RIGHT";
    public static final String DATE_INLINE = "INLINE";

    public static final String SKILL_GROUPED = "GROUPED";
    public static final String SKILL_FLAT = "FLAT";

    public static final String CONFIDENCE_HIGH = "HIGH";
    public static final String CONFIDENCE_MEDIUM = "MEDIUM";
    public static final String CONFIDENCE_LOW = "LOW";

    private int version = VERSION;
    private String pageSize;
    private float marginTopPt;
    private float marginBottomPt;
    private float marginLeftPt;
    private float marginRightPt;
    private String baseFontFamily;
    private float baseFontSizePt;
    private String headerAlignment;
    private String contactAlignment;
    private boolean hasHeaderRule;
    private SectionHeading sectionHeading;
    private Body body;
    private Bullet bullet;
    private SectionLayout sectionLayout;
    private String confidence;

    public static LayoutProfile lowConfidence() {
        LayoutProfile profile = new LayoutProfile();
        profile.normalize();
        profile.setConfidence(CONFIDENCE_LOW);
        return profile;
    }

    public void normalize() {
        if (version <= 0) {
            version = VERSION;
        }
        if (pageSize == null || pageSize.isBlank()) {
            pageSize = PAGE_UNKNOWN;
        }
        if (baseFontFamily == null) {
            baseFontFamily = "";
        }
        if (headerAlignment == null || headerAlignment.isBlank()) {
            headerAlignment = ALIGN_LEFT;
        }
        if (contactAlignment == null || contactAlignment.isBlank()) {
            contactAlignment = ALIGN_LEFT;
        }
        if (sectionHeading == null) {
            sectionHeading = new SectionHeading();
        }
        sectionHeading.normalize();
        if (body == null) {
            body = new Body();
        }
        body.normalize();
        if (bullet == null) {
            bullet = new Bullet();
        }
        bullet.normalize();
        if (sectionLayout == null) {
            sectionLayout = new SectionLayout();
        }
        sectionLayout.normalize();
        if (confidence == null || confidence.isBlank()) {
            confidence = CONFIDENCE_LOW;
        }
    }

    public boolean isHighOrMedium() {
        return CONFIDENCE_HIGH.equals(confidence) || CONFIDENCE_MEDIUM.equals(confidence);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SectionHeading {
        private String fontFamily;
        private float fontSizePt;
        private boolean bold;
        private boolean underline;
        private boolean allCaps;

        public void normalize() {
            if (fontFamily == null) {
                fontFamily = "";
            }
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Body {
        private String fontFamily;
        private float fontSizePt;
        private float lineSpacing;
        private float spaceAfterPt;

        public void normalize() {
            if (fontFamily == null) {
                fontFamily = "";
            }
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Bullet {
        private boolean enabled = true;
        private String symbol = "•";
        private float leftIndentPt;
        private float hangingIndentPt;

        public void normalize() {
            if (symbol == null || symbol.isBlank()) {
                symbol = "•";
            }
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SectionLayout {
        private String projectHeaderDateAlignment;
        private String educationDateAlignment;
        private String skillPresentation;
        private int columnCount = 1;

        public void normalize() {
            if (projectHeaderDateAlignment == null || projectHeaderDateAlignment.isBlank()) {
                projectHeaderDateAlignment = DATE_INLINE;
            }
            if (educationDateAlignment == null || educationDateAlignment.isBlank()) {
                educationDateAlignment = DATE_INLINE;
            }
            if (skillPresentation == null || skillPresentation.isBlank()) {
                skillPresentation = SKILL_FLAT;
            }
            if (columnCount != 2) {
                columnCount = 1;
            }
        }
    }
}
