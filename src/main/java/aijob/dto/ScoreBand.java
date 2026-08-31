package aijob.dto;

public final class ScoreBand {

    public static final String STRONG = "STRONG";
    public static final String MODERATE = "MODERATE";
    public static final String WEAK = "WEAK";
    public static final String POOR = "POOR";

    private ScoreBand() {
    }

    public static String of(int overallScore) {
        if (overallScore >= 85) {
            return STRONG;
        }
        if (overallScore >= 70) {
            return MODERATE;
        }
        if (overallScore >= 50) {
            return WEAK;
        }
        return POOR;
    }
}
