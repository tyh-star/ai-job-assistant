package aijob.rewrite;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything a Pro Resume Rewrite session knows, stored as JSON in
 * tailor_proposals.changes_json (mode = PRO_REWRITE). Personal details are stored as the
 * user typed them; they are redacted again before every AI call.
 */
public class ProRewriteState {

    public static final String VERIFIED = "VERIFIED";
    public static final String INFERRED = "INFERRED";
    public static final String UNSUPPORTED = "UNSUPPORTED";
    /** An INFERRED or UNSUPPORTED requirement the user confirmed with their own words. */
    public static final String USER_CONFIRMED = "USER_CONFIRMED";

    public Long resumeId;
    public Long jobId;
    public String model;
    public Requirements requirements = new Requirements();
    public List<Item> items = new ArrayList<>();
    public List<Question> questions = new ArrayList<>();
    public List<Suggestion> suggestions = new ArrayList<>();
    public List<FinalChange> finalChanges = new ArrayList<>();
    public int rejectedSuggestionCount;
    public String downloadFileName;

    public static class Requirements {
        public List<String> requiredSkills = new ArrayList<>();
        public List<String> preferredSkills = new ArrayList<>();
        public List<String> responsibilities = new ArrayList<>();
        public List<String> keywords = new ArrayList<>();
        public String experienceLevel = "";
    }

    /** One requirement from the job and how well the resume supports it. */
    public static class Item {
        public String id;
        /** REQUIRED_SKILL, PREFERRED_SKILL, RESPONSIBILITY or KEYWORD */
        public String category;
        public String text;
        public String status;
        public List<String> evidenceNodeIds = new ArrayList<>();
        public String note = "";

        public boolean usable() {
            return VERIFIED.equals(status) || USER_CONFIRMED.equals(status);
        }

        public boolean termLike() {
            return !"RESPONSIBILITY".equals(category);
        }
    }

    public static class Question {
        public String id;
        public String requirementId;
        public String question;
        public String reason = "";
        public Boolean hasExperience;
        public String detail;
    }

    public static class Suggestion {
        public String id;
        public String nodeId;
        public String originalText;
        public String revisedText;
        public String reason = "";
        public List<String> requirementIds = new ArrayList<>();
        /** VERIFIED (resume evidence only) or USER_CONFIRMED (relies on an answer). */
        public String evidenceStatus = VERIFIED;
    }

    public static class FinalChange {
        public String suggestionId;
        public String nodeId;
        public String originalText;
        public String finalText;
    }
}
