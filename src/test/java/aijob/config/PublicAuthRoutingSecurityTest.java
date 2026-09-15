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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.ForwardedHeaderFilter;

import jakarta.servlet.http.HttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
                "aijob.storage.root=target/aijob-test-uploads",
                "aijob.cors.allowed-origins=https://example.sslip.io,http://localhost:5173,http://127.0.0.1:5173",
                "server.forward-headers-strategy=framework"
        }
)
@AutoConfigureMockMvc
class PublicAuthRoutingSecurityTest {

    private static final String PRODUCTION_ORIGIN = "https://example.sslip.io";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void registerEmptyJson_fromConfiguredOrigin_is400Not401Or403() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Origin", PRODUCTION_ORIGIN))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertEquals("/api/v1/auth/register", result.getRequest().getRequestURI());
    }

    @Test
    void loginEmptyJson_fromConfiguredOrigin_is400Not401Or403() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Origin", PRODUCTION_ORIGIN))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertEquals("/api/v1/auth/login", result.getRequest().getRequestURI());
    }

    @Test
    void registerAndLogin_fromDefaultLocalViteOrigins_are400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Origin", "http://localhost:5173"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Origin", "http://127.0.0.1:5173"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registerFromUnlistedOrigin_is403CorsRejection() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
    }

    @Test
    void registerWithoutOrigin_reachesControllerAs400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.path").value("/api/v1/auth/register"));
    }

    @Test
    void forwardedHttpsHeaders_areRecognizedBehindProxy() throws Exception {
        boolean forwardedFilterRegistered = applicationContext.getBeansOfType(FilterRegistrationBean.class)
                .values()
                .stream()
                .anyMatch(bean -> bean.getFilter() instanceof ForwardedHeaderFilter);
        assertTrue(forwardedFilterRegistered, "ForwardedHeaderFilter must be registered");

        ForwardedHeaderFilter filter = new ForwardedHeaderFilter();
        MockHttpServletRequest forwarded = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        forwarded.setScheme("http");
        forwarded.setServerName("backend");
        forwarded.setServerPort(8080);
        forwarded.addHeader("Origin", PRODUCTION_ORIGIN);
        forwarded.addHeader("Host", "example.sslip.io");
        forwarded.addHeader("X-Forwarded-Proto", "https");
        forwarded.addHeader("X-Forwarded-Host", "example.sslip.io");
        filter.doFilter(forwarded, new org.springframework.mock.web.MockHttpServletResponse(), (req, res) -> {
            HttpServletRequest http = (HttpServletRequest) req;
            assertEquals("https", http.getScheme());
            assertEquals("example.sslip.io", http.getServerName());
            assertTrue(http.isSecure());
        });

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header("Origin", PRODUCTION_ORIGIN)
                        .header("Host", "example.sslip.io")
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Host", "example.sslip.io"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertEquals("/api/v1/auth/register", result.getRequest().getRequestURI());
    }

    @Test
    void protectedResumeListWithoutJwt_is401() throws Exception {
        int status = mockMvc.perform(get("/api/v1/resumes"))
                .andReturn()
                .getResponse()
                .getStatus();
        assertNotEquals(200, status);
        assertNotEquals(400, status);
        assertEquals(401, status, "missing credentials must be 401, not 403");
    }

    @Test
    void protectedPersonalJobsAndStatsWithoutJwt_are401() throws Exception {
        assertAnonymousDenied("/api/v1/jobs");
        assertAnonymousDenied("/api/v1/jobs/application-stats");
        assertAnonymousDenied("/api/v1/resume-builder/drafts");
        assertAnonymousDenied("/api/v1/job-search-history");
    }

    @Test
    void protectedCatalogWriteWithoutJwt_is401() throws Exception {
        int saveStatus = mockMvc.perform(post("/api/v1/job-catalog/501/save"))
                .andReturn()
                .getResponse()
                .getStatus();
        assertEquals(401, saveStatus, "save status");

        int appliedStatus = mockMvc.perform(post("/api/v1/job-catalog/501/applied"))
                .andReturn()
                .getResponse()
                .getStatus();
        assertEquals(401, appliedStatus, "applied status");
    }

    @Test
    void publicCatalogSearchWithoutJwt_isNot401Or403() throws Exception {
        int status = mockMvc.perform(get("/api/v1/job-catalog/search"))
                .andReturn()
                .getResponse()
                .getStatus();
        assertNotEquals(401, status);
        assertNotEquals(403, status);
    }

    private void assertAnonymousDenied(String path) throws Exception {
        int status = mockMvc.perform(get(path))
                .andReturn()
                .getResponse()
                .getStatus();
        assertNotEquals(200, status, path);
        assertEquals(401, status, path);
    }

    @Test
    void corsConfiguration_allowsConfiguredOriginsOnly() {
        SecurityConfig config = new SecurityConfig(
                mock(JwtAuthenticationFilter.class),
                "https://example.sslip.io, http://localhost:5173,http://127.0.0.1:5173",
                new com.fasterxml.jackson.databind.ObjectMapper()
        );
        CorsConfigurationSource source = config.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/register");
        CorsConfiguration cors = source.getCorsConfiguration(request);
        assertNotNull(cors);
        assertEquals(PRODUCTION_ORIGIN, cors.checkOrigin(PRODUCTION_ORIGIN));
        assertEquals("http://localhost:5173", cors.checkOrigin("http://localhost:5173"));
        assertEquals("http://127.0.0.1:5173", cors.checkOrigin("http://127.0.0.1:5173"));
        assertNull(cors.checkOrigin("https://evil.example"));
        assertFalse(Boolean.TRUE.equals(cors.getAllowCredentials()));
        assertTrue(cors.getAllowedOriginPatterns() == null || cors.getAllowedOriginPatterns().isEmpty());
    }
}
