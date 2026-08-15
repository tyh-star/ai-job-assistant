package aijob.util;

import aijob.exception.ResumeTextExtractionException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class ResumeTextExtractor {

    public String extract(Path filePath, String fileType) {

        if (fileType == null || fileType.isBlank()) {
            throw new ResumeTextExtractionException("Unsupported resume file type");
        }

        String type = fileType.toLowerCase(Locale.ROOT);

        try {
            if ("pdf".equals(type) || "application/pdf".equals(type)) {
                return extractPdf(filePath);
            }

            if ("docx".equals(type)
                    || type.contains("wordprocessingml")) {
                return extractDocx(filePath);
            }

            throw new ResumeTextExtractionException(
                    "Unsupported resume file type"
            );
        } catch (ResumeTextExtractionException e) {
            throw e;
        } catch (Exception e) {
            throw new ResumeTextExtractionException(
                    "Failed to extract resume text",
                    e
            );
        }
    }

    private String extractPdf(Path filePath) throws IOException {

        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            return text == null ? "" : text.trim();
        }
    }

    private String extractDocx(Path filePath) throws IOException {

        try (InputStream inputStream = Files.newInputStream(filePath);
             XWPFDocument document = new XWPFDocument(inputStream)) {

            StringBuilder text = new StringBuilder();

            for (XWPFParagraph paragraph : document.getParagraphs()) {
                appendLine(text, paragraph.getText());
            }

            for (XWPFTable table : document.getTables()) {
                extractTable(table, text);
            }

            return text.toString().trim();
        }
    }

    private void extractTable(XWPFTable table, StringBuilder text) {

        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();

            for (XWPFTableCell cell : row.getTableCells()) {
                String cellText = cell.getText();
                if (cellText != null && !cellText.isBlank()) {
                    cells.add(cellText.trim());
                }

                for (XWPFTable nestedTable : cell.getTables()) {
                    extractTable(nestedTable, text);
                }
            }

            if (!cells.isEmpty()) {
                appendLine(text, String.join("\t", cells));
            }
        }
    }

    private void appendLine(StringBuilder text, String line) {

        if (line == null || line.isBlank()) {
            return;
        }

        if (!text.isEmpty()) {
            text.append(System.lineSeparator());
        }

        text.append(line.trim());
    }
}
