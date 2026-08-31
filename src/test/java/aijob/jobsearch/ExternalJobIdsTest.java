package aijob.jobsearch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalJobIdsTest {

    @Test
    void persistIdIsSha256HexAndFitsVarchar64() {
        String raw = "abc-very-long-raw-id-that-exceeds-sixty-four-characters-xxxxxxxxxxxxxxxxxxxx";
        String persistId = ExternalJobIds.persistId("JSEARCH", raw);
        assertEquals(64, persistId.length());
        assertTrue(persistId.matches("[0-9a-f]{64}"));
        assertEquals(persistId, ExternalJobIds.persistId("JSEARCH", raw));
    }
}
