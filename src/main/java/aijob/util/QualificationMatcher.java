package aijob.util;

import aijob.dto.RequirementCategory;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeStructure;
import aijob.dto.TailorAnalysisRequirement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Matches QUALIFICATION requirements against education nodes / structured education,
 * never against project or experience bullets.
 */
public final class QualificationMatcher {

    public static final String CURRENTLY_PURSUING = "currently pursuing";

    private static final Pattern IN_PROGRESS = Pattern.compile(
            "(?i)\\b(present|current|currently|pursuing|in progress|expected|在读)\\b"
    );

    private QualificationMatcher() {
    }

    public record EducationHit(String nodeId, List<String> quotes, boolean inProgress) {
        public String quote() {
            if (quotes == null || quotes.isEmpty()) {
                return "";
            }
            return quotes.get(0);
        }
    }

    public static EducationHit match(
            TailorAnalysisRequirement requirement,
            List<ResumeDocumentNode> nodes,
            ResumeStructure structure) {

        if (requirement == null || !RequirementCategory.QUALIFICATION.equals(requirement.getCategory())) {
            return null;
        }
        List<EducationSource> sources = collect(nodes, structure);
        if (sources.isEmpty()) {
            return null;
        }
        boolean sectionInProgress = false;
        EducationHit best = null;
        for (EducationSource source : sources) {
            if (source.inProgress) {
                sectionInProgress = true;
            }
            if (best == null && matchesRequirement(requirement, source.text)) {
                best = new EducationHit(source.nodeId, source.quotes, source.inProgress);
            }
        }
        if (best == null) {
            return null;
        }
        if (sectionInProgress) {
            return new EducationHit(best.nodeId(), best.quotes(), true);
        }
        return best;
    }

    public static String joinEducation(ResumeStructure.ResumeEducation education) {
        if (education == null) {
            return "";
        }
        return String.join(" ", List.of(
                nullToEmpty(education.getSchool()),
                nullToEmpty(education.getDegree()),
                nullToEmpty(education.getDates()),
                nullToEmpty(education.getDetails())
        )).replaceAll("\\s+", " ").strip();
    }

    public static String structuredEducationId(int index) {
        return ResumeAnalysisInputFactory.STRUCTURED_EDUCATION_PREFIX + index;
    }

    static boolean isInProgress(String text) {
        return text != null && IN_PROGRESS.matcher(text).find();
    }

    private static boolean matchesRequirement(TailorAnalysisRequirement requirement, String educationText) {
        if (TermAliasNormalizer.matches(educationText, requirement.getRequirement())) {
            return true;
        }
        if (requirement.getExactTerms() != null) {
            for (String term : requirement.getExactTerms()) {
                if (term != null && !term.isBlank() && TermAliasNormalizer.matches(educationText, term)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<EducationSource> collect(List<ResumeDocumentNode> nodes, ResumeStructure structure) {
        List<EducationSource> sources = new ArrayList<>();
        if (structure != null && structure.getEducation() != null) {
            for (int index = 0; index < structure.getEducation().size(); index++) {
                ResumeStructure.ResumeEducation education = structure.getEducation().get(index);
                if (education == null) {
                    continue;
                }
                String text = joinEducation(education);
                if (text.isBlank()) {
                    continue;
                }
                sources.add(new EducationSource(
                        structuredEducationId(index),
                        text,
                        quotesForStructured(education, text),
                        isInProgress(text) || isInProgress(education.getDates())
                ));
            }
        }
        boolean inEducation = false;
        if (nodes != null) {
            for (ConservativeTailorPlanner.ClassifiedNode classified : ConservativeTailorPlanner.classify(nodes)) {
                if (classified.node() == null) {
                    continue;
                }
                if (classified.role() == ResumeNodeRole.HEADING) {
                    inEducation = isEducationHeading(classified.node().getText());
                    continue;
                }
                if (!inEducation) {
                    continue;
                }
                String text = classified.node().getText();
                sources.add(new EducationSource(
                        classified.node().getNodeId(),
                        text,
                        List.of(quoteFor(text)),
                        isInProgress(text)
                ));
            }
        }
        return sources;
    }

    private static boolean isEducationHeading(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.replaceAll("[^A-Za-z\\s]", " ")
                .replaceAll("\\s+", " ")
                .strip()
                .toUpperCase(Locale.ROOT);
        return "EDUCATION".equals(normalized) || "ACADEMIC BACKGROUND".equals(normalized);
    }

    private static List<String> quotesForStructured(
            ResumeStructure.ResumeEducation education,
            String joined) {
        List<String> quotes = new ArrayList<>();
        if (!isBlank(education.getDegree())) {
            quotes.add(education.getDegree().strip());
        }
        if (!isBlank(education.getDates())) {
            quotes.add(education.getDates().strip());
        }
        if (quotes.isEmpty() && !joined.isBlank()) {
            quotes.add(quoteFor(joined));
        }
        return quotes;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String quoteFor(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        if (stripped.length() <= 180) {
            return stripped;
        }
        return stripped.substring(0, 180).strip();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record EducationSource(String nodeId, String text, List<String> quotes, boolean inProgress) {
    }
}
