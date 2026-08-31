package aijob.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class JobDescriptionHashTest {

    @Test
    void sha256_unifiesNewlinesAndTrim() {
        String unix = "Junior Java\nREST APIs\n";
        String windows = "  Junior Java\r\nREST APIs\r\n  ";
        String oldMac = "Junior Java\rREST APIs";

        assertEquals(JobDescriptionHash.sha256(unix), JobDescriptionHash.sha256(windows));
        assertEquals(JobDescriptionHash.sha256(unix), JobDescriptionHash.sha256(oldMac));
        assertEquals(64, JobDescriptionHash.sha256(unix).length());
        assertNotEquals(JobDescriptionHash.sha256(unix), JobDescriptionHash.sha256(unix + " Docker"));
    }
}
