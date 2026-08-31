package aijob.tailor.v3;

/**
 * Generation engine selector. V3-1 adds the flag and router only.
 * {@link #V2} is the frozen conservative path. {@link #V3} is reserved for
 * the entry-level generator that is not wired in V3-1.
 */
public enum TailorEngine {
    V2,
    V3;

    public static TailorEngine fromProperty(String raw) {
        if (raw == null || raw.isBlank()) {
            return V2;
        }
        String normalized = raw.strip().toUpperCase();
        if ("V3".equals(normalized)) {
            return V3;
        }
        return V2;
    }
}
