package aijob.dto;

public final class AnswerStatus {

    public static final String CONFIRMED_USED = "CONFIRMED_USED";
    public static final String CONFIRMED_NOT_USED = "CONFIRMED_NOT_USED";

    private AnswerStatus() {
    }

    public static boolean isValid(String status) {
        return CONFIRMED_USED.equals(status) || CONFIRMED_NOT_USED.equals(status);
    }
}
