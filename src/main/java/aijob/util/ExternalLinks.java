package aijob.util;

import java.util.Locale;

/**
 * Safety checks for links this deployment stores and later renders in a browser.
 *
 * <p>These are <em>display</em> links, and they are governed by different rules than
 * fetch targets. A posting's apply link legitimately points at the employer's own
 * site, a third-party assessment vendor, or a government portal, so the ATS host
 * allowlist in {@code AtsHostAllowlist} must not be applied here — that allowlist
 * exists to constrain what the collector will connect to, and nothing in this class
 * is ever requested by the server.
 *
 * <p>What is checked is what actually matters for a stored link: the scheme must be
 * http or https, so {@code javascript:}, {@code data:} and {@code vbscript:} payloads
 * can never reach an {@code href}; smuggled control characters are removed first, so
 * {@code "java\nscript:"} cannot slip past the scheme test; and embedded credentials
 * are rejected rather than persisted.
 */
public final class ExternalLinks {

    private static final int MAX_LENGTH = 1000;

    private ExternalLinks() {
    }

    /**
     * @return the link, cleaned, when it is safe to store and render; {@code null}
     *         when it is not. Callers store {@code null} rather than an unsafe link.
     */
    public static String displayUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        // Control characters and whitespace are how "java\nscript:" style bypasses are
        // smuggled past a scheme check, so they go before anything is compared.
        String url = value.strip().replaceAll("[\\p{Cntrl}\\s]", "");
        if (url.isEmpty() || url.length() > MAX_LENGTH) {
            return null;
        }

        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return null;
        }

        String authority = url.substring(url.indexOf("//") + 2);
        int endOfAuthority = indexOfFirst(authority, '/', '?', '#');
        if (endOfAuthority >= 0) {
            authority = authority.substring(0, endOfAuthority);
        }
        if (authority.isEmpty() || authority.indexOf('@') >= 0) {
            // user:password@host in a stored link is either a credential leak or an
            // attempt to disguise the real host in the address bar.
            return null;
        }
        return url;
    }

    private static int indexOfFirst(String value, char... candidates) {
        int found = -1;
        for (char candidate : candidates) {
            int index = value.indexOf(candidate);
            if (index >= 0 && (found < 0 || index < found)) {
                found = index;
            }
        }
        return found;
    }
}
