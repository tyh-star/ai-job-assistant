package aijob.config;

import aijob.service.PdfExportService;
import aijob.service.impl.LibreOfficePdfExportService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PdfExportConfig {

    @Bean
    public PdfExportService pdfExportService(
            @Value("${pdf.export.soffice-path:}") String sofficePath,
            @Value("${pdf.export.timeout-seconds:60}") int timeoutSeconds) {
        return new LibreOfficePdfExportService(sofficePath, timeoutSeconds);
    }
}
