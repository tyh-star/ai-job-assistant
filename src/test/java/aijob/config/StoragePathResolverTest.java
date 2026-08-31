package aijob.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoragePathResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void forRoot_resolvesResumeAndTailoredDirectoriesUnderTempRoot() {
        Path root = tempDir.resolve("uploads");
        StoragePathResolver resolver = StoragePathResolver.forRoot(root);

        assertEquals(root.toAbsolutePath().normalize(), resolver.root());
        assertEquals(
                root.resolve("resumes").resolve("2").toAbsolutePath().normalize(),
                resolver.resumeDirectory(2L)
        );
        assertEquals(
                root.resolve("tailored").resolve("1").resolve("10").toAbsolutePath().normalize(),
                resolver.tailoredJobDirectory(1L, 10L)
        );
        assertTrue(resolver.resumeDirectory(2L).startsWith(tempDir.toAbsolutePath().normalize()));
    }

    @Test
    void constructor_blankRootDefaultsToUploadsWithoutWriting() {
        StorageProperties properties = new StorageProperties();
        properties.setRoot("  ");
        StoragePathResolver resolver = new StoragePathResolver(properties);

        assertEquals(Path.of("uploads").toAbsolutePath().normalize(), resolver.root());
    }

    @Test
    void forRoot_rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> StoragePathResolver.forRoot((Path) null));
    }
}
