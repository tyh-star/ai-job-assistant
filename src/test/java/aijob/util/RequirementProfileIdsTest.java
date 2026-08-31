package aijob.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementProfileIdsTest {

    @Test
    void stableId_isIndependentOfModelOrderAndCase() {
        String first = RequirementProfileIds.stableId("HARD_SKILL", "MUST_HAVE", "Spring Boot");
        String second = RequirementProfileIds.stableId("hard_skill", "must_have", " spring boot ");
        String other = RequirementProfileIds.stableId("HARD_SKILL", "PREFERRED", "Spring Boot");

        assertEquals(first, second);
        assertNotEquals(first, other);
        assertTrue(first.startsWith("req-"));
        assertEquals(20, first.length());
    }
}
