package aijob.collector;

import aijob.AiJobApplication;
import aijob.service.impl.AdzunaJobSearchProvider;
import aijob.service.impl.JSearchJobSearchProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collector container is not given the production JWT signing key, the OpenAI key
 * or a job-search key. This boots the whole application under the collector profile
 * WITHOUT supplying any of them, and checks that what the context resolves is the
 * profile's own inert placeholder rather than anything that could authenticate.
 *
 * <p>Note the honest limit of this guarantee, which is also stated in
 * application-collector.yml: the beans that wanted those values still exist here. What
 * has changed is that a compromised collector has no production secret to leak, not
 * that the components were removed.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.web-application-type=none",
                "spring.datasource.url=jdbc:h2:mem:collectorsecrets;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "aijob.storage.root=target/aijob-test-uploads"
                // Deliberately absent: jwt.secret, openai.api-key, JSEARCH_API_KEY.
        }
)
@ActiveProfiles("collector")
@Sql(scripts = "classpath:schema/job-catalog-h2.sql")
class CollectorSecretIsolationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private Environment environment;

    @Test
    void theCollectorBootsWithoutAnyProductionSecret() {
        assertNotNull(context.getBean(JobCollectorScheduler.class));
        assertNotNull(context.getBean(JobIngestService.class));
        assertNotNull(context.getBean(JobIngestTransactions.class));
    }

    @Test
    void theJwtSecretIsTheCollectorPlaceholderAndNotASigningKey() {
        String secret = environment.getProperty("jwt.secret");
        assertNotNull(secret);
        // The profile's value wins over anything the environment supplies, so removing
        // AIJOB_JWT_SECRET from the collector service cannot break the container -- and
        // adding it back would not give the collector a usable key either.
        assertTrue(secret.startsWith("collector-"),
                "the collector must not resolve a real JWT signing key, got a different value");
    }

    @Test
    void theOpenAiKeyIsTheCollectorPlaceholder() {
        String key = environment.getProperty("openai.api-key");
        assertNotNull(key);
        assertTrue(key.startsWith("collector-"),
                "the collector must not resolve a real OpenAI key");
    }

    @Test
    void noOutboundJobSearchProviderExistsSoNoJobSearchKeyIsNeeded() {
        assertTrue(context.getBeansOfType(JSearchJobSearchProvider.class).isEmpty());
        assertTrue(context.getBeansOfType(AdzunaJobSearchProvider.class).isEmpty());
        assertFalse(environment.containsProperty("JSEARCH_API_KEY"));
    }

    @Test
    void collectionStillStartsSwitchedOff() {
        assertFalse(context.getBean(JobCollectorProperties.class).isEnabled());
    }
}
