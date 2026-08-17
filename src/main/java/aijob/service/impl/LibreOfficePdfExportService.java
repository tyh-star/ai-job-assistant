package aijob.service.impl;

import aijob.exception.PdfExportException;
import aijob.service.PdfExportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class LibreOfficePdfExportService implements PdfExportService {

    private static final Logger log = LoggerFactory.getLogger(LibreOfficePdfExportService.class);
    private static final String GENERIC_FAILURE = "PDF export is unavailable";
    private static final List<String> DEFAULT_WINDOWS_PATHS = List.of(
            "C:\\Program Files\\LibreOffice\\program\\soffice.exe",
            "C:\\Program Files (x86)\\LibreOffice\\program\\soffice.exe",
            "C:\\Program Files\\LibreOffice\\program\\soffice.com"
    );

    private final String sofficePath;
    private final int timeoutSeconds;

    public LibreOfficePdfExportService(String configuredPath, int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        this.sofficePath = resolveSoffice(configuredPath);
        if (this.sofficePath == null) {
            log.warn("LibreOffice soffice not found; tailored PDF export is disabled");
        } else {
            log.info("LibreOffice PDF export is enabled");
        }
    }

    @Override
    public Path exportPdf(Path docxPath) {
        if (sofficePath == null) {
            throw new PdfExportException(GENERIC_FAILURE);
        }
        if (docxPath == null || !Files.isRegularFile(docxPath)) {
            throw new PdfExportException(GENERIC_FAILURE);
        }

        String fileName = docxPath.getFileName().toString();
        if (!fileName.toLowerCase(Locale.ROOT).endsWith(".docx")) {
            throw new PdfExportException(GENERIC_FAILURE);
        }

        Path outputDir = docxPath.toAbsolutePath().normalize().getParent();
        if (outputDir == null) {
            throw new PdfExportException(GENERIC_FAILURE);
        }

        Path expectedPdf = outputDir.resolve(fileName.substring(0, fileName.length() - 5) + ".pdf");
        Path profileDir = null;
        try {
            Files.createDirectories(outputDir);
            profileDir = Files.createTempDirectory("lo-pdf-");
            List<String> command = new ArrayList<>();
            command.add(sofficePath);
            command.add("--headless");
            command.add("--nologo");
            command.add("--nofirststartwizard");
            command.add("--norestore");
            command.add("-env:UserInstallation=" + profileDir.toUri());
            command.add("--convert-to");
            command.add("pdf");
            command.add("--outdir");
            command.add(outputDir.toString());
            command.add(docxPath.toAbsolutePath().normalize().toString());

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new PdfExportException(GENERIC_FAILURE);
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(expectedPdf)) {
                throw new PdfExportException(GENERIC_FAILURE);
            }
            return expectedPdf;
        } catch (PdfExportException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new PdfExportException(GENERIC_FAILURE);
        } finally {
            deleteQuietly(profileDir);
        }
    }

    static String resolveSoffice(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path configured = Path.of(configuredPath.trim());
            if (Files.isExecutable(configured) || Files.isRegularFile(configured)) {
                return configured.toString();
            }
            return null;
        }

        for (String candidate : DEFAULT_WINDOWS_PATHS) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path.toString();
            }
        }

        String fromPath = findOnPath();
        if (fromPath != null) {
            return fromPath;
        }
        return null;
    }

    private static String findOnPath() {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        String[] names = new String[] {"soffice.exe", "soffice.com", "soffice"};
        for (String dir : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : names) {
                Path candidate = Path.of(dir.trim(), name);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toString();
                }
            }
        }
        return null;
    }

    private static void deleteQuietly(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                            // best-effort cleanup of the temporary LibreOffice profile
                        }
                    });
        } catch (IOException ignored) {
            // best-effort cleanup of the temporary LibreOffice profile
        }
    }
}
