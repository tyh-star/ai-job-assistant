package aijob.util;

import java.util.regex.Pattern;

public final class HtmlTextExtractor {

    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile(
            "(?is)<(script|style)\\b[^>]*>.*?</\\1>"
    );
    private static final Pattern BREAK = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern BLOCK_END = Pattern.compile(
            "(?i)</(p|div|h[1-6]|li|tr|blockquote|section|article)>"
    );
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern MANY_NEWLINES = Pattern.compile("\\n{3,}");

    private HtmlTextExtractor() {
    }

    public static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        String text = SCRIPT_OR_STYLE.matcher(html).replaceAll(" ");
        text = BREAK.matcher(text).replaceAll("\n");
        text = BLOCK_END.matcher(text).replaceAll("\n");
        text = TAG.matcher(text).replaceAll(" ");
        text = decodeEntities(text);
        text = WHITESPACE.matcher(text).replaceAll(" ");
        text = MANY_NEWLINES.matcher(text).replaceAll("\n\n");
        return text.strip();
    }

    private static String decodeEntities(String text) {
        return text.replace("&nbsp;", " ")
                .replace("&#160;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }
}
