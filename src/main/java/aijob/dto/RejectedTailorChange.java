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

    private String nodeId;
    private String reasonCode;
}
