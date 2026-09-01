package aijob.controller;

import aijob.AiJobApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Catalog routes are ordinary protected {@code /api/v1} endpoints: the existing
 * "everything except /api/v1/auth/** needs a JWT" rule is not relaxed for them.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:jobcatalogsecurity;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads",
                "JOB_SEARCH_PRIMARY_PROVIDER=LOCAL_CATALOG"
        }
)
@AutoConfigureMockMvc
class JobCatalogControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/job-catalog/search",
            "/api/v1/job-catalog/search?q=java&country=CA",
            "/api/v1/job-catalog/1",
    })
    void readingTheCatalogWithoutAJwtIsRejected(String path) throws Exception {
        assertRejected(mockMvc.perform(get(path)).andReturn().getResponse().getStatus());
    }

    @Test
    void savingFromTheCatalogWithoutAJwtIsRejected() throws Exception {
        assertRejected(mockMvc.perform(post("/api/v1/job-catalog/1/save"))
                .andReturn().getResponse().getStatus());
    }

    @Test
    void anInvalidBearerTokenIsRejectedToo() throws Exception {
        assertRejected(mockMvc.perform(get("/api/v1/job-catalog/search")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andReturn().getResponse().getStatus());
    }

    private static void assertRejected(int status) {
        assertNotEquals(200, status);
        assertTrue(status == 401 || status == 403, "status=" + status);
    }
}
