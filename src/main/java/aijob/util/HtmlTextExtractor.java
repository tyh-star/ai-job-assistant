package aijob.util;

import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

public final class HtmlTextExtractor {

    private static final int MAX_ENTITY_PASSES = 3;
    private static final Map<String, String> NAMED_ENTITIES = Map.of(
            "nbsp", " ",
            "amp", "&",
            "lt", "<",
            "gt", ">",
            "quot", "\"",
            "apos", "'"
    );
    private static final Pattern ENTITY = Pattern.compile(
            "&(#[xX][0-9a-fA-F]+|#\\d+|[a-zA-Z][a-zA-Z0-9]+);"
    );
    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile(
            "(?is)<(script|style)\\b[^>]*>.*?</\\1>"
    );
    private static final Pattern BREAK = Pattern.compile("(?is)<br\\s*/?>");
    private static final Pattern BLOCK = Pattern.compile(
            "(?is)</?(div|p|h[1-6]|li|ul|ol|tr|td|th|blockquote|section|"
                    + "article|header|footer|hr|table|thead|tbody|pre)\\b[^>]*>"
    );
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern HORIZONTAL_WS = Pattern.compile("[ \\t\\x0B\\f\\r]+");
    private static final Pattern LINE_EDGES = Pattern.compile(" *\\n *");
    private static final Pattern MANY_NEWLINES = Pattern.compile("\\n{3,}");

    private HtmlTextExtractor() {
    }

    public static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        // Greenhouse Board API stores content as HTML-entity-encoded markup
        // (&lt;div class=&quot;...&quot;&gt;), sometimes with nested &amp;nbsp;.
        // Decode before stripping, otherwise tags reappear as raw HTML.
        String text = decodeEntities(html);
        text = SCRIPT_OR_STYLE.matcher(text).replaceAll("\n");
        text = BREAK.matcher(text).replaceAll("\n");
        text = BLOCK.matcher(text).replaceAll("\n");
        text = TAG.matcher(text).replaceAll("");
        text = HORIZONTAL_WS.matcher(text).replaceAll(" ");
        text = LINE_EDGES.matcher(text).replaceAll("\n");
        text = MANY_NEWLINES.matcher(text).replaceAll("\n\n");
        return text.strip();
    }

    private static String decodeEntities(String text) {
        String current = text;
        for (int i = 0; i < MAX_ENTITY_PASSES; i++) {
            String next = ENTITY.matcher(current).replaceAll(HtmlTextExtractor::replaceEntity);
            if (next.equals(current)) {
                break;
            }
            current = next;
        }
        return current;
    }

    private static String replaceEntity(MatchResult matcher) {
        String body = matcher.group(1);
        if (body.charAt(0) == '#') {
            try {
                int codePoint = body.charAt(1) == 'x' || body.charAt(1) == 'X'
                        ? Integer.parseInt(body.substring(2), 16)
                        : Integer.parseInt(body.substring(1));
                if (codePoint == 160) {
                    return " ";
                }
                if (Character.isValidCodePoint(codePoint)) {
                    return new String(Character.toChars(codePoint));
                }
            } catch (NumberFormatException ignored) {
                return matcher.group();
            }
            return matcher.group();
        }
        return NAMED_ENTITIES.getOrDefault(body.toLowerCase(), matcher.group());
    }
}
