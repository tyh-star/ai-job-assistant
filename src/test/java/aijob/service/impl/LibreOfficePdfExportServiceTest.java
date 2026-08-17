package aijob.service.impl;

import aijob.exception.PdfExportException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LibreOfficePdfExportServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void exportPdf_missingSoffice_throwsGenericExceptionWithoutPath() throws Exception {
        Path docx = tempDir.resolve("11111111-2222-3333-4444-555555555555.docx");
        Files.writeString(docx, "docx");
        LibreOfficePdfExportService service =
                new LibreOfficePdfExportService("C:/missing/soffice.exe", 5);

        PdfExportException thrown = assertThrows(
                PdfExportException.class,
                () -> service.exportPdf(docx)
        );

        assertEquals("PDF export is unavailable", thrown.getMessage());
        assertFalse(thrown.getMessage().contains("C:"));
        assertFalse(thrown.getMessage().contains("soffice"));
    }

    @Test
    void resolveSoffice_missingConfiguredBinary_isNull() {
        assertEquals(null, LibreOfficePdfExportService.resolveSoffice("C:/missing/soffice.exe"));
    }
}
