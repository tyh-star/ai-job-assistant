package aijob.dto;

public final class RequirementPriority {

    public static final String MUST_HAVE = "MUST_HAVE";
    public static final String PREFERRED = "PREFERRED";
    public static final String OPTIONAL = "OPTIONAL";

    private RequirementPriority() {
    }

    public static int weight(String priority) {
        if (MUST_HAVE.equals(priority)) {
            return 3;
        }
        if (PREFERRED.equals(priority)) {
            return 2;
        }
        if (OPTIONAL.equals(priority)) {
            return 1;
        }
        return 0;
    }
}
