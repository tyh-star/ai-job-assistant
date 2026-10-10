package aijob.util;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * One place for resume file names: what is stored on upload, what the API returns, and
 * what a download is called.
 * <p>
 * React Native's FormData sends {@code filename="<encodeURIComponent(name)>"} and the
 * multipart parser does not decode it, so a phone upload of "简历.pdf" used to be stored
 * as "%E7%AE%80%E5%8E%86.pdf". Browsers send the real name. Rows already stored encoded
 * are decoded on the way out, so nobody has to upload again.
 * <p>
 * Order matters: percent-decoding runs first, then path and control-character cleanup,
 * so an encoded "%2F", "%5C" or "%00" can never survive as a separator or NUL.
 */
public final class ResumeFileNames {

    private static final int MAX_LAYERS = 3;
    private static final int MAX_LENGTH = 255;
    /** Only characters encodeURIComponent leaves alone, plus escapes. */
    private static final Pattern ENCODED_SHAPE = Pattern.compile("[A-Za-z0-9\\-_.!~*'()%]*");
    private static final Pattern ESCAPE = Pattern.compile("%[0-9A-Fa-f]{2}");
    private static final Pattern BROKEN_PERCENT = Pattern.compile("%(?![0-9A-Fa-f]{2})");
    /** C0/C1 controls, DEL and bidirectional overrides that could disguise an extension. */
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cntrl}\\u0080-\\u009F\\u202A-\\u202E\\u2066-\\u2069]");

    private ResumeFileNames() {
    }

    /**
     * Undoes percent-encoding, at most a few layers, and only while the value looks fully
     * encoded and decodes to valid UTF-8. Anything else ("简历 (1).pdf", "100%.pdf",
     * "%E7%AE.pdf", "%ZZ.pdf") is returned unchanged.
     */
    public static String decodeStored(String stored) {
        if (stored == null) {
            return "";
        }
        String current = stored;
        for (int layer = 0; layer < MAX_LAYERS; layer++) {
            if (!ESCAPE.matcher(current).find()
                    || !ENCODED_SHAPE.matcher(current).matches()
                    || BROKEN_PERCENT.matcher(current).find()) {
                break;
            }
            String next = strictDecode(current);
            if (next == null || next.equals(current)) {
                break;
            }
            current = next;
        }
        return current;
    }

    /** The name to store and show: decoded, last path segment only, no control characters. */
    public static String displayName(String stored) {
        String decoded = decodeStored(stored);
        int slash = Math.max(decoded.lastIndexOf('/'), decoded.lastIndexOf('\\'));
        String segment = slash >= 0 ? decoded.substring(slash + 1) : decoded;
        String cleaned = UNSAFE.matcher(segment).replaceAll("").trim();
        if (cleaned.equals(".") || cleaned.equals("..")) {
            return "";
        }
        return truncate(cleaned);
    }

    /** Upload side: same rules as {@link #displayName}; blank means the name is unusable. */
    public static String uploadName(String originalFileName) {
        return displayName(originalFileName);
    }

    /** Download side: the display name, or "resume.&lt;type&gt;" when nothing usable is left. */
    public static String downloadName(String stored, String fileType) {
        String name = displayName(stored);
        if (!name.isBlank()) {
            return name;
        }
        return fileType == null || fileType.isBlank() ? "resume" : "resume." + fileType;
    }

    /**
     * {@code attachment; filename="<ASCII fallback>"; filename*=UTF-8''<RFC 5987>}. Modern
     * browsers use filename*; the quoted fallback never contains quotes, backslashes or
     * non-ASCII characters.
     */
    public static String attachmentHeader(String fileName) {
        String name = displayName(fileName);
        if (name.isBlank()) {
            name = "download";
        }
        return "attachment; filename=\"" + asciiFallback(name) + "\"; filename*=UTF-8''" + rfc5987(name);
    }

    private static String strictDecode(String value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 >= value.length()) {
                    return null;
                }
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high < 0 || low < 0) {
                    return null;
                }
                bytes.write(high * 16 + low);
                i += 2;
            } else {
                bytes.write(c);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String truncate(String name) {
        if (name.length() <= MAX_LENGTH) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= 10 ? name.substring(dot) : "";
        int end = MAX_LENGTH - extension.length();
        if (Character.isHighSurrogate(name.charAt(end - 1))) {
            end--;
        }
        return name.substring(0, end) + extension;
    }

    private static String asciiFallback(String name) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean safe = c >= 0x20 && c <= 0x7E && c != '"' && c != '\\';
            out.append(safe ? c : '_');
        }
        String fallback = out.toString().replaceAll("_+", "_");
        int dot = fallback.lastIndexOf('.');
        String base = dot > 0 ? fallback.substring(0, dot) : fallback;
        String extension = dot > 0 ? fallback.substring(dot) : "";
        if (!base.matches(".*[A-Za-z0-9].*")) {
            return "resume" + extension;
        }
        return fallback;
    }

    private static String rfc5987(String name) {
        StringBuilder out = new StringBuilder();
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            boolean attrChar = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || "!#$&+-.^_`|~".indexOf(c) >= 0;
            if (attrChar) {
                out.append((char) c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }
}
