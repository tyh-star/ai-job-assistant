package aijob.util;

import aijob.dto.ResumeDocumentNode;
import aijob.dto.RewriteIntent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scores every eligible resume node for rewrite opportunity.
 * OpenAI is invoked only when the opportunity is valuable enough; coverage still
 * records no-change nodes as explored.
 */
public final class OpportunityDetector {

    public static final String GRAMMAR = "GRAMMAR";
    public static final String TENSE = "TENSE";
    public static final String CLARITY = "CLARITY";
    public static final String CONCISION = "CONCISION";
    public static final String PROFESSIONAL_LANGUAGE = "PROFESSIONAL_LANGUAGE";
    public static final String SPECIFICITY = "SPECIFICITY";
    public static final String JD_RELEVANCE = "JD_RELEVANCE";
    public static final String NO_VALUABLE_CHANGE = "NO_VALUABLE_CHANGE";
    public static final int MIN_SCORE = 40;

    private static final Set<String> PRESENT_TENSE = Set.of(
            "design", "implement", "develop", "build", "integrate", "create", "deploy"
    );
    private static final Pattern MANAGEMENTS = Pattern.compile("(?i)\\bmanagements\\b");
    private static final Pattern VAGUE_QUALITY = Pattern.compile(
            "(?i)\\b(?:to ensure the quality|ensure the quality|java doc string)\\b"
    );
    private static final Pattern WORDY = Pattern.compile(
            "(?i)\\b(?:exceptional|successfully|continuous thrive|highly motivated)\\b"
    );
    private static final Pattern INFORMAL = Pattern.compile(
            "(?i)\\b(?:kinda|gonna|stuff|things|a lot of)\\b"
    );

    private OpportunityDetector() {
    }

    public static List<Opportunity> scan(List<ResumeDocumentNode> nodes, String jobDescription) {
        List<Opportunity> opportunities = new ArrayList<>();
        if (nodes == null || nodes.isEmpty()) {
            return opportunities;
        }
        Set<String> allowed = ResumeTailorNodeGuard.allowedNodeIds(nodes);
        List<String> jobTerms = ConservativeTailorPlanner.extractTerms(jobDescription);
        Set<String> mustHave = ConservativeTailorPlanner.extractMustHaveTerms(jobDescription);
        int index = 0;
        for (ConservativeTailorPlanner.ClassifiedNode classified : ConservativeTailorPlanner.classify(nodes)) {
            ResumeDocumentNode node = classified.node();
            ResumeNodeRole role = classified.role();
            if (node == null || node.getNodeId() == null || !allowed.contains(node.getNodeId())) {
                index++;
                continue;
            }
            if (!isEligible(node.getText(), role)) {
                index++;
                continue;
            }
            opportunities.add(evaluate(node, role, jobTerms, mustHave, index));
            index++;
        }
        return opportunities;
    }

    public static List<String> eligibleNodeIds(List<ResumeDocumentNode> nodes) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        for (Opportunity opportunity : scan(nodes, "")) {
            ids.add(opportunity.nodeId());
        }
        return new ArrayList<>(ids);
    }

    public static boolean needsGeneration(Opportunity opportunity) {
        return opportunity != null
                && !NO_VALUABLE_CHANGE.equals(opportunity.type())
                && opportunity.score() >= MIN_SCORE;
    }

    static boolean isEligible(String text, ResumeNodeRole role) {
        if (role == ResumeNodeRole.HEADING
                || role == ResumeNodeRole.CONTACT
                || role == ResumeNodeRole.DATE
                || role == ResumeNodeRole.LINK
                || role == ResumeNodeRole.SKILLS) {
            return false;
        }
        if (role != ResumeNodeRole.EXPERIENCE_BULLET
                && role != ResumeNodeRole.PROJECT_BULLET
                && role != ResumeNodeRole.SUMMARY_BULLET
                && role != ResumeNodeRole.OTHER) {
            return false;
        }
        String value = text == null ? "" : text.strip();
        if (value.length() < ConservativeTailorPlanner.MIN_CANDIDATE_CHARS) {
            return false;
        }
        if (value.split("\\s+").length < ConservativeTailorPlanner.MIN_CANDIDATE_WORDS) {
            return false;
        }
        return !ConservativeTailorPlanner.isSkillList(value);
    }

    private static Opportunity evaluate(
            ResumeDocumentNode node,
            ResumeNodeRole role,
            List<String> jobTerms,
            Set<String> mustHave,
            int originalIndex) {

        String text = node.getText() == null ? "" : node.getText();
        String first = ConservativeTailorPlanner.firstWord(text);
        int tense = PRESENT_TENSE.contains(first) ? 85 : 0;
        int grammar = 0;
        if (MANAGEMENTS.matcher(text).find()) {
            grammar += 80;
        }
        if (VAGUE_QUALITY.matcher(text).find()) {
            grammar += 55;
        }
        int clarity = 0;
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("the application") && !lower.contains("spring boot") && role != ResumeNodeRole.SUMMARY_BULLET) {
            clarity += 20;
        }
        if (lower.contains("to handle") && lower.contains("managements")) {
            clarity += 25;
        }
        int concision = WORDY.matcher(text).find() ? 45 : 0;
        int professional = INFORMAL.matcher(text).find() ? 60 : 0;
        if (lower.contains("java doc string") || lower.contains("to ensure the quality")) {
            professional += 50;
        }
        int specificity = 0;
        if (role != ResumeNodeRole.SUMMARY_BULLET
                && !hasTech(lower)
                && ConservativeTailorPlanner.extractTerms(text).size() >= 8) {
            specificity += 20;
        }
        int jd = 0;
        for (String term : mustHave == null ? Set.<String>of() : mustHave) {
            if (term != null && lower.contains(term) && PRESENT_TENSE.contains(first)) {
                jd += 15;
            }
        }
        if (jd == 0 && jobTerms != null) {
            for (String term : jobTerms) {
                if (term != null && lower.contains(term) && tense > 0) {
                    jd += 8;
                    break;
                }
            }
        }
        int score = tense + grammar + clarity + concision + professional + specificity + jd;
        String type = dominantType(tense, grammar, clarity, concision, professional, specificity, jd);
        boolean expOrProject = role == ResumeNodeRole.EXPERIENCE_BULLET || role == ResumeNodeRole.PROJECT_BULLET;
        if (ConservativeTailorPlanner.alreadyWellWritten(text) && score < MIN_SCORE) {
            type = NO_VALUABLE_CHANGE;
        } else if (expOrProject && score < MIN_SCORE) {
            type = CLARITY;
            score = MIN_SCORE;
        } else if (score < MIN_SCORE) {
            type = NO_VALUABLE_CHANGE;
            score = Math.max(0, score);
        }
        return new Opportunity(
                node.getNodeId(),
                type,
                score,
                role,
                rewriteIntent(type, role),
                originalIndex
        );
    }

    private static boolean hasTech(String lower) {
        return lower.contains("java")
                || lower.contains("spring")
                || lower.contains("maven")
                || lower.contains("rest")
                || lower.contains("sql")
                || lower.contains("aws")
                || lower.contains("algorithm");
    }

    private static String dominantType(
            int tense,
            int grammar,
            int clarity,
            int concision,
            int professional,
            int specificity,
            int jd) {

        int best = 0;
        String type = NO_VALUABLE_CHANGE;
        if (tense > best) {
            best = tense;
            type = TENSE;
        }
        if (grammar > best) {
            best = grammar;
            type = GRAMMAR;
        }
        if (jd > best) {
            best = jd;
            type = JD_RELEVANCE;
        }
        if (clarity > best) {
            best = clarity;
            type = CLARITY;
        }
        if (specificity > best) {
            best = specificity;
            type = SPECIFICITY;
        }
        if (professional > best) {
            best = professional;
            type = PROFESSIONAL_LANGUAGE;
        }
        if (concision > best) {
            type = CONCISION;
        }
        return type;
    }

    static String rewriteIntent(String opportunityType, ResumeNodeRole role) {
        if (role == ResumeNodeRole.SUMMARY_BULLET) {
            return RewriteIntent.SUMMARY_REWRITE;
        }
        if (TENSE.equals(opportunityType) || GRAMMAR.equals(opportunityType)) {
            return RewriteIntent.GRAMMAR_FIX;
        }
        if (JD_RELEVANCE.equals(opportunityType)) {
            return RewriteIntent.JD_RELEVANCE;
        }
        return RewriteIntent.CLARITY_IMPROVEMENT;
    }

    public record Opportunity(
            String nodeId,
            String type,
            int score,
            ResumeNodeRole role,
            String rewriteIntent,
            int originalIndex
    ) {
    }
}
