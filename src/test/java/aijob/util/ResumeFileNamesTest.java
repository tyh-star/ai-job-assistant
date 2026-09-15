package aijob.util;

import org.junit.jupiter.api.Test;
import org.springframework.http.ContentDisposition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeFileNamesTest {

    @Test
    void realNamesAreKeptAsIs() {
        assertEquals("简历.pdf", ResumeFileNames.displayName("简历.pdf"));
        assertEquals("简历 (1).pdf", ResumeFileNames.displayName("简历 (1).pdf"));
        assertEquals("alex-morgan.pdf", ResumeFileNames.displayName("alex-morgan.pdf"));
    }

    @Test
    void phoneUploadEncodingIsDecoded() {
        assertEquals("简历.pdf", ResumeFileNames.displayName("%E7%AE%80%E5%8E%86.pdf"));
        assertEquals("简历 (1).pdf", ResumeFileNames.displayName("%E7%AE%80%E5%8E%86%20(1).pdf"));
        assertEquals("简历 (1).pdf", ResumeFileNames.displayName("%e7%ae%80%e5%8e%86%20%281%29.pdf"));
    }

    @Test
    void invalidPercentSequencesKeepTheStoredName() {
        assertEquals("100%.pdf", ResumeFileNames.displayName("100%.pdf"));
        assertEquals("%ZZ.pdf", ResumeFileNames.displayName("%ZZ.pdf"));
        assertEquals("%E7%AE.pdf", ResumeFileNames.displayName("%E7%AE.pdf"));
        assertEquals("简历%E7.pdf", ResumeFileNames.displayName("简历%E7.pdf"));
        assertEquals("%", ResumeFileNames.displayName("%"));
    }

    @Test
    void repeatedEncodingIsDecodedLayerByLayer() {
        assertEquals("简历.pdf", ResumeFileNames.displayName("%25E7%25AE%2580%25E5%258E%2586.pdf"));
        assertEquals("简历.pdf", ResumeFileNames.displayName("%2525E7%2525AE%252580%2525E5%25258E%252586.pdf"));
    }

    @Test
    void decodingHappensBeforePathAndControlCleanup() {
        assertEquals("passwd.pdf", ResumeFileNames.displayName("..%2F..%2Fetc%2Fpasswd.pdf"));
        assertEquals("b.pdf", ResumeFileNames.displayName("a%5Cb.pdf"));
        assertEquals("cv.pdf", ResumeFileNames.displayName("cv%00.pdf"));
        assertEquals("cv.pdf.exe", ResumeFileNames.displayName("cv.pdf%00.exe"));
        assertEquals("简历.pdf", ResumeFileNames.displayName("..%2F%E7%AE%80%E5%8E%86.pdf"));
        assertEquals("x.pdf", ResumeFileNames.displayName("../x.pdf"));
        assertEquals("x.pdf", ResumeFileNames.displayName("C:\\Users\\a\\x.pdf"));
        assertEquals("fdp.exe", ResumeFileNames.displayName("\u202Efdp.exe"));
        assertEquals("", ResumeFileNames.displayName("%2E%2E"));
        assertEquals("", ResumeFileNames.displayName(null));
    }

    @Test
    void downloadNameFallsBackWhenNothingIsLeft() {
        assertEquals("简历.pdf", ResumeFileNames.downloadName("%E7%AE%80%E5%8E%86.pdf", "pdf"));
        assertEquals("resume.pdf", ResumeFileNames.downloadName("..%2F", "pdf"));
        assertEquals("resume.docx", ResumeFileNames.downloadName(null, "docx"));
    }

    @Test
    void longNamesKeepTheirExtension() {
        String name = "长".repeat(300) + ".pdf";
        String shown = ResumeFileNames.displayName(name);
        assertEquals(255, shown.length());
        assertTrue(shown.endsWith(".pdf"));
    }

    @Test
    void attachmentHeaderCarriesUnicodeAndAnAsciiFallback() {
        String header = ResumeFileNames.attachmentHeader("简历 (1).pdf");
        assertEquals("attachment; filename=\"_ (1).pdf\"; filename*=UTF-8''%E7%AE%80%E5%8E%86%20%281%29.pdf", header);
        assertEquals("简历 (1).pdf", ContentDisposition.parse(header).getFilename());

        String chinese = ResumeFileNames.attachmentHeader("简历.pdf");
        assertEquals("attachment; filename=\"resume.pdf\"; filename*=UTF-8''%E7%AE%80%E5%8E%86.pdf", chinese);

        String ascii = ResumeFileNames.attachmentHeader("alex \"morgan\".pdf");
        assertFalse(ascii.substring(0, ascii.indexOf(';', 12)).contains("\"morgan\""));
        assertEquals("alex \"morgan\".pdf", ContentDisposition.parse(ascii).getFilename());
    }
}
