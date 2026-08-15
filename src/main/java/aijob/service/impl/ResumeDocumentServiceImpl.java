package aijob.service.impl;

import aijob.dto.ResumeDocumentNode;
import aijob.exception.InvalidResumeFileException;
import aijob.service.ResumeDocumentService;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class ResumeDocumentServiceImpl implements ResumeDocumentService {

    private static final String TYPE_PARAGRAPH = "PARAGRAPH";
    private static final String TYPE_TABLE_CELL_PARAGRAPH = "TABLE_CELL_PARAGRAPH";

    @Override
    public List<ResumeDocumentNode> parseDocx(Path path) {

        try (InputStream inputStream = Files.newInputStream(path);
             XWPFDocument document = new XWPFDocument(inputStream)) {

            List<ResumeDocumentNode> nodes = new ArrayList<>();
            int paragraphIndex = 0;
            int tableIndex = 0;

            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    addNodeIfPresent(
                            paragraph,
                            "p-" + paragraphIndex,
                            TYPE_PARAGRAPH,
                            nodes
                    );
                    paragraphIndex++;
                } else if (element instanceof XWPFTable table) {
                    parseTable(table, "t-" + tableIndex, nodes);
                    tableIndex++;
                }
            }

            return nodes;
        } catch (IOException e) {
            throw new InvalidResumeFileException("Failed to parse DOCX resume");
        }
    }

    /**
     * Resolves a stable nodeId back to the original XWPFParagraph.
     * Future rewrite must edit this paragraph's XWPFRun list, not paragraph.setText(...).
     */
    @Override
    public XWPFParagraph findParagraph(XWPFDocument document, String nodeId) {

        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId is required");
        }

        String[] parts = nodeId.split("-");
        if (parts.length < 2 || parts.length % 2 != 0) {
            throw new IllegalArgumentException("Invalid nodeId: " + nodeId);
        }

        if ("p".equals(parts[0]) && parts.length == 2) {
            return document.getParagraphs().get(parseIndex(parts[1], nodeId));
        }

        XWPFTable table = null;
        XWPFTableRow row = null;
        XWPFTableCell cell = null;

        for (int i = 0; i < parts.length; i += 2) {
            String kind = parts[i];
            int index = parseIndex(parts[i + 1], nodeId);

            switch (kind) {
                case "t" -> {
                    if (cell != null) {
                        table = cell.getTables().get(index);
                    } else {
                        table = document.getTables().get(index);
                    }
                    row = null;
                    cell = null;
                }
                case "r" -> row = table.getRows().get(index);
                case "c" -> cell = row.getTableCells().get(index);
                case "p" -> {
                    if (cell == null) {
                        throw new IllegalArgumentException("Invalid nodeId: " + nodeId);
                    }
                    return cell.getParagraphs().get(index);
                }
                default -> throw new IllegalArgumentException("Invalid nodeId: " + nodeId);
            }
        }

        throw new IllegalArgumentException("Invalid nodeId: " + nodeId);
    }

    private void parseTable(
            XWPFTable table,
            String tablePrefix,
            List<ResumeDocumentNode> nodes) {

        List<XWPFTableRow> rows = table.getRows();
        for (int r = 0; r < rows.size(); r++) {
            List<XWPFTableCell> cells = rows.get(r).getTableCells();
            for (int c = 0; c < cells.size(); c++) {
                XWPFTableCell cell = cells.get(c);
                String cellPrefix = tablePrefix + "-r-" + r + "-c-" + c;

                List<XWPFParagraph> paragraphs = cell.getParagraphs();
                for (int p = 0; p < paragraphs.size(); p++) {
                    addNodeIfPresent(
                            paragraphs.get(p),
                            cellPrefix + "-p-" + p,
                            TYPE_TABLE_CELL_PARAGRAPH,
                            nodes
                    );
                }

                List<XWPFTable> nestedTables = cell.getTables();
                for (int t = 0; t < nestedTables.size(); t++) {
                    parseTable(
                            nestedTables.get(t),
                            cellPrefix + "-t-" + t,
                            nodes
                    );
                }
            }
        }
    }

    private void addNodeIfPresent(
            XWPFParagraph paragraph,
            String nodeId,
            String type,
            List<ResumeDocumentNode> nodes) {

        String text = readParagraphText(paragraph);
        if (text.isEmpty()) {
            return;
        }

        nodes.add(new ResumeDocumentNode(nodeId, type, text));
    }

    /**
     * Concatenates all XWPFRun texts for reading only.
     * Runs are left untouched so later replacement can rewrite run-by-run.
     */
    private String readParagraphText(XWPFParagraph paragraph) {

        String text = paragraph.getText();
        if (text == null) {
            return "";
        }

        return text.strip();
    }

    private int parseIndex(String value, String nodeId) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid nodeId: " + nodeId);
        }
    }
}
