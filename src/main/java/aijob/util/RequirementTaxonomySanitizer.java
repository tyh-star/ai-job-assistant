package aijob.util;

import aijob.dto.JobRequirementDraft;
import aijob.dto.RequirementCategory;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server-side category for job requirements. The LLM category is a candidate
 * only; this classifier uses credential / duty / skill-frame / soft-skill
 * signals that are occupation-agnostic.
 */
public final class RequirementTaxonomySanitizer {

    private static final Pattern YEARS_OF_EXPERIENCE = Pattern.compile(
            "(?i)(?:\\d+\\s*\\+?\\s*years?|years?'?\\s+(?:of\\s+)?(?:professional\\s+|relevant\\s+|industry\\s+)?experience|minimum\\s+of\\s+\\d+)"
    );
    private static final Pattern EDUCATION = Pattern.compile(
            "(?i)\\b(?:bachelor'?s?|master'?s?|\\bm\\.?s\\.?\\b|\\bb\\.?s\\.?\\b|\\bb\\.?sc\\.?\\b|"
                    + "phd|ph\\.?d\\.?|doctorate|diploma|degree|major|university|college|"
                    + "high school|ged)\\b"
                    + "|\\bpursuing\\b.{0,80}\\b(?:degree|diploma|bachelor|master|phd)"
    );
    private static final Pattern CERTIFICATION = Pattern.compile(
            "(?i)\\b(?:certification|certifications|certificate|certificates|certified|"
                    + "license|licenses|licensed|licensure)\\b"
    );
    private static final Pattern AUTHORIZATION = Pattern.compile(
            "(?i)\\b(?:authorized to work|work authorization|legally authorized|"
                    + "eligible to work|security clearance|clearance required|"
                    + "citizenship|citizen|visa|work permit)\\b"
    );
    private static final Pattern LOCATION_ELIGIBILITY = Pattern.compile(
            "(?i)\\b(?:willing to (?:relocate|travel)|travel required|must (?:live|be located|be based)|"
                    + "on-?site in|relocation required)\\b"
    );
    private static final Pattern SKILL_FRAME = Pattern.compile(
            "(?i)\\b(?:experience with|experience in|knowledge of|proficiency in|proficient in|"
                    + "familiar(?:ity)? with|hands-?on (?:with|experience)|skilled in|working knowledge of)\\b"
    );
    private static final Pattern DUTY_VERB = Pattern.compile(
            "(?i)\\b(?:build|building|design|designing|develop|developing|implement|implementing|"
                    + "debug|debugging|test|testing|tested|document|documenting|documented|"
                    + "optimize|optimizing|deploy|deploying|deployed|maintain|maintaining|"
                    + "support|supporting|collaborate|collaborating|review|reviewing|"
                    + "operate|operating|integrate|integrating|write|writing|deliver|delivering|"
                    + "monitor|monitoring|troubleshoot|troubleshooting|refactor|migrate|"
                    + "configure|automate)\\b"
    );
    private static final Pattern QUALITY_CODE = Pattern.compile(
            "(?i)\\b(?:clean|well-?documented|documented|thoroughly tested|tested)\\b"
                    + ".{0,40}\\b(?:code|application|service|software)\\b"
                    + "|\\b(?:code|application|service|software)\\b.{0,40}"
                    + "\\b(?:clean|well-?documented|documented|tested)\\b"
    );
    private static final Pattern NATURAL_LANGUAGE = Pattern.compile(
            "(?i)\\b(?:fluent|native|proficiency) in\\s+"
                    + "(?:english|french|spanish|mandarin|cantonese|german|japanese|korean|"
                    + "hindi|arabic|portuguese|italian|russian|dutch|polish|turkish|"
                    + "vietnamese|thai|indonesian|swedish|norwegian|danish|finnish|"
                    + "greek|hebrew|persian|urdu|bengali|tamil|ukrainian|czech|"
                    + "hungarian|romanian)\\b"
    );
    private static final Pattern TECH_SHAPE = Pattern.compile(
            "[+#.]|\\d|[A-Z]{2,6}(?:\\b|$)|[a-z]+[A-Z][a-zA-Z]+"
    );
    private static final Pattern FILLER = Pattern.compile(
            "(?i)\\b(?:strong|excellent|proven|good|solid|ability|abilities|skills?|"
                    + "including|include|using|use|the|and|or|of|to|a|an|in|on|at|as|by|"
                    + "with|for|from|that|this|their|our|your)\\b"
    );

    private static final Set<String> SOFT_TOKENS = Set.of(
            "communication",
            "teamwork",
            "leadership",
            "analytical",
            "interpersonal",
            "written",
            "verbal",
            "ownership",
            "attitude",
            "mindset",
            "collaboration",
            "collaborative",
            "passionate",
            "motivated",
            "eager",
            "detail",
            "oriented",
            "time",
            "management",
            "problem",
            "solving",
            "listen",
            "listening",
            "empathy",
            "adaptability",
            "adaptable"
    );
    private static final Set<String> DUTY_STEMS = Set.of(
            "build", "design", "develop", "implement", "debug", "test", "document",
            "optimize", "deploy", "maintain", "support", "collaborate", "review",
            "operate", "integrate", "write", "deliver", "monitor", "troubleshoot"
    );

    private RequirementTaxonomySanitizer() {
    }

    public static void apply(List<JobRequirementDraft> drafts) {
        if (drafts == null) {
            return;
        }
        for (JobRequirementDraft draft : drafts) {
            if (draft == null || isBlank(draft.getRequirement())) {
                continue;
            }
            draft.setCategory(classify(draft.getRequirement(), draft.getExactTerms(), draft.getCategory()));
        }
    }

    private static final Set<String> FLUFF_TOKENS = Set.of(
            "nice",
            "plus",
            "bonus",
            "preferred",
            "additional",
            "extra",
            "various",
            "related",
            "field",
            "role",
            "position",
            "candidate",
            "applicant",
            "have",
            "had",
            "has"
    );

    public static String classify(String requirement, List<String> exactTerms, String ignoredLlmCategory) {
        String original = requirement == null ? "" : requirement.replace('’', '\'').replace('‘', '\'');
        String text = haystack(requirement, exactTerms);
        if (text.isBlank()) {
            return RequirementCategory.OTHER_KEYWORD;
        }
        if (isQualification(text)) {
            return RequirementCategory.QUALIFICATION;
        }
        if (isSkillFrame(text)) {
            return RequirementCategory.HARD_SKILL;
        }
        if (isPureSoftSkill(text)) {
            return RequirementCategory.SOFT_SKILL;
        }
        if (isResponsibility(text)) {
            return RequirementCategory.RESPONSIBILITY;
        }
        if (isTechnical(original, text, exactTerms)) {
            return RequirementCategory.HARD_SKILL;
        }
        return RequirementCategory.OTHER_KEYWORD;
    }

    private static boolean isQualification(String text) {
        return YEARS_OF_EXPERIENCE.matcher(text).find()
                || EDUCATION.matcher(text).find()
                || CERTIFICATION.matcher(text).find()
                || AUTHORIZATION.matcher(text).find()
                || LOCATION_ELIGIBILITY.matcher(text).find()
                || NATURAL_LANGUAGE.matcher(text).find();
    }

    private static boolean isSkillFrame(String text) {
        return SKILL_FRAME.matcher(text).find() && !YEARS_OF_EXPERIENCE.matcher(text).find();
    }

    private static boolean isPureSoftSkill(String text) {
        String stripped = FILLER.matcher(text).replaceAll(" ");
        boolean sawSoft = false;
        for (String token : tokens(stripped)) {
            if (token.length() < 3) {
                continue;
            }
            String stem = ExactTermPolicy.stem(token);
            if (SOFT_TOKENS.contains(token) || SOFT_TOKENS.contains(stem)) {
                sawSoft = true;
                continue;
            }
            if (DUTY_STEMS.contains(stem)) {
                return false;
            }
            if (ExactTermPolicy.isMeaningfulToken(token) && !FLUFF_TOKENS.contains(token)) {
                return false;
            }
        }
        return sawSoft;
    }

    private static boolean isResponsibility(String text) {
        if (QUALITY_CODE.matcher(text).find()) {
            return true;
        }
        return DUTY_VERB.matcher(text).find();
    }

    private static boolean isTechnical(String original, String text, List<String> exactTerms) {
        if (exactTerms != null) {
            for (String term : exactTerms) {
                if (isBlank(term) || ExactTermPolicy.isLowInformation(term)) {
                    continue;
                }
                String normalized = EvidenceText.normalize(term);
                if (DUTY_STEMS.contains(ExactTermPolicy.stem(normalized))
                        || SOFT_TOKENS.contains(normalized)
                        || FLUFF_TOKENS.contains(normalized)) {
                    continue;
                }
                return true;
            }
        }
        if (original != null && TECH_SHAPE.matcher(original).find()) {
            return true;
        }
        for (String token : tokens(text)) {
            if (token.length() < 3 || FLUFF_TOKENS.contains(token)) {
                continue;
            }
            if (SOFT_TOKENS.contains(token) || DUTY_STEMS.contains(ExactTermPolicy.stem(token))) {
                continue;
            }
            if (ExactTermPolicy.isMeaningfulToken(token)) {
                return true;
            }
        }
        return false;
    }

    private static String haystack(String requirement, List<String> exactTerms) {
        StringBuilder builder = new StringBuilder();
        if (requirement != null) {
            builder.append(requirement).append(' ');
        }
        if (exactTerms != null) {
            for (String term : exactTerms) {
                if (!isBlank(term)) {
                    builder.append(term).append(' ');
                }
            }
        }
        return EvidenceText.normalize(builder.toString().replace('’', '\'').replace('‘', '\''));
    }

    private static List<String> tokens(String text) {
        return List.of(EvidenceText.normalize(text).split("[^a-z0-9+#]+"));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
