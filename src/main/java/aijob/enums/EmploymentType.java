package aijob.enums;

import java.util.Locale;

public enum EmploymentType {
    FULL_TIME,
    PART_TIME,
    CONTRACT,
    INTERNSHIP,
    TEMPORARY,
    OTHER,
    UNSPECIFIED;

    public static EmploymentType fromNullable(String value) {
        if (value == null || value.isBlank()) {
            return UNSPECIFIED;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ');
        return switch (normalized) {
            case "full time", "fulltime", "regular" -> FULL_TIME;
            case "part time", "parttime" -> PART_TIME;
            case "contract", "contractor", "freelance" -> CONTRACT;
            case "internship", "intern", "co op", "coop" -> INTERNSHIP;
            case "temporary", "temp", "seasonal" -> TEMPORARY;
            default -> OTHER;
        };
    }
}
