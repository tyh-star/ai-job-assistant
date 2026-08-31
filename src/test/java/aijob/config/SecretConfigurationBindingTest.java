package aijob.config;

import aijob.AiJobApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:aijobtest;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads"
        }
)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:aijobtest;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "jwt.secret=test-only-jwt-secret-key-must-be-32b!!"
})
class SecretConfigurationBindingTest {

    @Autowired
    private Environment environment;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${spring.datasource.password}")
    private String datasourcePassword;

    @Value("${aijob.storage.root}")
    private String storageRoot;

    @Test
    void bindsIndependentTestSecretsInsteadOfLocalMachineValues() {
        assertEquals("test-only-jwt-secret-key-must-be-32b!!", jwtSecret);
        assertEquals("test-only-h2-password", datasourcePassword);
        assertEquals("sa", environment.getProperty("spring.datasource.username"));
        assertTrue(environment.getProperty("spring.datasource.url", "").startsWith("jdbc:h2:mem:"));
        assertTrue(jwtSecret.length() >= 32);
        assertFalse(jwtSecret.toLowerCase().contains("tyh"));
        assertFalse(datasourcePassword.toLowerCase().contains("tyh"));
    }

    @Test
    void storageRootIsIsolatedFromRealUploads() {
        Path root = Path.of(storageRoot).toAbsolutePath().normalize();
        Path realUploads = Path.of("uploads").toAbsolutePath().normalize();
        assertEquals("target/aijob-test-uploads", storageRoot);
        assertFalse(root.startsWith(realUploads));
        assertFalse(realUploads.equals(root));
    }

    @Test
    void applicationYmlDoesNotContainAdzunaSecrets() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertFalse(yml.toLowerCase().contains("adzuna"));
        assertFalse(yml.contains("ADZUNA_APP_ID"));
        assertFalse(yml.contains("ADZUNA_APP_KEY"));
        assertFalse(yml.contains("ADZUNA_COUNTRY_CODE"));
        assertFalse(yml.contains("JSEARCH_API_KEY"));
        assertFalse(yml.contains("JSEARCH_API_BASE_URL"));
        assertFalse(yml.toLowerCase().contains("openwebninja"));
    }
}
