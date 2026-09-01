package aijob.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Job descriptions arrive as third-party HTML, so the sanitizer is the only thing
 * between an ATS payload and the browser.
 */
class HtmlSanitizerTest {

    @Test
    void keepsOrdinaryFormatting() {
        String safe = HtmlSanitizer.sanitize(
                "<p>Build APIs.</p><ul><li>Java</li><li>SQL</li></ul><strong>Bonus</strong>");

        assertEquals("<p>Build APIs.</p><ul><li>Java</li><li>SQL</li></ul><strong>Bonus</strong>", safe);
    }

    @Test
    void dropsScriptAndStyleWithTheirContents() {
        String safe = HtmlSanitizer.sanitize(
                "<p>Hi</p><script>steal(document.cookie)</script><style>body{display:none}</style>");

        assertEquals("<p>Hi</p>", safe);
        assertFalse(safe.contains("steal"));
        assertFalse(safe.contains("display:none"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<img src=x onerror=alert(1)>",
            "<iframe src=\"https://evil.example\"></iframe>",
            "<svg/onload=alert(1)>",
            "<object data=\"https://evil.example\"></object>",
            "<form action=\"https://evil.example\"><input name=\"p\"></form>",
    })
    void dropsTagsThatAreNotOnTheAllowlist(String html) {
        String safe = HtmlSanitizer.sanitize(html);

        assertFalse(safe.contains("<img"));
        assertFalse(safe.contains("<iframe"));
        assertFalse(safe.contains("<svg"));
        assertFalse(safe.contains("<object"));
        assertFalse(safe.contains("<form"));
        assertFalse(safe.contains("<input"));
    }

    @Test
    void stripsEventHandlersAndInlineStylesFromAllowedTags() {
        String safe = HtmlSanitizer.sanitize("<p onclick=\"steal()\" style=\"color:red\">Hi</p>");

        assertEquals("<p>Hi</p>", safe);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "<a href=\"javascript:alert(1)\">click</a>",
            "<a href=\"JaVaScRiPt:alert(1)\">click</a>",
            "<a href=\"java\nscript:alert(1)\">click</a>",
            "<a href=\"data:text/html;base64,PHNjcmlwdD4=\">click</a>",
            "<a href=\"vbscript:msgbox(1)\">click</a>",
            "<a href=\"file:///etc/passwd\">click</a>",
    })
    void keepsLinkTextButDropsAnUnusableHref(String html) {
        String safe = HtmlSanitizer.sanitize(html);

        assertTrue(safe.contains("click"));
        assertFalse(safe.contains("href"));
        assertFalse(safe.toLowerCase().contains("javascript"));
        assertFalse(safe.toLowerCase().contains("vbscript"));
        assertFalse(safe.toLowerCase().contains("data:text/html"));
    }

    @Test
    void anHttpsLinkSurvivesAndIsMarkedUntrusted() {
        String safe = HtmlSanitizer.sanitize("<a href=\"https://acme.example/jobs/1\">Apply</a>");

        assertTrue(safe.contains("href=\"https://acme.example/jobs/1\""));
        assertTrue(safe.contains("rel=\"nofollow noopener noreferrer\""));
        assertTrue(safe.contains("target=\"_blank\""));
    }

    @Test
    void resolvesEntityEncodedTagsBeforeDecidingWhatIsSafe() {
        String safe = HtmlSanitizer.sanitize("&lt;script&gt;alert(1)&lt;/script&gt;<p>Hi</p>");

        assertFalse(safe.contains("<script"));
        assertTrue(safe.contains("<p>Hi</p>"));
    }

    @Test
    void escapesBareTextSoItCannotBecomeMarkup() {
        String safe = HtmlSanitizer.sanitize("<p>5 > 3 && 2 < 4</p>");

        assertTrue(safe.contains("&gt;"));
        assertTrue(safe.contains("&lt;"));
        assertTrue(safe.contains("&amp;"));
    }

    @Test
    void handlesEmptyAndNullInput() {
        assertEquals("", HtmlSanitizer.sanitize(null));
        assertEquals("", HtmlSanitizer.sanitize("   "));
    }

    @Test
    void anUnclosedScriptTagStillCannotSurvive() {
        String safe = HtmlSanitizer.sanitize("<p>Hi</p><script>alert(1)");

        assertFalse(safe.contains("<script"));
    }
}
