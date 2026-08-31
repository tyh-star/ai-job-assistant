package aijob.tailor.v3;

public record V3QualityScore(
        int factIntegrity,
        int jdRelevance,
        int materialUplift,
        int clarityNaturalness,
        int specificity,
        int concision,
        int total,
        boolean admitted,
        boolean languageRegression,
        String notes
) {
    public static V3QualityScore originalBaseline(
            int factIntegrity,
            int jdRelevance,
            int clarityNaturalness,
            int specificity,
            int concision,
            String notes) {
        int total = weighted(factIntegrity, jdRelevance, 50, clarityNaturalness, specificity, concision);
        return new V3QualityScore(
                factIntegrity, jdRelevance, 50, clarityNaturalness, specificity, concision,
                total, false, false, notes == null ? "" : notes);
    }

    public static int weighted(
            int factIntegrity,
            int jdRelevance,
            int materialUplift,
            int clarityNaturalness,
            int specificity,
            int concision) {
        return (int) Math.round(
                factIntegrity * 0.25
                        + jdRelevance * 0.20
                        + materialUplift * 0.20
                        + clarityNaturalness * 0.15
                        + specificity * 0.10
                        + concision * 0.10);
    }

    public static boolean meetsAdmission(V3QualityScore variant, V3QualityScore original) {
        if (variant == null || original == null) {
            return false;
        }
        return !variant.languageRegression()
                && variant.total() >= 75
                && variant.clarityNaturalness() >= 70
                && variant.factIntegrity() >= 90
                && variant.total() >= original.total() + 8;
    }
}
