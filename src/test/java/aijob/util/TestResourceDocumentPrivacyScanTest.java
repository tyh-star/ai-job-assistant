package aijob.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Permanent scan of every PDF/DOCX under {@code src/test/resources}. Rules are generic
 * (reserved example emails, 555 test numbers, synthetic identity fields, allowlisted
 * hosts). Failure messages name the file and rule, never the matched value.
 */
class TestResourceDocumentPrivacyScanTest {

    private static final Path RESOURCES = Path.of("src/test/resources");
    private static final Path SYNTHETIC_DOCX =
            RESOURCES.resolve("regression/synthetic-formatting-resume.docx");

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NANP = Pattern.compile(
            "(?:\\+?1[-.\\s]?)?\\(?([2-9]\\d{2})\\)?[-.\\s]([2-9]\\d{2})[-.\\s](\\d{4})");
    private static final Pattern URL = Pattern.compile(
            "https?://[^\\s\"'<>\\\\]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GITHUB_PROFILE = Pattern.compile(
            "(?:https?://)?(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LINKEDIN_PROFILE = Pattern.compile(
            "(?:https?://)?(?:www\\.)?linkedin\\.com/in/([A-Za-z0-9_.-]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\b");
    private static final Pattern XML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern TARGET_ATTR = Pattern.compile(
            "Target\\s*=\\s*\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_PARAGRAPH = Pattern.compile("<w:p[\\s>][\\s\\S]*?</w:p>");
    private static final Pattern WORD_TEXT = Pattern.compile(
            "<w:(?:t|instrText)(?:\\s[^>]*)?>([^<]*)</w:(?:t|instrText)>");

    private static final Set<String> ALLOWED_EMAIL_DOMAINS = Set.of(
            "example.com", "example.test");
    private static final Set<String> ALLOWED_IDENTITY = Set.of(
            "",
            "alex morgan",
            "example company",
            "example university",
            "synthetic-fixture",
            "synthetic fixture");
    private static final Set<String> OFFICE_SCHEMA_HOSTS = Set.of(
            "schemas.openxmlformats.org",
            "schemas.microsoft.com",
            "purl.org",
            "www.w3.org",
            "w3.org",
            "www.wps.cn",
            "wps.cn",
            "ns.adobe.com",
            "www.ecma-international.org");
    private static final Set<String> EXAMPLE_HOST_SUFFIXES = Set.of(
            "example.com", "example.test", "example.org", "example.net");

    @Test
    void checkedInPdfAndDocxFixturesStaySynthetic() throws Exception {
        assertTrue(Files.isRegularFile(SYNTHETIC_DOCX),
                "missing synthetic formatting resume under src/test/resources");
        List<Path> documents = listDocuments();
        assertFalse(documents.isEmpty(), "src/test/resources must contain at least one PDF or DOCX");
        List<String> failures = new ArrayList<>();
        for (Path file : documents) {
            String relative = RESOURCES.relativize(file).toString().replace('\\', '/');
            String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".docx")) {
                scanDocx(file, relative, failures);
            } else if (name.endsWith(".pdf")) {
                scanPdf(file, relative, failures);
            }
        }
        if (!failures.isEmpty()) {
            fail(String.join("\n", failures));
        }
    }

    private static List<Path> listDocuments() throws IOException {
        if (!Files.isDirectory(RESOURCES)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(RESOURCES)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        if (name.startsWith("~$")) {
                            return false;
                        }
                        return name.endsWith(".docx") || name.endsWith(".pdf");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static void scanDocx(Path file, String relative, List<String> failures) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName().replace('\\', '/');
                String lower = entryName.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".xml") || lower.endsWith(".rels"))) {
                    continue;
                }
                String xml;
                try (InputStream in = zip.getInputStream(entry)) {
                    xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                String location = relative + "!" + entryName;
                if (isWordSurface(lower)) {
                    scanText(extractWordSurfaceText(xml), location, failures);
                } else {
                    scanText(stripTags(xml), location, failures);
                }
                scanRelationshipTargets(xml, location, failures);
                if (lower.contains("docprops/") || lower.contains("customxml/")) {
                    scanIdentityFields(xml, location, failures);
                }
            }
        }
    }

    private static void scanPdf(Path file, String relative, List<String> failures) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            PDDocumentInformation info = document.getDocumentInformation();
            if (info != null) {
                scanIdentityValue(info.getAuthor(), relative + "#Author", failures);
                scanText(nullToEmpty(info.getTitle()), relative + "#Title", failures);
                scanText(nullToEmpty(info.getSubject()), relative + "#Subject", failures);
                scanText(nullToEmpty(info.getKeywords()), relative + "#Keywords", failures);
                scanText(nullToEmpty(info.getProducer()), relative + "#Producer", failures);
                scanText(nullToEmpty(info.getCreator()), relative + "#Creator", failures);
                scanText(nullToEmpty(info.getAuthor()), relative + "#AuthorText", failures);
                for (String key : info.getMetadataKeys()) {
                    String value = info.getCustomMetadataValue(key);
                    String location = relative + "#meta/" + key;
                    if (key != null && Set.of("author", "company", "manager", "lastmodifiedby")
                            .contains(key.toLowerCase(Locale.ROOT))) {
                        scanIdentityValue(value, location, failures);
                    }
                    scanText(nullToEmpty(value), location, failures);
                }
            }
            if (document.getDocumentCatalog().getMetadata() != null) {
                try (InputStream xmp = document.getDocumentCatalog().getMetadata().exportXMPMetadata()) {
                    scanText(new String(xmp.readAllBytes(), StandardCharsets.UTF_8), relative + "#xmp", failures);
                }
            }
            PDFTextStripper stripper = new PDFTextStripper();
            scanText(stripper.getText(document), relative + "#body", failures);
            for (PDPage page : document.getPages()) {
                for (PDAnnotation annotation : page.getAnnotations()) {
                    if (annotation instanceof PDAnnotationLink link) {
                        PDAction action = link.getAction();
                        if (action instanceof PDActionURI uriAction) {
                            checkUrl(uriAction.getURI(), relative + "#link", failures);
                        }
                    }
                }
            }
        }
    }

    private static boolean isWordSurface(String lowerName) {
        return lowerName.endsWith("word/document.xml")
                || lowerName.contains("word/header")
                || lowerName.contains("word/footer")
                || lowerName.contains("word/comments")
                || lowerName.contains("word/footnotes")
                || lowerName.contains("word/endnotes");
    }

    private static String extractWordSurfaceText(String xml) {
        StringBuilder out = new StringBuilder();
        Matcher paragraphs = WORD_PARAGRAPH.matcher(xml);
        boolean found = false;
        while (paragraphs.find()) {
            found = true;
            Matcher texts = WORD_TEXT.matcher(paragraphs.group());
            while (texts.find()) {
                out.append(unescapeXml(texts.group(1)));
            }
            out.append('\n');
        }
        return found ? out.toString() : stripTags(xml);
    }

    private static void scanRelationshipTargets(String xml, String location, List<String> failures) {
        Matcher targets = TARGET_ATTR.matcher(xml);
        while (targets.find()) {
            String target = unescapeXml(targets.group(1));
            if (looksExternal(target)) {
                checkUrl(target, location + "#rel", failures);
            }
        }
    }

    private static void scanIdentityFields(String xml, String location, List<String> failures) {
        scanTaggedValue(xml, "creator", location, failures);
        scanTaggedValue(xml, "lastModifiedBy", location, failures);
        scanTaggedValue(xml, "Company", location, failures);
        scanTaggedValue(xml, "Manager", location, failures);
    }

    private static void scanTaggedValue(
            String xml, String localName, String location, List<String> failures) {
        Pattern tagged = Pattern.compile(
                "<(?:[A-Za-z0-9._-]+:)?" + Pattern.quote(localName) + ">([^<]*)</(?:[A-Za-z0-9._-]+:)?"
                        + Pattern.quote(localName) + ">",
                Pattern.CASE_INSENSITIVE);
        Matcher matcher = tagged.matcher(xml);
        while (matcher.find()) {
            scanIdentityValue(unescapeXml(matcher.group(1)), location + "#" + localName, failures);
        }
    }

    private static void scanIdentityValue(String raw, String location, List<String> failures) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (ALLOWED_IDENTITY.contains(lower)) {
            return;
        }
        if (lower.startsWith("apache pdfbox") || lower.equals("pdfbox")) {
            return;
        }
        if (lower.startsWith("wps office") || lower.startsWith("microsoft") || lower.startsWith("libreoffice")) {
            return;
        }
        failures.add(location + ": identity field is not a synthetic-fixture value");
    }

    private static void scanText(String text, String location, List<String> failures) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher emails = EMAIL.matcher(text);
        while (emails.find()) {
            String domain = emails.group().substring(emails.group().indexOf('@') + 1)
                    .toLowerCase(Locale.ROOT);
            if (!ALLOWED_EMAIL_DOMAINS.contains(domain)) {
                failures.add(location + ": email domain is not a reserved example domain");
            }
        }
        Matcher phones = NANP.matcher(text);
        while (phones.find()) {
            String npa = phones.group(1);
            String nxx = phones.group(2);
            if (!"555".equals(npa) && !"555".equals(nxx)) {
                failures.add(location + ": NANP telephone is not a 555 test number");
            }
        }
        Matcher urls = URL.matcher(text);
        while (urls.find()) {
            checkUrl(urls.group(), location, failures);
        }
        Matcher github = GITHUB_PROFILE.matcher(text);
        while (github.find()) {
            if (!"alexmorgan".equalsIgnoreCase(github.group(1))) {
                failures.add(location + ": GitHub profile is not the synthetic fixture account");
            }
        }
        Matcher linkedin = LINKEDIN_PROFILE.matcher(text);
        while (linkedin.find()) {
            if (!"alexmorgan".equalsIgnoreCase(linkedin.group(1))) {
                failures.add(location + ": LinkedIn profile is not the synthetic fixture account");
            }
        }
        Matcher ip = IPV4.matcher(text);
        while (ip.find()) {
            failures.add(location + ": IPv4 address is not allowed in test documents");
        }
    }

    private static void checkUrl(String rawUrl, String location, List<String> failures) {
        String trimmed = rawUrl == null ? "" : unescapeXml(rawUrl).replace("&quot;", "").trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String candidate = trimmed.split("[\\s<>\"']", 2)[0];
        if (!candidate.contains("://")) {
            if (looksExternal(candidate)) {
                candidate = "https://" + candidate;
            } else {
                return;
            }
        }
        URI uri;
        try {
            uri = URI.create(candidate);
        } catch (IllegalArgumentException ex) {
            return;
        }
        String scheme = nullToEmpty(uri.getScheme()).toLowerCase(Locale.ROOT);
        if ("mailto".equals(scheme)) {
            String address = nullToEmpty(uri.getSchemeSpecificPart());
            Matcher email = EMAIL.matcher(address);
            if (email.find()) {
                String domain = email.group().substring(email.group().indexOf('@') + 1)
                        .toLowerCase(Locale.ROOT);
                if (!ALLOWED_EMAIL_DOMAINS.contains(domain)) {
                    failures.add(location + ": mailto domain is not a reserved example domain");
                }
            }
            return;
        }
        if (!scheme.isEmpty() && !scheme.equals("http") && !scheme.equals("https")) {
            if (scheme.equals("file") || scheme.equals("ftp")) {
                failures.add(location + ": disallowed URL scheme");
            }
            return;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return;
        }
        String hostLower = host.toLowerCase(Locale.ROOT);
        if (!hostLower.contains(".")) {
            return;
        }
        if (IPV4.matcher(hostLower).matches()) {
            failures.add(location + ": URL host is an IP address");
            return;
        }
        if (isOfficeSchemaHost(hostLower) || isExampleHost(hostLower)) {
            return;
        }
        if (hostLower.equals("github.com") || hostLower.equals("www.github.com")) {
            String user = firstPathSegment(uri.getPath());
            if ("alexmorgan".equalsIgnoreCase(user)) {
                return;
            }
            failures.add(location + ": GitHub host is not the synthetic fixture account");
            return;
        }
        if (hostLower.equals("linkedin.com") || hostLower.equals("www.linkedin.com")) {
            String[] parts = nullToEmpty(uri.getPath()).split("/");
            List<String> segments = new ArrayList<>();
            for (String part : parts) {
                if (!part.isBlank()) {
                    segments.add(part);
                }
            }
            if (segments.size() >= 2
                    && "in".equalsIgnoreCase(segments.get(0))
                    && "alexmorgan".equalsIgnoreCase(segments.get(1))) {
                return;
            }
            failures.add(location + ": LinkedIn host is not the synthetic fixture account");
            return;
        }
        failures.add(location + ": external URL host is not on the synthetic allowlist");
    }

    private static boolean isOfficeSchemaHost(String host) {
        for (String allowed : OFFICE_SCHEMA_HOSTS) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExampleHost(String host) {
        for (String suffix : EXAMPLE_HOST_SUFFIXES) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksExternal(String target) {
        String lower = target.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("mailto:")
                || lower.startsWith("file:")
                || lower.startsWith("//");
    }

    private static String firstPathSegment(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        for (String part : path.split("/")) {
            if (!part.isBlank()) {
                return part;
            }
        }
        return "";
    }

    private static String stripTags(String xml) {
        return XML_TAG.matcher(xml).replaceAll("");
    }

    private static String unescapeXml(String value) {
        return value
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
