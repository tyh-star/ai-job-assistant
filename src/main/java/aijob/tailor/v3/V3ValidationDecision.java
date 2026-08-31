package aijob.tailor.v3;

public record V3ValidationDecision(boolean accepted, String reasonCode, String detail) {

    public static final String ACCEPTED = "ACCEPTED";
    public static final String CROSS_ENTRY_FACT = "CROSS_ENTRY_FACT";
    public static final String INVENTED_SKILL = "INVENTED_SKILL";
    public static final String INVENTED_CREDENTIAL = "INVENTED_CREDENTIAL";
    public static final String UNCONFIRMED_METRIC = "UNCONFIRMED_METRIC";
    public static final String ROLE_ESCALATION = "ROLE_ESCALATION";
    public static final String IDENTITY_MUTATION = "IDENTITY_MUTATION";
    public static final String USER_REJECTED_FACT = "USER_REJECTED_FACT";
    public static final String LOW_VALUE_REWRITE = "LOW_VALUE_REWRITE";
    public static final String MISSING_MATERIAL_FACT = "MISSING_MATERIAL_FACT";
    public static final String DUPLICATE_MATERIAL_FACT = "DUPLICATE_MATERIAL_FACT";
    public static final String INTRODUCED_RELATION = "INTRODUCED_RELATION";
    public static final String OPTIONAL_CONTEXT_MUTATION = "OPTIONAL_CONTEXT_MUTATION";
    public static final String MISSING_CONFIRMED_EVIDENCE = "MISSING_CONFIRMED_EVIDENCE";

    public static V3ValidationDecision accept() {
        return new V3ValidationDecision(true, ACCEPTED, "");
    }

    public static V3ValidationDecision reject(String reasonCode, String detail) {
        return new V3ValidationDecision(false, reasonCode, detail == null ? "" : detail);
    }
}
