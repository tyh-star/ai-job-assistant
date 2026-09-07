package aijob.util;

import aijob.dto.ResumeQualityCategoryScore;
import aijob.dto.ResumeQualityScoreResponse;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes a resume's OWN structure/content-completeness score -- rubric version
 * {@value aijob.dto.ResumeQualityScoreResponse#RUBRIC_VERSION} -- purely from the
 * resume's already-extracted plain text ({@code Resume.extractedText}, populated once
 * at upload for both PDF and DOCX; see ResumeServiceImpl#uploadResume). This is
 * DELIBERATELY the one input every successfully-uploaded resume already has, so scoring
 * never depends on whether PDF layout/structure classification has run, never re-parses
 * or rewrites the resume, and never needs a new database column or table.
 *
 * <p><b>This class must never call OpenAI, AiResumeService, ResumeStructureLlmService,
 * or any Analyze/Match/Tailor code path.</b> Every method here is a pure function of
 * the input string: same text in, same {@link ResumeQualityScoreResponse} out, always
 * -- no randomness, no wall-clock reads, no I/O, no network calls of any kind. That is
 * what makes "the same resume always gets the same score" true by construction rather
 * than by promise.
 *
 * <h2>The rubric (100 points total, five categories)</h2>
 * <ul>
 *   <li><b>STRUCTURE (20)</b> -- section completeness: contact info (5), an
 *       experience-OR-projects section (5), an education section (5), a skills section
 *       (5). Experience and projects count equally on purpose: a student with no
 *       full-time job but a solid projects section must not be penalized here.</li>
 *   <li><b>EXPERIENCE (30)</b> -- clarity of descriptive content anywhere in the resume
 *       (not gated on a formal "Experience" heading, for the same reason as above):
 *       10 points for having any descriptive lines at all, up to 10 for having a
 *       reasonable volume of them (capped, not linear -- more bullets past a healthy
 *       count earn nothing further), up to 10 for those lines being a healthy length
 *       (neither one-word fragments nor unbroken walls of text).</li>
 *   <li><b>ACHIEVEMENTS (20)</b> -- the fraction of descriptive lines that name a
 *       genuinely verifiable metric: a percentage, a dollar figure, a large/counted
 *       quantity of something ("10,000 users", "12-person team"), a rate ("5,000
 *       requests per second"), a before/after time reduction, or a multiplier. This is
 *       DELIBERATELY a positive-pattern allowlist, not "does this line contain a
 *       digit" -- a bare digit is exactly as likely to be a year, a version number, a
 *       phone number, a zip code, or a standard/course/certificate number as an actual
 *       achievement, and none of those name a verifiable result. See
 *       {@link #isQuantifiedAchievement(String)} for the exact patterns and why each
 *       false-positive category structurally cannot match any of them. A resume that
 *       legitimately has no numbers yet scores 0 only in THIS category, not overall --
 *       there is no floor removed elsewhere for lacking numbers.</li>
 *   <li><b>SKILLS (15)</b> -- whether a skills section exists and how many distinct
 *       skills it lists, on a TIERED scale that is flat past a reasonable count (6-15
 *       skills all score the same as 15). This is the anti-keyword-stuffing control:
 *       listing 60 buzzwords cannot outscore a focused, honest list.</li>
 *   <li><b>READABILITY (15)</b> -- up to 10 points for not having overlong bullets/paragraphs
 *       (a wall of text is hard to scan, regardless of content), up to 5 for using a
 *       single consistent date-range style throughout.</li>
 * </ul>
 *
 * <h2>What this rubric never does</h2>
 * Never looks for a photo. Never reads or infers age, gender, nationality, or marital
 * status (none of those are ever extracted or referenced). Never rewards keyword
 * density (SKILLS is tiered/capped, not linear in count; ACHIEVEMENTS and EXPERIENCE
 * are ratios/caps, not raw counts). Never frames the result as a hiring probability or
 * a match to any job -- that framing belongs entirely to the frontend copy, and this
 * class has no concept of "job" at all.
 */
@Component
public class ResumeQualityScorer {

    // -------------------------------------------------------------------------------
    // Category codes and point budget -- MUST sum to exactly 100.
    // -------------------------------------------------------------------------------
    public static final String CATEGORY_STRUCTURE = "STRUCTURE";
    public static final String CATEGORY_EXPERIENCE = "EXPERIENCE";
    public static final String CATEGORY_ACHIEVEMENTS = "ACHIEVEMENTS";
    public static final String CATEGORY_SKILLS = "SKILLS";
    public static final String CATEGORY_READABILITY = "READABILITY";

    private static final int MAX_STRUCTURE = 20;
    private static final int MAX_EXPERIENCE = 30;
    private static final int MAX_ACHIEVEMENTS = 20;
    private static final int MAX_SKILLS = 15;
    private static final int MAX_READABILITY = 15;

    static {
        int total = MAX_STRUCTURE + MAX_EXPERIENCE + MAX_ACHIEVEMENTS + MAX_SKILLS + MAX_READABILITY;
        if (total != 100) {
            // A rubric that silently stopped summing to 100 would misrepresent every
            // score it ever produced -- fail hard at class-load time, not quietly in
            // production.
            throw new IllegalStateException("ResumeQualityScorer rubric must sum to 100, was " + total);
        }
    }

    // -------------------------------------------------------------------------------
    // Reason codes for an unscorable resume.
    // -------------------------------------------------------------------------------
    public static final String REASON_TEXT_NOT_AVAILABLE = "TEXT_NOT_AVAILABLE";

    // -------------------------------------------------------------------------------
    // Level thresholds -- published, fixed boundaries (see levelFor's javadoc). These
    // apply ONLY to a scorable result; an unscorable resume is never leveled WEAK or
    // scored 0 -- see score()'s early return, which sets both score and level to null
    // and never reaches levelFor at all.
    // -------------------------------------------------------------------------------
    public static final String LEVEL_EXCELLENT = "EXCELLENT";
    public static final String LEVEL_GOOD = "GOOD";
    public static final String LEVEL_NEEDS_WORK = "NEEDS_WORK";
    public static final String LEVEL_WEAK = "WEAK";

    // -------------------------------------------------------------------------------
    // Strength / suggestion codes -- the frontend maps every one of these to i18n text.
    // -------------------------------------------------------------------------------
    public static final String STRENGTH_STRUCTURE_COMPLETE = "STRUCTURE_COMPLETE";
    public static final String STRENGTH_EXPERIENCE_WELL_DESCRIBED = "EXPERIENCE_WELL_DESCRIBED";
    public static final String STRENGTH_QUANTIFIED_ACHIEVEMENTS = "QUANTIFIED_ACHIEVEMENTS";
    public static final String STRENGTH_FOCUSED_SKILLS_LIST = "FOCUSED_SKILLS_LIST";
    public static final String STRENGTH_CONSISTENT_FORMATTING = "CONSISTENT_FORMATTING";

    public static final String SUGGESTION_ADD_CONTACT_INFO = "ADD_CONTACT_INFO";
    public static final String SUGGESTION_ADD_EXPERIENCE_OR_PROJECTS_SECTION = "ADD_EXPERIENCE_OR_PROJECTS_SECTION";
    public static final String SUGGESTION_ADD_EDUCATION_SECTION = "ADD_EDUCATION_SECTION";
    public static final String SUGGESTION_ADD_SKILLS_SECTION = "ADD_SKILLS_SECTION";
    public static final String SUGGESTION_ADD_MORE_DESCRIPTIVE_DETAIL = "ADD_MORE_DESCRIPTIVE_DETAIL";
    public static final String SUGGESTION_QUANTIFY_ACHIEVEMENTS = "QUANTIFY_ACHIEVEMENTS";
    public static final String SUGGESTION_EXPAND_SKILLS_LIST = "EXPAND_SKILLS_LIST";
    public static final String SUGGESTION_SHORTEN_LONG_BULLETS = "SHORTEN_LONG_BULLETS";
    public static final String SUGGESTION_USE_CONSISTENT_DATE_FORMAT = "USE_CONSISTENT_DATE_FORMAT";

    // -------------------------------------------------------------------------------
    // Detection patterns. Bilingual on purpose (English + Chinese section names) since
    // this platform serves both.
    // -------------------------------------------------------------------------------
    private static final int HEADER_MAX_LENGTH = 40;

    private static final List<String> EXPERIENCE_HEADER_KEYWORDS = List.of(
            "experience", "employment", "work history", "professional experience",
            "工作经历", "工作经验", "实习经历", "实习经验");
    private static final List<String> PROJECT_HEADER_KEYWORDS = List.of(
            "project", "projects", "项目经历", "项目经验");
    private static final List<String> EDUCATION_HEADER_KEYWORDS = List.of(
            "education", "academic background", "教育经历", "教育背景", "学历");
    private static final List<String> SKILLS_HEADER_KEYWORDS = List.of(
            "skills", "technical skills", "core competencies", "技能", "专业技能", "技能清单");

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    // Any run of 7+ digits (allowing common separators) -- deliberately loose so it
    // catches phone formats from more than one country, not just North American.
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(\\+?\\d[\\d .()-]{6,}\\d)");

    // -------------------------------------------------------------------------------
    // ACHIEVEMENTS quantification -- a POSITIVE allowlist, not "contains a digit". Each
    // pattern below is deliberately narrow enough that a year, a version number, a
    // phone number, a zip/postal code, or a standard/course/certificate number cannot
    // match it: none of those are ever followed by a %, a currency symbol, a
    // scale-noun, a rate unit, or a from-X-to-Y time reduction, which is exactly what
    // distinguishes an actual verifiable result from an incidental digit. See
    // isQuantifiedAchievement's javadoc for the full worked examples.
    // -------------------------------------------------------------------------------
    private static final Pattern PERCENT_PATTERN = Pattern.compile(
            "\\d+(\\.\\d+)?\\s*(%|percent|pct\\b)", Pattern.CASE_INSENSITIVE);

    private static final Pattern CURRENCY_PATTERN = Pattern.compile(
            "[$¥€£]\\s?\\d[\\d,]*(\\.\\d+)?"
                    + "|\\d[\\d,]*(\\.\\d+)?\\s*(dollars?|usd|美元|万元|元)\\b",
            Pattern.CASE_INSENSITIVE);

    // Nouns that turn a bare number into a stated scale of impact -- deliberately NOT
    // things like "version", "grade", "chapter", which is what keeps a version number
    // or a course code from ever matching this.
    private static final String SCALE_NOUNS =
            "users?|customers?|clients?|requests?|records?|transactions?|downloads?|"
                    + "subscribers?|employees?|reports?|tickets?|orders?|sessions?|rows?|people|"
                    + "engineers?|developers?|projects?|features?|meetings?|articles?|teams?|"
                    + "students?|visitors?|viewers?|readers?|members?|merchants?|vendors?|partners?|"
                    + "名用户|名客户|个用户|个请求|条记录|位员工|个项目|个功能|份报告|个客户|人团队|名员工|个任务|个功能点";

    // Up to two intervening adjectives are tolerated, joined by whitespace OR a
    // hyphen ("12 JUNIOR engineers", "500 ACTIVE users", "a 12-person team") -- real
    // sentences almost never put the noun directly next to the number. A bare 4-digit
    // year (1900-2099) is excluded from ever starting this match -- exactly the
    // false-positive category this fix exists for -- while "10,000" and "2,024" are
    // unaffected since neither is a bare 4-digit run. An optional k/m/b/K/M/B suffix
    // covers shorthand like "500k records".
    private static final Pattern SCALE_COUNT_PATTERN = Pattern.compile(
            "(?<!\\d)(?!(?:19|20)\\d{2}\\b)\\d[\\d,]*(\\.\\d+)?[kKmMbB]?\\+?"
                    + "(?:[\\s-]+[a-zA-Z]+){0,2}[\\s-]*(" + SCALE_NOUNS + ")",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TEAM_SIZE_PATTERN = Pattern.compile(
            "team of\\s*\\d+"
                    + "|\\d+\\s*人(的)?团队"
                    + "|团队.{0,4}\\d+\\s*人",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RATE_PATTERN = Pattern.compile(
            "\\d[\\d,]*(\\.\\d+)?\\s*(per|/)\\s*(second|sec|minute|min|hour|day|month|year)s?\\b"
                    + "|每(秒|分钟|小时|天|日|月|年).{0,6}\\d[\\d,]*"
                    + "|\\d[\\d,]*.{0,4}(个|次)?/\\s*(秒|分钟|小时|天)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TIME_REDUCTION_PATTERN = Pattern.compile(
            "from\\s+[\\d.]+\\s*(seconds?|ms|milliseconds?|minutes?|mins?|hours?|hrs?|days?)"
                    + "\\s+to\\s+[\\d.]+\\s*(seconds?|ms|milliseconds?|minutes?|mins?|hours?|hrs?|days?)"
                    + "|从\\s*[\\d.]+\\s*(秒|毫秒|分钟|小时|天)\\s*(降到|降至|减少到|缩短到|降低到|减到)"
                    + "\\s*[\\d.]+\\s*(秒|毫秒|分钟|小时|天)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MULTIPLIER_PATTERN =
            Pattern.compile("\\d+(\\.\\d+)?\\s*[xX×]\\b|\\d+(\\.\\d+)?\\s*倍");

    // A "date range" -- two year-like tokens (optionally with a month name/number)
    // joined by a dash/en-dash/"to"/"-". Captures the SEPARATOR STYLE so date-format
    // consistency can be judged without caring about the actual dates.
    private static final Pattern DATE_RANGE_PATTERN = Pattern.compile(
            "(?i)((?:19|20)\\d{2}|jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)"
                    + "[^\\n]{0,15}?"
                    + "(-|–|—|~|to| to )"
                    + "[^\\n]{0,15}?"
                    + "((?:19|20)\\d{2}|present|至今|现在)");

    private static final Pattern SKILL_SEPARATOR = Pattern.compile("[,，、|/;；]|\\n");

    /**
     * @param extractedText the resume's already-extracted plain text (may be null/blank
     *                       for an image-only scan with nothing to read)
     */
    public ResumeQualityScoreResponse score(String extractedText) {
        if (extractedText == null || extractedText.isBlank()) {
            return new ResumeQualityScoreResponse(
                    false, REASON_TEXT_NOT_AVAILABLE, null, null,
                    ResumeQualityScoreResponse.RUBRIC_VERSION,
                    List.of(), List.of(), List.of());
        }

        List<String> lines = splitNonBlankLines(extractedText);

        boolean hasContactInfo = EMAIL_PATTERN.matcher(extractedText).find()
                || PHONE_PATTERN.matcher(extractedText).find();
        boolean hasExperienceSection = anyHeaderMatches(lines, EXPERIENCE_HEADER_KEYWORDS);
        boolean hasProjectsSection = anyHeaderMatches(lines, PROJECT_HEADER_KEYWORDS);
        boolean hasEducationSection = anyHeaderMatches(lines, EDUCATION_HEADER_KEYWORDS);
        boolean hasSkillsSection = anyHeaderMatches(lines, SKILLS_HEADER_KEYWORDS);

        List<String> contentLines = contentLines(lines);
        int bulletCount = contentLines.size();
        long quantifiedCount = contentLines.stream().filter(ResumeQualityScorer::isQuantifiedAchievement).count();
        long shortCount = contentLines.stream().filter(ResumeQualityScorer::isTooShort).count();
        long overlongCount = contentLines.stream().filter(ResumeQualityScorer::isTooLong).count();

        Set<String> dateSeparatorStyles = new LinkedHashSet<>();
        Matcher dateMatcher = DATE_RANGE_PATTERN.matcher(extractedText);
        while (dateMatcher.find()) {
            dateSeparatorStyles.add(normalizeSeparator(dateMatcher.group(2)));
        }

        int skillCount = hasSkillsSection ? countSkills(extractedText) : 0;

        // --- STRUCTURE (20) ---
        int structureContact = hasContactInfo ? 5 : 0;
        int structureExperienceOrProjects = (hasExperienceSection || hasProjectsSection) ? 5 : 0;
        int structureEducation = hasEducationSection ? 5 : 0;
        int structureSkills = hasSkillsSection ? 5 : 0;
        int structureScore = structureContact + structureExperienceOrProjects + structureEducation + structureSkills;

        // --- EXPERIENCE (30) ---
        int hasContentScore = bulletCount > 0 ? 10 : 0;
        int volumeScore = Math.min(10, bulletCount * 2);
        int qualityScore = bulletCount == 0 ? 0
                : clamp(10 - (int) Math.min(5, shortCount) - (int) Math.min(5, overlongCount), 0, 10);
        int experienceScore = hasContentScore + volumeScore + qualityScore;

        // --- ACHIEVEMENTS (20) ---
        int achievementsScore = bulletCount == 0 ? 0
                : clamp(Math.round(MAX_ACHIEVEMENTS * (quantifiedCount / (float) bulletCount)), 0, MAX_ACHIEVEMENTS);

        // --- SKILLS (15) ---
        int skillsScore = skillsTierScore(hasSkillsSection, skillCount);

        // --- READABILITY (15) ---
        int lengthScore = clamp(10 - (int) Math.min(10, overlongCount * 2), 0, 10);
        int dateConsistencyScore = dateSeparatorStyles.size() <= 1
                ? 5
                : clamp(5 - (dateSeparatorStyles.size() - 1) * 2, 0, 5);
        int readabilityScore = lengthScore + dateConsistencyScore;

        List<ResumeQualityCategoryScore> categories = List.of(
                new ResumeQualityCategoryScore(CATEGORY_STRUCTURE, structureScore, MAX_STRUCTURE),
                new ResumeQualityCategoryScore(CATEGORY_EXPERIENCE, experienceScore, MAX_EXPERIENCE),
                new ResumeQualityCategoryScore(CATEGORY_ACHIEVEMENTS, achievementsScore, MAX_ACHIEVEMENTS),
                new ResumeQualityCategoryScore(CATEGORY_SKILLS, skillsScore, MAX_SKILLS),
                new ResumeQualityCategoryScore(CATEGORY_READABILITY, readabilityScore, MAX_READABILITY));

        int totalScore = categories.stream().mapToInt(ResumeQualityCategoryScore::getScore).sum();

        List<String> strengths = new ArrayList<>();
        if (structureScore >= (int) Math.ceil(MAX_STRUCTURE * 0.8)) {
            strengths.add(STRENGTH_STRUCTURE_COMPLETE);
        }
        if (experienceScore >= (int) Math.ceil(MAX_EXPERIENCE * 0.8)) {
            strengths.add(STRENGTH_EXPERIENCE_WELL_DESCRIBED);
        }
        if (achievementsScore >= (int) Math.ceil(MAX_ACHIEVEMENTS * 0.8)) {
            strengths.add(STRENGTH_QUANTIFIED_ACHIEVEMENTS);
        }
        if (skillsScore >= (int) Math.ceil(MAX_SKILLS * 0.8)) {
            strengths.add(STRENGTH_FOCUSED_SKILLS_LIST);
        }
        if (readabilityScore >= (int) Math.ceil(MAX_READABILITY * 0.8)) {
            strengths.add(STRENGTH_CONSISTENT_FORMATTING);
        }

        // Suggestions, paired with a priority weight (the point gap they represent) so
        // the most impactful fix is always first -- the frontend shows the top 3.
        List<WeightedSuggestion> weighted = new ArrayList<>();
        if (!hasContactInfo) {
            weighted.add(new WeightedSuggestion(SUGGESTION_ADD_CONTACT_INFO, 5));
        }
        if (!hasExperienceSection && !hasProjectsSection) {
            weighted.add(new WeightedSuggestion(SUGGESTION_ADD_EXPERIENCE_OR_PROJECTS_SECTION, 5));
        }
        if (!hasEducationSection) {
            weighted.add(new WeightedSuggestion(SUGGESTION_ADD_EDUCATION_SECTION, 5));
        }
        if (!hasSkillsSection) {
            weighted.add(new WeightedSuggestion(SUGGESTION_ADD_SKILLS_SECTION, 5));
        } else if (skillCount < 3) {
            weighted.add(new WeightedSuggestion(SUGGESTION_EXPAND_SKILLS_LIST, MAX_SKILLS - skillsScore));
        }
        if (experienceScore < MAX_EXPERIENCE * 0.6) {
            weighted.add(new WeightedSuggestion(SUGGESTION_ADD_MORE_DESCRIPTIVE_DETAIL, MAX_EXPERIENCE - experienceScore));
        }
        if (achievementsScore < MAX_ACHIEVEMENTS * 0.4) {
            weighted.add(new WeightedSuggestion(SUGGESTION_QUANTIFY_ACHIEVEMENTS, MAX_ACHIEVEMENTS - achievementsScore));
        }
        if (overlongCount > 0) {
            weighted.add(new WeightedSuggestion(SUGGESTION_SHORTEN_LONG_BULLETS, (int) overlongCount));
        }
        if (dateSeparatorStyles.size() > 1) {
            weighted.add(new WeightedSuggestion(SUGGESTION_USE_CONSISTENT_DATE_FORMAT, dateSeparatorStyles.size()));
        }
        List<String> suggestions = weighted.stream()
                .sorted((a, b) -> Integer.compare(b.weight, a.weight))
                .map(w -> w.code)
                .toList();

        return new ResumeQualityScoreResponse(
                true, null, totalScore, levelFor(totalScore),
                ResumeQualityScoreResponse.RUBRIC_VERSION,
                categories, strengths, suggestions);
    }

    private record WeightedSuggestion(String code, int weight) {
    }

    /**
     * Published, fixed boundaries -- 85-100 EXCELLENT, 70-84 GOOD, 50-69 NEEDS_WORK,
     * 0-49 WEAK. Package-private (not private) specifically so
     * {@code ResumeQualityScorerTest} can assert every boundary value (49/50, 69/70,
     * 84/85, and 100) directly, rather than reverse-engineering resume text that
     * happens to produce an exact score.
     */
    static String levelFor(int score) {
        if (score >= 85) {
            return LEVEL_EXCELLENT;
        }
        if (score >= 70) {
            return LEVEL_GOOD;
        }
        if (score >= 50) {
            return LEVEL_NEEDS_WORK;
        }
        return LEVEL_WEAK;
    }

    private static List<String> splitNonBlankLines(String text) {
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\r?\\n")) {
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    private static boolean isHeaderLine(String line, List<String> keywords) {
        if (line.length() > HEADER_MAX_LENGTH) {
            return false;
        }
        // A real section heading is a short label ("PROJECTS", "Project Experience",
        // "工作经历"), never a full sentence -- without this, a genuine achievement
        // bullet like "Completed 20 projects ahead of schedule" would be misread as a
        // "Projects" heading purely for containing that word, and silently dropped
        // from every content-line-based category (EXPERIENCE, ACHIEVEMENTS,
        // READABILITY) instead of being scored.
        if (wordCount(line) > 4) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyHeaderMatches(List<String> lines, List<String> keywords) {
        for (String line : lines) {
            if (isHeaderLine(line, keywords)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAnyHeaderLine(String line) {
        return isHeaderLine(line, EXPERIENCE_HEADER_KEYWORDS)
                || isHeaderLine(line, PROJECT_HEADER_KEYWORDS)
                || isHeaderLine(line, EDUCATION_HEADER_KEYWORDS)
                || isHeaderLine(line, SKILLS_HEADER_KEYWORDS);
    }

    /**
     * Non-header lines of a plausible descriptive length -- this intentionally counts
     * content ANYWHERE in the resume (education details, project bullets, a summary),
     * not only under a formal "Experience" heading, so a student resume with no
     * full-time job is judged on the descriptive content it actually has.
     */
    private static List<String> contentLines(List<String> lines) {
        List<String> content = new ArrayList<>();
        for (String line : lines) {
            if (isAnyHeaderLine(line)) {
                continue;
            }
            int wordCount = wordCount(line);
            if (wordCount >= 2 && line.length() <= 500) {
                content.add(line);
            }
        }
        return content;
    }

    /**
     * True only for a line naming a genuinely verifiable metric -- never merely "this
     * line contains a digit". Matched positive examples: "improved by 30%", "served
     * 10,000 users", "response time from 2 seconds to 500 milliseconds", "saved
     * $50,000", "managed a 12-person team" / "12人团队", "completed 20 projects" /
     * "完成20个项目", "handled 5,000 requests per second" / "每秒处理5,000个请求".
     * Deliberately NOT matched (no positive pattern below fires for any of these,
     * because none of them are followed by a %, currency symbol, scale-noun, rate
     * unit, or time-reduction phrase): a bare year ("2024", "2025"), a slash-date
     * ("01/2025"), a phone number, a zip/postal code, a version number ("Java 17",
     * "Python 3", "React 18"), a standard number ("ISO 27001"), or a course/certificate
     * number.
     */
    private static boolean isQuantifiedAchievement(String line) {
        return PERCENT_PATTERN.matcher(line).find()
                || CURRENCY_PATTERN.matcher(line).find()
                || SCALE_COUNT_PATTERN.matcher(line).find()
                || TEAM_SIZE_PATTERN.matcher(line).find()
                || RATE_PATTERN.matcher(line).find()
                || TIME_REDUCTION_PATTERN.matcher(line).find()
                || MULTIPLIER_PATTERN.matcher(line).find();
    }

    private static boolean isTooShort(String line) {
        return wordCount(line) < 4;
    }

    private static boolean isTooLong(String line) {
        return wordCount(line) > 40 || line.length() > 220;
    }

    private static int wordCount(String line) {
        String[] words = line.trim().split("\\s+");
        return words.length == 1 && words[0].isEmpty() ? 0 : words.length;
    }

    private static String normalizeSeparator(String rawSeparator) {
        String trimmed = rawSeparator.trim().toLowerCase(Locale.ROOT);
        if (trimmed.equals("to")) {
            return "to";
        }
        // en-dash / em-dash / tilde / hyphen are grouped as "dash family" -- resumes
        // routinely mix these interchangeably even when otherwise consistent, and
        // treating them as different "styles" would flag noise, not a real
        // inconsistency.
        return "-";
    }

    private static int countSkills(String text) {
        int skillsIndex = -1;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : SKILLS_HEADER_KEYWORDS) {
            int idx = lower.indexOf(keyword);
            if (idx >= 0 && (skillsIndex == -1 || idx < skillsIndex)) {
                skillsIndex = idx;
            }
        }
        if (skillsIndex < 0) {
            return 0;
        }
        String afterHeader = text.substring(skillsIndex);
        // Stop at the next recognized section header, if any, so skills counting
        // never bleeds into the next section's own content.
        List<String> afterLines = splitNonBlankLines(afterHeader);
        StringBuilder block = new StringBuilder();
        boolean skippedHeaderLine = false;
        for (String line : afterLines) {
            if (!skippedHeaderLine) {
                skippedHeaderLine = true; // the header line itself
                continue;
            }
            if (isHeaderLine(line, EXPERIENCE_HEADER_KEYWORDS)
                    || isHeaderLine(line, PROJECT_HEADER_KEYWORDS)
                    || isHeaderLine(line, EDUCATION_HEADER_KEYWORDS)) {
                break;
            }
            block.append(line).append('\n');
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : SKILL_SEPARATOR.split(block.toString())) {
            String cleaned = token.trim();
            if (!cleaned.isEmpty() && cleaned.length() <= 40) {
                tokens.add(cleaned.toLowerCase(Locale.ROOT));
            }
        }
        return tokens.size();
    }

    private static int skillsTierScore(boolean hasSkillsSection, int skillCount) {
        if (!hasSkillsSection) {
            return 0;
        }
        if (skillCount == 0) {
            return 2;
        }
        if (skillCount <= 2) {
            return 6;
        }
        if (skillCount <= 5) {
            return 11;
        }
        return MAX_SKILLS;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
