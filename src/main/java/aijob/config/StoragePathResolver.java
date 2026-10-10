package aijob.config;

import java.nio.file.Path;

public class StoragePathResolver {

    private final Path root;

    public StoragePathResolver(StorageProperties properties) {
        this(Path.of(normalizeRoot(properties == null ? null : properties.getRoot())));
    }

    private StoragePathResolver(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public static StoragePathResolver forRoot(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("Storage root is required");
        }
        return new StoragePathResolver(root);
    }

    public static StoragePathResolver forRoot(String root) {
        return forRoot(Path.of(normalizeRoot(root)));
    }

    public Path root() {
        return root;
    }

    public Path resumeDirectory(Long userId) {
        return root.resolve("resumes").resolve(String.valueOf(userId));
    }

    public Path tailoredJobDirectory(Long userId, Long jobId) {
        return root.resolve("tailored")
                .resolve(String.valueOf(userId))
                .resolve(String.valueOf(jobId));
    }

    /** Free Resume Rewrite outputs (no job involved). */
    public Path rewriteDirectory(Long userId) {
        return root.resolve("rewrites").resolve(String.valueOf(userId));
    }

    private static String normalizeRoot(String root) {
        if (root == null || root.isBlank()) {
            return "uploads";
        }
        return root.trim();
    }
}
