package aijob.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResumeTextBlock {

    public static final String FEATURE_EMAIL = "EMAIL";
    public static final String FEATURE_PHONE = "PHONE";
    public static final String FEATURE_URL = "URL";
    public static final String FEATURE_DATE_RANGE = "DATE_RANGE";
    public static final String FEATURE_BULLET = "BULLET";
    public static final String FEATURE_ALL_CAPS = "ALL_CAPS";
    public static final String FEATURE_LARGE_FONT = "LARGE_FONT";
    public static final String FEATURE_COMMA_LIST = "COMMA_LIST";
    public static final String FEATURE_SECTION_HEADING = "SECTION_HEADING";

    public static final String CONFIDENCE_HIGH = "HIGH";
    public static final String CONFIDENCE_MEDIUM = "MEDIUM";
    public static final String CONFIDENCE_LOW = "LOW";

    public static final String PROVENANCE_RULE = "RULE";
    public static final String PROVENANCE_USER_EDITED = "USER_EDITED";
    public static final String PROVENANCE_LLM = "LLM";

    private String id;
    private int page;
    private float x;
    private float y;
    private float width;
    private float height;
    private float fontSize;
    private String fontFamily;
    private boolean bold;
    private boolean underline;
    private int columnIndex;
    private float maxInternalGap;
    private float contentX;
    private String text;
    private List<String> features;
    private String confidence;
    private String provenance;

    public void normalize() {
        if (text == null) {
            text = "";
        }
        if (fontFamily == null) {
            fontFamily = "";
        }
        if (features == null) {
            features = new ArrayList<>();
        }
        if (confidence == null || confidence.isBlank()) {
            confidence = CONFIDENCE_LOW;
        }
    }
}
