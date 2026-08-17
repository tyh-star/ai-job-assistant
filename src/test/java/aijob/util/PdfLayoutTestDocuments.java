package aijob.util;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Path;

final class PdfLayoutTestDocuments {

    private PdfLayoutTestDocuments() {
    }

    static void writeTwoColumnResume(Path path) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font plain = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                show(cs, bold, 22, 72, 740, "Jane Doe");
                show(cs, plain, 11, 72, 720, "jane.doe@example.com  +1 (555) 123-4567");
                show(cs, plain, 11, 72, 704, "https://linkedin.com/in/janedoe");
                show(cs, bold, 12, 50, 640, "SKILLS");
                show(cs, plain, 11, 50, 622, "Java, Spring Boot");
                show(cs, bold, 12, 330, 640, "EXPERIENCE");
                show(cs, plain, 11, 330, 622, "Acme Corp");
                show(cs, plain, 11, 330, 606, "- Built payment APIs");
            }
            document.save(path.toFile());
        }
    }

    static void writeWeirdHeadingResume(Path path) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font plain = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                show(cs, bold, 20, 72, 740, "Alex Rivera");
                show(cs, plain, 11, 72, 720, "alex.rivera@example.com");
                show(cs, bold, 13, 72, 680, "SUMMARY OF QUALIFICATIONS");
                show(cs, plain, 11, 72, 662, "Backend engineer with distributed systems experience.");
                show(cs, bold, 13, 72, 630, "CORE COMPETENCIES");
                show(cs, plain, 11, 72, 612, "Java, MySQL, Redis");
                show(cs, bold, 13, 72, 580, "RELEVANT PROJECTS");
                show(cs, plain, 11, 72, 562, "Job Tracker");
            }
            document.save(path.toFile());
        }
    }

    static void writeEmptyPage(Path path) throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.LETTER));
            document.save(path.toFile());
        }
    }

    static void writeCenteredSansResume(Path path) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font plain = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            float pageWidth = PDRectangle.LETTER.getWidth();
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                String name = "Alex Chen";
                float nameSize = 22f;
                float nameX = (pageWidth - stringWidth(bold, nameSize, name)) / 2f;
                show(cs, bold, 22, nameX, 740, name);
                String contact = "alex.chen@example.com  +1 (555) 010-2030";
                float contactX = (pageWidth - stringWidth(plain, 11, contact)) / 2f;
                show(cs, plain, 11, contactX, 718, contact);
                show(cs, bold, 12, 72, 660, "PROJECTS");
                show(cs, bold, 11, 72, 640, "Job Tracker Jan 2025 - Mar 2025");
                show(cs, plain, 11, 72, 622, "- Parsed job descriptions");
                show(cs, bold, 12, 72, 580, "SKILLS");
                show(cs, plain, 11, 72, 562, "Java, Python, SQL");
            }
            document.save(path.toFile());
        }
    }

    static void writeLeftTimesRuleResume(Path path) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            document.addPage(page);
            PDType1Font bold = new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD);
            PDType1Font plain = new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
            try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                show(cs, bold, 20, 72, 740, "Morgan Lee");
                show(cs, plain, 11, 72, 720, "morgan.lee@example.com  +1 (555) 111-2222");
                cs.setStrokingColor(0f, 0f, 0f);
                cs.setLineWidth(0.8f);
                cs.moveTo(72, 708);
                cs.lineTo(540, 708);
                cs.stroke();

                show(cs, bold, 13, 72, 670, "TECHNICAL SKILLS");
                cs.moveTo(72, 666);
                cs.lineTo(540, 666);
                cs.stroke();
                show(cs, plain, 11, 72, 648, "Programming Languages: Java, Python");
                show(cs, plain, 11, 72, 632, "Web Technologies: HTML, CSS");

                show(cs, bold, 13, 72, 590, "RELEVANT PROJECTS");
                cs.moveTo(72, 586);
                cs.lineTo(540, 586);
                cs.stroke();
                show(cs, bold, 11, 72, 568, "Prime Deals");
                show(cs, plain, 11, 420, 568, "Sept 2024 - Present");
                show(cs, plain, 11, 72, 550, "- Built a shopping website");

                show(cs, bold, 13, 72, 510, "EDUCATION");
                cs.moveTo(72, 506);
                cs.lineTo(540, 506);
                cs.stroke();
                show(cs, bold, 11, 72, 488, "York University");
                show(cs, plain, 11, 420, 488, "Sept 2023 - Present");
            }
            document.save(path.toFile());
        }
    }

    private static float stringWidth(PDType1Font font, float size, String text) throws IOException {
        return font.getStringWidth(text) / 1000f * size;
    }

    private static void show(
            PDPageContentStream cs,
            PDType1Font font,
            int size,
            float x,
            float y,
            String text) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
    }
}
