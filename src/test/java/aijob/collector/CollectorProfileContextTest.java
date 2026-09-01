package aijob.collector;

import aijob.AiJobApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collector container runs the same jar under the {@code collector} profile with
 * no servlet stack. This proves that combination actually boots, and that collection
 * stays off until an operator enables it.
 */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.web-application-type=none",
                "spring.datasource.url=jdbc:h2:mem:collectorprofile;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads",
                "JOB_SEARCH_PRIMARY_PROVIDER=LOCAL_CATALOG"
        }
)
@ActiveProfiles("collector")
@Sql(scripts = "classpath:schema/job-catalog-h2.sql")
class CollectorProfileContextTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JobCollectorProperties properties;

    @Autowired
    private JobIngestService jobIngestService;

    @Test
    void theCollectorContainerBootsWithoutAServletStack() {
        assertNotNull(context.getBean(JobCollectorScheduler.class));
        assertNotNull(context.getBean(JobCollectorRunner.class));
        assertNotNull(context.getBean(GreenhouseBoardAdapter.class));
        assertNotNull(context.getBean(LeverBoardAdapter.class));
        // No servlet context is created, so nothing here can serve HTTP even though
        // the shared jar still carries the controller classes.
        assertFalse(context instanceof org.springframework.web.context.WebApplicationContext);
    }

    @Test
    void collectionIsOffUntilAnOperatorTurnsItOn() {
        assertFalse(properties.isEnabled());
    }

    @Test
    void aTickWithNoConfiguredSourcesDoesNothingAndDoesNotFail() {
        assertEquals(0, jobIngestService.collectDueSources().size());
        jobIngestService.housekeeping();
    }

    @Test
    void adaptersCoverBothPhaseOneProviders() {
        assertEquals(2, context.getBeansOfType(JobBoardAdapter.class).size());
        assertTrue(context.getBeansOfType(JobBoardAdapter.class).values().stream()
                .anyMatch(adapter -> adapter.providerType().name().equals("GREENHOUSE")));
        assertTrue(context.getBeansOfType(JobBoardAdapter.class).values().stream()
                .anyMatch(adapter -> adapter.providerType().name().equals("LEVER")));
    }
}
