package aijob.dto;

public final class UpliftType {

    public static final String SUBSTANTIVE_REWRITE = "SUBSTANTIVE_REWRITE";
    public static final String CLARITY_IMPROVEMENT = "CLARITY_IMPROVEMENT";
    public static final String GRAMMAR_FIX = "GRAMMAR_FIX";
    public static final String LOW_VALUE = "LOW_VALUE";

    private UpliftType() {
    }

    public static int rank(String upliftType) {
        if (SUBSTANTIVE_REWRITE.equals(upliftType)) {
            return 3;
        }
        if (CLARITY_IMPROVEMENT.equals(upliftType)) {
            return 2;
        }
        if (GRAMMAR_FIX.equals(upliftType)) {
            return 1;
        }
        return 0;
    }
}
