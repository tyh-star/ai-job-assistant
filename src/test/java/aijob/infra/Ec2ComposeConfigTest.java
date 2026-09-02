package aijob.infra;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties of the production Compose file that are easy to undo by accident.
 *
 * <p>These are static checks on the file, not a deployment test: nothing here proves
 * the stack runs. What they do catch is a secret being handed back to the collector,
 * or the image tags that make a rollback possible being dropped.
 */
class Ec2ComposeConfigTest {

    private static final Path COMPOSE = Path.of("infra/aws/docker-compose.ec2.yml");

    private static Map<String, Object> services;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadComposeFile() throws IOException {
        assertTrue(Files.exists(COMPOSE), "missing " + COMPOSE.toAbsolutePath());
        try (InputStream in = Files.newInputStream(COMPOSE)) {
            Map<String, Object> root = new Yaml().load(in);
            services = (Map<String, Object>) root.get("services");
        }
        assertNotNull(services, "the Compose file declares no services");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) {
        Map<String, Object> service = (Map<String, Object>) services.get(name);
        assertNotNull(service, "no '" + name + "' service in " + COMPOSE);
        return service;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> environmentOf(String serviceName) {
        Object environment = service(serviceName).get("environment");
        return environment == null ? Map.of() : (Map<String, Object>) environment;
    }

    @Test
    void theCollectorIsNotGivenAnyProductionSecret() {
        Set<String> forbidden = Set.of(
                "AIJOB_JWT_SECRET", "OPENAI_API_KEY", "JSEARCH_API_KEY", "MYSQL_ROOT_PASSWORD");
        for (String name : forbidden) {
            assertFalse(environmentOf("collector").containsKey(name),
                    name + " must not be passed to the collector: it never signs a token, "
                            + "calls a model, or calls a job-search API");
        }
    }

    @Test
    void theCollectorStillGetsWhatItActuallyNeeds() {
        Map<String, Object> environment = environmentOf("collector");
        assertTrue(environment.containsKey("AIJOB_DB_URL"));
        assertTrue(environment.containsKey("AIJOB_DB_USERNAME"));
        assertTrue(environment.containsKey("AIJOB_DB_PASSWORD"));
        assertTrue(environment.containsKey("JOB_COLLECTOR_ENABLED"));
    }

    @Test
    void collectionIsOffByDefault() {
        assertEquals("${JOB_COLLECTOR_ENABLED:-false}",
                environmentOf("collector").get("JOB_COLLECTOR_ENABLED"),
                "a fresh deployment must not start collecting on its own");
    }

    @Test
    void theCollectorPublishesNoPort() {
        assertFalse(service("collector").containsKey("ports"),
                "the collector has no HTTP surface and must not be reachable off the network");
    }

    @Test
    void everyBuiltServiceIsTaggedSoAReleaseCanBeRolledBack() {
        // Without an explicit tag a rebuild leaves the previous image untagged, and
        // rolling back means rebuilding old source rather than starting what ran.
        assertEquals("${AIJOB_BACKEND_IMAGE:-aijob-backend:current}", service("backend").get("image"));
        assertEquals("${AIJOB_WEB_IMAGE:-aijob-web:current}", service("web").get("image"));
        assertEquals("${AIJOB_COLLECTOR_IMAGE:-aijob-collector:current}", service("collector").get("image"));
    }

    @Test
    void neitherMysqlNorTheBackendPortIsPublished() {
        assertFalse(service("mysql").containsKey("ports"), "3306 must stay internal");
        assertFalse(service("backend").containsKey("ports"), "8080 must stay internal");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theWebTierPublishesOnlyHttpAndHttps() {
        assertEquals(java.util.List.of("80:80", "443:443", "443:443/udp"),
                service("web").get("ports"));
    }

    @Test
    void productionVolumesAreDeclaredAndNamed() {
        // A rename here would orphan the live data on the next deploy.
        assertTrue(Files.exists(COMPOSE));
        String text = readCompose();
        for (String volume : new String[] {"mysql_data", "uploads_data", "caddy_data", "caddy_config"}) {
            assertTrue(text.contains(volume + ":"), "missing volume " + volume);
        }
    }

    private static String readCompose() {
        try {
            return Files.readString(COMPOSE);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
