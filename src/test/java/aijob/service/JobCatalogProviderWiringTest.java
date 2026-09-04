package aijob.service;

import aijob.AiJobApplication;
import aijob.service.impl.FreeHireJobCatalogService;
import aijob.service.impl.JobCatalogServiceImpl;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Which {@code JobCatalogService} bean actually answers {@code /api/v1/job-catalog/**}
 * -- the requirement this whole switch exists to satisfy, checked directly rather than
 * inferred from the config file. Two nested contexts, since the provider is chosen once
 * at boot and Spring cannot rewire it mid-context.
 */
class JobCatalogProviderWiringTest {

    /** No {@code aijob.job-catalog.provider} property at all: the real production default. */
    @Nested
    @SpringBootTest(
            classes = AiJobApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.MOCK,
            properties = {
                    "spring.datasource.url=jdbc:h2:mem:catalogwiringdefault;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=test-only-h2-password",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                    "openai.api-key=test-openai-key-not-real",
                    "aijob.storage.root=target/aijob-test-uploads"
                    // Deliberately no aijob.job-catalog.provider entry here.
            }
    )
    class DefaultProvider {

        @Autowired
        private JobCatalogService jobCatalogService;

        @Test
        void defaultsToFreeHireWithNoConfigurationAtAll() {
            assertInstanceOf(FreeHireJobCatalogService.class, jobCatalogService,
                    "search must go to FreeHire by default, not silently stay on the local catalog");
        }
    }

    /** Explicit opt-in to the local catalog. */
    @Nested
    @SpringBootTest(
            classes = AiJobApplication.class,
            webEnvironment = SpringBootTest.WebEnvironment.MOCK,
            properties = {
                    "spring.datasource.url=jdbc:h2:mem:catalogwiringlocal;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=test-only-h2-password",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                    "openai.api-key=test-openai-key-not-real",
                    "aijob.storage.root=target/aijob-test-uploads",
                    "aijob.job-catalog.provider=local_catalog"
            }
    )
    class ExplicitLocalCatalog {

        @Autowired
        private JobCatalogService jobCatalogService;

        @Test
        void switchingBackToLocalCatalogIsAnExplicitChoiceNotAFallback() {
            assertInstanceOf(JobCatalogServiceImpl.class, jobCatalogService,
                    "local_catalog must be reachable by an explicit, documented property value");
        }
    }
}
