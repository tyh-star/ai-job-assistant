package aijob.util;

import aijob.dto.builder.ResumeBuilderDraftContent.BasicInfo;
import aijob.dto.builder.ResumeBuilderDraftContent.ProfessionalLink;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Canonical professional-link list plus backward compatibility for drafts that still
 * store {@code linkedin}/{@code github}/{@code website} scalars. Those three fields stay
 * on {@link BasicInfo} so existing {@code draft_json} rows deserialize without data
 * loss; this helper is the one place that migrates scalars into the list and mirrors
 * the list back onto the scalars for any older reader.
 */
public final class ResumeBuilderProfessionalLinks {

    public static final String LINKEDIN = "LINKEDIN";
    public static final String PORTFOLIO = "PORTFOLIO";
    public static final String GITHUB = "GITHUB";
    public static final String PERSONAL_WEBSITE = "PERSONAL_WEBSITE";
    public static final String BEHANCE = "BEHANCE";
    public static final String OTHER = "OTHER";

    public static final Set<String> TYPES = Set.of(
            LINKEDIN, PORTFOLIO, GITHUB, PERSONAL_WEBSITE, BEHANCE, OTHER);

    private ResumeBuilderProfessionalLinks() {
    }

    public static String canonicalType(String type) {
        if (type == null || type.isBlank()) {
            return OTHER;
        }
        String upper = type.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if ("WEBSITE".equals(upper) || "PERSONALWEBSITE".equals(upper)) {
            return PERSONAL_WEBSITE;
        }
        return TYPES.contains(upper) ? upper : OTHER;
    }

    /**
     * If {@code professionalLinks} is {@code null}, this is a pre-upgrade draft: copy
     * non-blank scalars into the list. An explicit empty list is left empty (the user
     * cleared every link). Afterward, scalars are rewritten from the list so older
     * clients that still read {@code github} keep seeing the GitHub URL when one exists.
     */
    public static void migrateAndSync(BasicInfo info) {
        if (info == null) {
            return;
        }
        List<ProfessionalLink> links = info.getProfessionalLinks();
        if (links == null) {
            links = new ArrayList<>();
            addFromScalar(links, LINKEDIN, info.getLinkedin());
            addFromScalar(links, GITHUB, info.getGithub());
            addFromScalar(links, PERSONAL_WEBSITE, info.getWebsite());
            info.setProfessionalLinks(links);
        }
        List<ProfessionalLink> cleaned = new ArrayList<>();
        for (ProfessionalLink link : links) {
            if (link == null) {
                continue;
            }
            link.setType(canonicalType(link.getType()));
            link.setLabel(link.getLabel() == null ? "" : link.getLabel().trim());
            link.setUrl(link.getUrl() == null ? "" : link.getUrl().trim());
            cleaned.add(link);
        }
        info.setProfessionalLinks(cleaned);
        info.setLinkedin(firstUrlOfType(cleaned, LINKEDIN));
        info.setGithub(firstUrlOfType(cleaned, GITHUB));
        info.setWebsite(firstUrlOfType(cleaned, PERSONAL_WEBSITE));
    }

    /** Only filled URLs -- never a type name, never "GitHub", never N/A. */
    public static List<String> headerLines(BasicInfo info) {
        List<String> lines = new ArrayList<>();
        if (info == null || info.getProfessionalLinks() == null) {
            return lines;
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ProfessionalLink link : info.getProfessionalLinks()) {
            if (link == null) {
                continue;
            }
            String url = link.getUrl() == null ? "" : link.getUrl().trim();
            if (url.isBlank() || !seen.add(url)) {
                continue;
            }
            String label = link.getLabel() == null ? "" : link.getLabel().trim();
            lines.add(label.isBlank() ? url : label + " — " + url);
        }
        return lines;
    }

    public static ProfessionalLink empty(String type) {
        ProfessionalLink link = new ProfessionalLink();
        link.setType(canonicalType(type));
        link.setLabel("");
        link.setUrl("");
        return link;
    }

    private static void addFromScalar(List<ProfessionalLink> links, String type, String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        ProfessionalLink link = empty(type);
        link.setUrl(url.trim());
        links.add(link);
    }

    private static String firstUrlOfType(List<ProfessionalLink> links, String type) {
        for (ProfessionalLink link : links) {
            if (type.equals(link.getType()) && link.getUrl() != null && !link.getUrl().isBlank()) {
                return link.getUrl();
            }
        }
        return "";
    }
}
