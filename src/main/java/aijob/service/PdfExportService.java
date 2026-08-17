package aijob.service;

import java.nio.file.Path;

public interface PdfExportService {

    Path exportPdf(Path docxPath);
}
