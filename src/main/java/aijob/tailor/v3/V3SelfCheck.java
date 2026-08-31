package aijob.tailor.v3;

public record V3SelfCheck(boolean passed, String notes) {
    public V3SelfCheck {
        notes = notes == null ? "" : notes;
    }

    public static V3SelfCheck empty() {
        return new V3SelfCheck(true, "");
    }
}
