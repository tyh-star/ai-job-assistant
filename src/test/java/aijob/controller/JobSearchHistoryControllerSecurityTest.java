package aijob.controller;

import aijob.AiJobApplication;
import aijob.security.JwtUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:jobsearchhistory;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-search-history-test-uploads"
        }
)
@AutoConfigureMockMvc
@Sql(scripts = "classpath:schema/job-catalog-h2.sql")
class JobSearchHistoryControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtUtil jwtUtil;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void emptyCriteriaAreNotStoredAndRepeatsIncrementCount() throws Exception {
        String alice = bearerFor("alice-history@example.com");

        mockMvc.perform(post("/api/v1/job-search-history")
                        .header("Authorization", alice)
                        .contentType("application/json")
                        .content("{\"keywords\":\"\",\"sort\":\"RELEVANCE\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        String body = "{\"keywords\":\"java backend\",\"city\":\"Toronto\",\"cityCountry\":\"CA\",\"sort\":\"RELEVANCE\"}";
        mockMvc.perform(post("/api/v1/job-search-history")
                        .header("Authorization", alice)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/job-search-history")
                        .header("Authorization", alice)
                        .contentType("application/json")
                        .content("{\"keywords\":\"  Java   Backend \",\"city\":\"Toronto\",\"cityCountry\":\"ca\"}"))
                .andExpect(status().isNoContent());

        String list = mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].searchCount").value(2))
                .andExpect(jsonPath("$[0].city").value("toronto"))
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(list);
        assertEquals("java backend", node.get(0).get("keywords").asText());
    }

    @Test
    void torontoAndTorontoShareOneRowAndPageJsonIsIgnored() throws Exception {
        String alice = bearerFor("alice-city-case@example.com");
        mockMvc.perform(post("/api/v1/job-search-history")
                        .header("Authorization", alice)
                        .contentType("application/json")
                        .content("{\"keywords\":\"java\",\"city\":\"Toronto\",\"cityCountry\":\"CA\",\"page\":1}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/job-search-history")
                        .header("Authorization", alice)
                        .contentType("application/json")
                        .content("{\"keywords\":\"java\",\"city\":\"toronto\",\"cityCountry\":\"ca\",\"page\":9}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].searchCount").value(2))
                .andExpect(jsonPath("$[0].city").value("toronto"));
    }

    @Test
    void recordDoesNotLogTheRawKeywords() throws Exception {
        ch.qos.logback.classic.Logger root =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
                        ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            String alice = bearerFor("alice-nolog@example.com");
            String secret = "unique-keyword-must-not-appear-in-logs-zx9q";
            mockMvc.perform(post("/api/v1/job-search-history")
                            .header("Authorization", alice)
                            .contentType("application/json")
                            .content("{\"keywords\":\"" + secret + "\"}"))
                    .andExpect(status().isNoContent());
            boolean leaked = appender.list.stream().anyMatch(event -> {
                String message = event.getFormattedMessage();
                return message != null && message.contains(secret);
            });
            org.junit.jupiter.api.Assertions.assertFalse(leaked,
                    "search keywords must never be written to application logs");
        } finally {
            root.detachAppender(appender);
        }
    }

    @Test
    void capsAtTwentyAndCrossUserDeleteIs404() throws Exception {
        String alice = bearerFor("alice-cap@example.com");
        String bob = bearerFor("bob-cap@example.com");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/job-search-history")
                            .header("Authorization", bob)
                            .contentType("application/json")
                            .content("{\"keywords\":\"bob-keep-" + i + "\"}"))
                    .andExpect(status().isNoContent());
        }

        for (int i = 0; i < 21; i++) {
            mockMvc.perform(post("/api/v1/job-search-history")
                            .header("Authorization", alice)
                            .contentType("application/json")
                            .content("{\"keywords\":\"term-" + i + "\"}"))
                    .andExpect(status().isNoContent());
        }
        mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(20));
        String aliceAfterCap = mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", alice))
                .andReturn().getResponse().getContentAsString();
        JsonNode aliceNodes = objectMapper.readTree(aliceAfterCap);
        assertEquals(20, aliceNodes.size());
        assertEquals("term-1", aliceNodes.get(19).get("keywords").asText());
        for (JsonNode node : aliceNodes) {
            assertNotEquals("term-0", node.get("keywords").asText());
        }

        mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", bob))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));

        mockMvc.perform(post("/api/v1/job-search-history")
                        .header("Authorization", alice)
                        .contentType("application/json")
                        .content("{\"keywords\":\"keep-me\"}"))
                .andExpect(status().isNoContent());
        String aliceList = mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", alice))
                .andExpect(jsonPath("$.length()").value(20))
                .andReturn().getResponse().getContentAsString();
        long aliceId = objectMapper.readTree(aliceList).get(0).get("id").asLong();

        mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", bob))
                .andExpect(jsonPath("$.length()").value(3));
        String bobList = mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", bob))
                .andReturn().getResponse().getContentAsString();
        long bobId = objectMapper.readTree(bobList).get(0).get("id").asLong();

        mockMvc.perform(delete("/api/v1/job-search-history/" + aliceId).header("Authorization", bob))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/job-search-history/" + bobId).header("Authorization", alice))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", alice))
                .andExpect(jsonPath("$.length()").value(20));
        mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", bob))
                .andExpect(jsonPath("$.length()").value(3));

        mockMvc.perform(delete("/api/v1/job-search-history/" + aliceId).header("Authorization", alice))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", alice))
                .andExpect(jsonPath("$.length()").value(19));

        mockMvc.perform(delete("/api/v1/job-search-history").header("Authorization", alice))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", alice))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/v1/job-search-history").header("Authorization", bob))
                .andExpect(jsonPath("$.length()").value(3));
    }

    private String bearerFor(String email) {
        jdbcTemplate.update(
                "INSERT INTO users (email, password, name, role) VALUES (?, ?, ?, 'USER')",
                email, passwordEncoder.encode("Password123"), "History Test User");
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        return "Bearer " + jwtUtil.generateToken(new JwtUtil.UserTokenInfo(userId, email, "USER", 0));
    }
}
