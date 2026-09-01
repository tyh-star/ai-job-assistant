package aijob.util;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Allowlist sanitizer for third-party job descriptions.
 *
 * <p>ATS payloads are untrusted. Nothing here is rendered until every tag has been
 * matched against {@link #ALLOWED_TAGS}; anything else is dropped and its text is
 * re-escaped, so scripts, event handlers, styles, iframes and {@code javascript:}
 * links cannot reach the browser.
 */
public final class HtmlSanitizer {

    private static final Set<String> ALLOWED_TAGS = Set.of(
            "p", "br", "ul", "ol", "li", "strong", "b", "em", "i", "u",
            "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "a"
    );
    private static final Set<String> VOID_TAGS = Set.of("br");

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
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern SCRIPT_OR_STYLE = Pattern.compile(
            "(?is)<(script|style)\\b[^>]*>.*?</\\1>"
    );
    private static final Pattern DANGLING_SCRIPT_OR_STYLE = Pattern.compile(
            "(?is)</?(script|style)\\b[^>]*>"
    );
    private static final Pattern TAG = Pattern.compile("(?s)<\\s*(/?)\\s*([a-zA-Z][a-zA-Z0-9]*)([^>]*)>");
    private static final Pattern HREF = Pattern.compile(
            "(?i)\\bhref\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))"
    );

    private HtmlSanitizer() {
    }

    /**
     * @return safe HTML, or an empty string when there is nothing renderable.
     */
    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String decoded = decodeEntities(html);
        decoded = COMMENT.matcher(decoded).replaceAll("");
        decoded = SCRIPT_OR_STYLE.matcher(decoded).replaceAll("");
        decoded = DANGLING_SCRIPT_OR_STYLE.matcher(decoded).replaceAll("");

        StringBuilder out = new StringBuilder(decoded.length());
        Matcher matcher = TAG.matcher(decoded);
        int cursor = 0;
        while (matcher.find()) {
            out.append(escape(decoded.substring(cursor, matcher.start())));
            cursor = matcher.end();

            boolean closing = !matcher.group(1).isEmpty();
            String tag = matcher.group(2).toLowerCase(Locale.ROOT);
            String attributes = matcher.group(3) == null ? "" : matcher.group(3);
            if (!ALLOWED_TAGS.contains(tag)) {
                continue;
            }
            if (VOID_TAGS.contains(tag)) {
                if (!closing) {
                    out.append("<br />");
                }
                continue;
            }
            if (closing) {
                out.append("</").append(tag).append('>');
                continue;
            }
            if ("a".equals(tag)) {
                String href = safeHref(attributes);
                if (href == null) {
                    // Keep the link text, drop the unusable link.
                    continue;
                }
                out.append("<a href=\"").append(escape(href))
                        .append("\" target=\"_blank\" rel=\"nofollow noopener noreferrer\">");
                continue;
            }
            out.append('<').append(tag).append('>');
        }
        out.append(escape(decoded.substring(cursor)));
        return out.toString().strip();
    }

    private static String safeHref(String attributes) {
        Matcher matcher = HREF.matcher(attributes);
        if (!matcher.find()) {
            return null;
        }
        String raw = matcher.group(2) != null ? matcher.group(2)
                : matcher.group(3) != null ? matcher.group(3)
                : matcher.group(4);
        if (raw == null) {
            return null;
        }
        String href = raw.strip();
        // Control characters are how "java\nscript:" style bypasses are smuggled in.
        href = href.replaceAll("[\\p{Cntrl}\\s]", "");
        String lower = href.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;
        }
        return href;
    }

    private static String escape(String text) {
        if (text.isEmpty()) {
            return text;
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String decodeEntities(String text) {
        String current = text;
        for (int i = 0; i < MAX_ENTITY_PASSES; i++) {
            String next = ENTITY.matcher(current).replaceAll(HtmlSanitizer::replaceEntity);
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
        return NAMED_ENTITIES.getOrDefault(body.toLowerCase(Locale.ROOT), matcher.group());
    }
}
