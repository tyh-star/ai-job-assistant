package aijob.infra;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The isolation properties of the MySQL test container, checked rather than asserted in
 * prose.
 *
 * <p>This container exists to be truncated and rebuilt by a test. The things that keep
 * that from being dangerous — a loopback-only port, a fresh volume, no host mount — are
 * one careless edit away from not being true, and the edit would look harmless.
 *
 * <p>Static checks on the file. Nothing here starts a container or proves the stack runs.
 */
class MySqlTestComposeConfigTest {

    private static final Path COMPOSE = Path.of("infra/mysql-test/docker-compose.mysql-test.yml");

    private static Map<String, Object> service;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void loadComposeFile() throws IOException {
        assertTrue(Files.exists(COMPOSE), "missing " + COMPOSE.toAbsolutePath());
        try (InputStream in = Files.newInputStream(COMPOSE)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> services = (Map<String, Object>) root.get("services");
            assertNotNull(services, "the compose file declares no services");
            service = (Map<String, Object>) services.get("mysql-test");
            assertNotNull(service, "no 'mysql-test' service");
        }
    }

    @Test
    void itIsMysqlEightPointFour() {
        // The suite it serves asserts 8.4 behaviour. A different tag would make every
        // result in it a statement about the wrong server.
        assertEquals("mysql:8.4", service.get("image"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void thePortIsBoundToLoopbackOnly() {
        List<String> ports = (List<String>) service.get("ports");
        assertNotNull(ports);
        assertEquals(1, ports.size(), "one published port is enough");
        String published = ports.get(0);
        assertTrue(published.startsWith("127.0.0.1:"),
                "a throwaway database with a guessable name must not be published on all "
                        + "interfaces, got: " + published);
        assertFalse(published.contains("3306:"),
                "3306 belongs to the machine's existing MySQL; this must not claim it");
    }

    @Test
    @SuppressWarnings("unchecked")
    void itMountsNoHostDatabaseDirectory() {
        List<String> volumes = (List<String>) service.get("volumes");
        assertNotNull(volumes);
        for (String volume : volumes) {
            String source = volume.split(":")[0];
            boolean namedVolume = !source.startsWith(".") && !source.startsWith("/")
                    && !source.contains("\\") && !source.matches("^[A-Za-z]:.*");
            boolean initScripts = volume.contains("/docker-entrypoint-initdb.d");
            assertTrue(namedVolume || initScripts,
                    "only a named volume and the read-only init scripts may be mounted; "
                            + "a host path here could point at another database's files: " + volume);
        }
        assertTrue(volumes.stream().anyMatch(v -> v.startsWith("mysqltest_data:")),
                "the data volume must be the project's own, created empty");
        assertTrue(volumes.stream().anyMatch(v -> v.endsWith(":ro")),
                "the init scripts must be mounted read-only");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDataVolumeIsDeclaredAndIsNotAProductionOne() throws IOException {
        try (InputStream in = Files.newInputStream(COMPOSE)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> volumes = (Map<String, Object>) root.get("volumes");
            assertNotNull(volumes, "the data volume must be declared, so Compose creates it empty");
            assertTrue(volumes.containsKey("mysqltest_data"));
            // The production names, which must never appear here.
            assertFalse(volumes.containsKey("mysql_data"));
            assertFalse(volumes.containsKey("uploads_data"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void noPasswordIsWrittenInTheFile() {
        Map<String, Object> environment = (Map<String, Object>) service.get("environment");
        assertNotNull(environment);
        for (String name : List.of("MYSQL_ROOT_PASSWORD", "MYSQL_PASSWORD")) {
            String value = String.valueOf(environment.get(name));
            assertTrue(value.startsWith("${"),
                    name + " must be interpolated from the git-ignored env file, not written here");
        }
        assertEquals("aijob_import_test", environment.get("MYSQL_DATABASE"));
        assertEquals("aijob_test", environment.get("MYSQL_USER"));
    }

    @Test
    void theCollationMatchesProduction() {
        // One of the questions this container exists to answer is whether
        // utf8mb4_unicode_ci folds two external ids differing only in case. A server on a
        // different collation would answer a different question.
        String text = readCompose();
        assertTrue(text.contains("--collation-server=utf8mb4_unicode_ci"));
        assertTrue(text.contains("--character-set-server=utf8mb4"));
    }

    @Test
    void theCredentialsFileIsGitIgnored() throws IOException {
        String gitignore = Files.readString(Path.of(".gitignore"));
        assertTrue(gitignore.contains("infra/mysql-test/.env.mysql-test"),
                "the generated credentials file must be ignored before it can exist");
    }

    private static String readCompose() {
        try {
            return Files.readString(COMPOSE);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
