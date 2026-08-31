package aijob.dto;

public final class EvidenceKind {

    public static final String EXPERIENCE_BULLET = "EXPERIENCE_BULLET";
    public static final String PROJECT_BULLET = "PROJECT_BULLET";
    public static final String SUMMARY = "SUMMARY";
    public static final String SKILLS_ONLY = "SKILLS_ONLY";
    public static final String EDUCATION = "EDUCATION";
    public static final String OTHER = "OTHER";

    private EvidenceKind() {
    }

    public static double strength(String kind) {
        if (kind == null) {
            return 0.0;
        }
        return switch (kind) {
            case EXPERIENCE_BULLET -> 1.0;
            case PROJECT_BULLET -> 0.9;
            case SUMMARY -> 0.6;
            case SKILLS_ONLY -> 0.4;
            case EDUCATION -> 0.6;
            case OTHER -> 0.3;
            default -> 0.0;
        };
    }

    public static String stronger(String left, String right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return strength(left) >= strength(right) ? left : right;
    }
}
