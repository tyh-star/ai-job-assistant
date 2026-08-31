package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RejectedTailorChange {

    public static final String EMPTY_REQUIRED_FIELD = "EMPTY_REQUIRED_FIELD";
    public static final String UNKNOWN_NODE_ID = "UNKNOWN_NODE_ID";
    public static final String NODE_NOT_ALLOWED = "NODE_NOT_ALLOWED";
    public static final String ORIGINAL_TEXT_MISMATCH = "ORIGINAL_TEXT_MISMATCH";
    public static final String TEXT_UNCHANGED = "TEXT_UNCHANGED";
    public static final String TEXT_TOO_LONG = "TEXT_TOO_LONG";
    public static final String UNKNOWN_EVIDENCE_TYPE = "UNKNOWN_EVIDENCE_TYPE";
    public static final String INFERRED_NOT_ALLOWED = "INFERRED_NOT_ALLOWED";
    public static final String DUPLICATE_NODE_ID = "DUPLICATE_NODE_ID";
    public static final String MAX_CHANGE_LIMIT_EXCEEDED = "MAX_CHANGE_LIMIT_EXCEEDED";
    public static final String MISSING_EVIDENCE = "MISSING_EVIDENCE";
    public static final String UNKNOWN_EVIDENCE_NODE_ID = "UNKNOWN_EVIDENCE_NODE_ID";
    public static final String EVIDENCE_QUOTE_MISMATCH = "EVIDENCE_QUOTE_MISMATCH";
    public static final String UNSUPPORTED_CLAIM = "UNSUPPORTED_CLAIM";
    public static final String CROSS_NODE_EVIDENCE_NOT_ALLOWED = "CROSS_NODE_EVIDENCE_NOT_ALLOWED";
    public static final String CROSS_ENTRY_EVIDENCE_NOT_ALLOWED = "CROSS_ENTRY_EVIDENCE_NOT_ALLOWED";
    public static final String LOW_VALUE_REWRITE = "LOW_VALUE_REWRITE";
    public static final String ROLE_SEMANTIC_ESCALATION = "ROLE_SEMANTIC_ESCALATION";
    public static final String INFORMATION_LOSS = "INFORMATION_LOSS";
    public static final String SKILL_REMOVAL_NOT_ALLOWED = "SKILL_REMOVAL_NOT_ALLOWED";
    public static final String GRAMMAR_QUALITY_FAILED = "GRAMMAR_QUALITY_FAILED";
    public static final String KEYWORD_STUFFING = "KEYWORD_STUFFING";
    public static final String RELATION_NOT_SUPPORTED = "RELATION_NOT_SUPPORTED";
    public static final String SOURCE_FACT_WOULD_DUPLICATE = "SOURCE_FACT_WOULD_DUPLICATE";
    public static final String UNSUPPORTED_ACTION = "UNSUPPORTED_ACTION";
    public static final String OPPORTUNITY_NOT_RESOLVED = "OPPORTUNITY_NOT_RESOLVED";
    public static final String FACT_CARDINALITY_CHANGED = "FACT_CARDINALITY_CHANGED";

    private String nodeId;
    private String reasonCode;
}
