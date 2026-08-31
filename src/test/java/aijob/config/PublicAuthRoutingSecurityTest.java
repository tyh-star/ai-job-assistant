package aijob.config;

import aijob.AiJobApplication;
import aijob.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:aijobauthrouting;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads"
        }
)
@AutoConfigureMockMvc
class PublicAuthRoutingSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void registerEmptyJson_is400Not401Or403() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Origin", "https://203.0.113.10.sslip.io"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void loginEmptyJson_is400Not401Or403() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Origin", "https://203.0.113.10.sslip.io"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void protectedResumeListWithoutJwt_is401Or403() throws Exception {
        int status = mockMvc.perform(get("/api/v1/resumes"))
                .andReturn()
                .getResponse()
                .getStatus();
        assertNotEquals(200, status);
        assertNotEquals(400, status);
        org.junit.jupiter.api.Assertions.assertTrue(status == 401 || status == 403, "status=" + status);
    }

    @Test
    void corsPreflightFromPublicHttps_isAllowed() throws Exception {
        mockMvc.perform(options("/api/v1/auth/register")
                        .header("Origin", "https://203.0.113.10.sslip.io")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk());
    }

    @Test
    void corsConfiguration_allowsPublicHttpsOriginWithoutWideningPermitAll() {
        SecurityConfig config = new SecurityConfig(mock(JwtAuthenticationFilter.class));
        CorsConfigurationSource source = config.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        CorsConfiguration cors = source.getCorsConfiguration(request);
        assertNotNull(cors);
        assertEquals(
                "https://203.0.113.10.sslip.io",
                cors.checkOrigin("https://203.0.113.10.sslip.io")
        );
        assertEquals("http://localhost:5173", cors.checkOrigin("http://localhost:5173"));
    }
}
