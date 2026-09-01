package aijob.enums;

import java.util.Locale;

public enum RemoteType {
    REMOTE,
    HYBRID,
    ONSITE,
    UNSPECIFIED;

    public static RemoteType fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return UNSPECIFIED;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "remote", "fully-remote" -> REMOTE;
            case "hybrid" -> HYBRID;
            case "on-site", "onsite", "in-office" -> ONSITE;
            default -> UNSPECIFIED;
        };
    }
}
