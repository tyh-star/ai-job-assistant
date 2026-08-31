package aijob.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsAllowedOriginsTest {

    @Test
    void trimsFiltersBlanksAndDeduplicates() {
        assertEquals(
                List.of(
                        "https://example.sslip.io",
                        "http://localhost:5173",
                        "http://127.0.0.1:5173"
                ),
                SecurityConfig.parseAllowedOrigins(
                        " https://example.sslip.io, http://localhost:5173,,http://localhost:5173, http://127.0.0.1:5173 "
                )
        );
    }

    @Test
    void rejectsWildcardOriginsAndPatterns() {
        assertThrows(IllegalStateException.class, () -> SecurityConfig.parseAllowedOrigins("*"));
        assertThrows(IllegalStateException.class, () -> SecurityConfig.parseAllowedOrigins("https://*"));
        assertThrows(
                IllegalStateException.class,
                () -> SecurityConfig.parseAllowedOrigins("http://localhost:5173,https://*.sslip.io")
        );
        assertThrows(IllegalStateException.class, () -> SecurityConfig.parseAllowedOrigins(" , , "));
        assertThrows(IllegalStateException.class, () -> SecurityConfig.parseAllowedOrigins(""));
    }

    @Test
    void securityConfigDoesNotHardcodeWildcardOrEc2Ip() throws Exception {
        String source = Files.readString(Path.of("src/main/java/aijob/config/SecurityConfig.java"));
        assertFalse(source.contains("setAllowedOriginPatterns"));
        assertFalse(source.contains("List.of(\"*\")"));
        assertFalse(source.contains("3.99.54.214"));
        assertTrue(source.contains("setAllowedOrigins"));
        assertTrue(source.contains("setAllowCredentials(false)"));
    }
}
