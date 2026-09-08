package aijob.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordStrengthTest {

    static final String VALID = "Password123!";

    @Test
    void acceptsAPasswordMeetingEveryRule() {
        assertTrue(PasswordStrength.isStrong(VALID));
        PasswordStrength.requireStrong(VALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "password123!",
            "PASSWORD123!",
            "Password!!!!",
            "Password1234",
            "Pass1!",
            " Password123!",
            "Password123! ",
            "Pass word123!",
            "Password123!\n",
            "Password123!\t"
    })
    void rejectsMissingClassTooShortOrWhitespace(String password) {
        assertFalse(PasswordStrength.isStrong(password));
        assertThrows(aijob.exception.WeakPasswordException.class, () -> PasswordStrength.requireStrong(password));
    }

    @Test
    void rejectsNullAndDoesNotTreatWhitespaceOnlyAsBlankAfterTrim() {
        assertFalse(PasswordStrength.isStrong(null));
        assertFalse(PasswordStrength.isStrong("        "));
        assertFalse(PasswordStrength.isStrong("Pass12!"));
    }

    @Test
    void rejectsLongerThan72WithoutTrimming() {
        String seventyTwo = "Aa1!" + "x".repeat(68);
        String seventyThree = seventyTwo + "x";
        assertTrue(PasswordStrength.isStrong(seventyTwo));
        assertFalse(PasswordStrength.isStrong(seventyThree));
    }

    @Test
    void rejectsIsoControlCharacters() {
        assertFalse(PasswordStrength.isStrong("Passw0rd!\u0001"));
    }
}
