package aijob.dto;

public final class CoverageStatus {

    public static final String SUPPORTED = "SUPPORTED";
    public static final String PARTIALLY_SUPPORTED = "PARTIALLY_SUPPORTED";
    public static final String MISSING_EVIDENCE = "MISSING_EVIDENCE";
    public static final String NOT_APPLICABLE = "NOT_APPLICABLE";

    private CoverageStatus() {
    }

    public static double weight(String status) {
        if (SUPPORTED.equals(status)) {
            return 1.0;
        }
        if (PARTIALLY_SUPPORTED.equals(status)) {
            return 0.5;
        }
        return 0.0;
    }
}
