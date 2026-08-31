package aijob.util;

import aijob.dto.RejectedTailorChange;
import aijob.dto.UpliftType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic CONSERVATIVE quality gate. Runs before Writer.
 */
public final class ConservativeRewriteQualityEvaluator {

    public static final int MIN_FACTUAL_SCORE = 60;

    private static final Pattern BULLET = Pattern.compile("^[\\s]*[●•▪◦‣∙·\\-*]\\s*");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9+#\\./]{1,}");
    private static final Pattern JOB_VOICE = Pattern.compile(
            "(?i)^(you will|must |required to|responsibilities include|the candidate)\\b"
    );
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "are", "was",
            "were", "have", "has", "had", "using", "used", "use", "into", "a", "an"
    );
    private static final Map<String, String> LOW_VALUE_SYNONYMS = Map.ofEntries(
            Map.entry("including", "include"),
            Map.entry("include", "include"),
            Map.entry("focusing", "include"),
            Map.entry("focused", "include"),
            Map.entry("utilize", "use"),
            Map.entry("utilizing", "use"),
            Map.entry("leverage", "use"),
            Map.entry("leveraging", "use"),
            Map.entry("help", "help"),
            Map.entry("helps", "help"),
            Map.entry("assist", "help"),
            Map.entry("assists", "help")
    );
    private static final Set<String> SYNONYM_FILLERS = Set.of(
            "on", "of", "to", "a", "an", "the", "and", "for", "with", "from"
    );
    private static final Set<String> LIGHT_VERBS = Set.of(
            "handle", "handles", "handling", "ensure", "ensures", "ensuring"
    );
    private static final Set<String> GENERIC_CREATED = Set.of("created", "create", "made", "make");
    private static final Set<String> PAST_VERBS = Set.of(
            "developed", "implemented", "built", "designed", "created",
            "integrated", "engineered", "delivered", "configured", "enhanced"
    );

    private ConservativeRewriteQualityEvaluator() {
    }

    public static Verdict evaluate(String originalText, String newText, String jobDescription) {
        return evaluate(originalText, newText, jobDescription, false);
    }

    public static Verdict evaluate(
            String originalText,
            String newText,
            String jobDescription,
            boolean sameEntryLeadershipCited) {
        String original = collapseSpace(stripBullet(originalText)).toLowerCase(Locale.ROOT);
        String rewritten = collapseSpace(stripBullet(newText)).toLowerCase(Locale.ROOT);
        if (original.isEmpty() || rewritten.isEmpty()) {
            return Verdict.reject(RejectedTailorChange.EMPTY_REQUIRED_FIELD, 0);
        }
        if (original.equals(rewritten)) {
            return Verdict.reject(RejectedTailorChange.TEXT_UNCHANGED, 0);
        }
        if (!sameEntryLeadershipCited && inflatesSeniority(originalText, newText)) {
            return Verdict.reject(RejectedTailorChange.ROLE_SEMANTIC_ESCALATION, 10);
        }
        if (replacesDeployWithMaintain(originalText, newText)) {
            return Verdict.reject(RejectedTailorChange.LOW_VALUE_REWRITE, 10);
        }
        if (upgradesAgileKnowledgeToCollaboration(originalText, newText)) {
            return Verdict.reject(RejectedTailorChange.LOW_VALUE_REWRITE, 10);
        }
        if (addsOnlyVaguePhrases(originalText, newText)) {
            return Verdict.reject(RejectedTailorChange.LOW_VALUE_REWRITE, 15);
        }
        if (!SurfaceRewriteEquivalence.hasValuableGrammarFix(originalText, newText)
                && (isTrivialSurfaceChange(originalText, newText)
                || SurfaceRewriteEquivalence.isLowValueSurface(originalText, newText))) {
            return Verdict.reject(RejectedTailorChange.LOW_VALUE_REWRITE, 20);
        }
        if (weakensSpecificVerbs(originalText, newText)) {
            return Verdict.reject(RejectedTailorChange.LOW_VALUE_REWRITE, 15);
        }
        if (losesCoreActions(originalText, newText)) {
            return Verdict.reject(RejectedTailorChange.INFORMATION_LOSS, 15);
        }
        if (JOB_VOICE.matcher(stripBullet(newText)).find()) {
            return Verdict.reject(RejectedTailorChange.LOW_VALUE_REWRITE, 20);
        }
        if (losesSkillTokens(originalText, newText)) {
            return Verdict.reject(RejectedTailorChange.SKILL_REMOVAL_NOT_ALLOWED, 10);
        }
        if (losesImportantTech(originalText, newText)) {
            return Verdict.reject(RejectedTailorChange.INFORMATION_LOSS, 15);
        }
        if (isInformationLoss(originalText, newText)) {
            return Verdict.reject(RejectedTailorChange.INFORMATION_LOSS, 20);
        }
        if (isKeywordStuffing(originalText, newText, jobDescription)) {
            return Verdict.reject(RejectedTailorChange.KEYWORD_STUFFING, 20);
        }
        if (hasBrokenGrammar(newText)) {
            return Verdict.reject(RejectedTailorChange.GRAMMAR_QUALITY_FAILED, 20);
        }
        boolean grammarOnly = isLightGrammarChange(originalText, newText);
        if (!grammarOnly
                && (isSingleSynonymChange(original, rewritten) || isSynonymBagChange(original, rewritten))) {
            return Verdict.reject(RejectedTailorChange.LOW_VALUE_REWRITE, 30);
        }

        QualityScores scores = score(originalText, newText, jobDescription);
        if (scores.factualPreservationScore() < MIN_FACTUAL_SCORE) {
            return Verdict.reject(RejectedTailorChange.INFORMATION_LOSS, scores.factualPreservationScore());
        }
        String uplift = classifyUplift(originalText, newText);
        if (UpliftType.LOW_VALUE.equals(uplift)) {
            return Verdict.reject(RejectedTailorChange.LOW_VALUE_REWRITE, 25);
        }
        if (!hasSignificantUplift(originalText, newText, uplift)) {
            return Verdict.reject(RejectedTailorChange.LOW_VALUE_REWRITE, 25);
        }
        return Verdict.accept(scores, uplift);
    }

    public static String upliftOf(String originalText, String newText) {
        return classifyUplift(originalText, newText);
    }

    public static boolean isGrammarOnly(String originalText, String newText) {
        return isLightGrammarChange(originalText, newText);
    }

    public static QualityScores score(String originalText, String newText, String jobDescription) {
        String firstOriginal = ConservativeTailorPlanner.firstWord(originalText);
        String firstNew = ConservativeTailorPlanner.firstWord(newText);
        int action = PAST_VERBS.contains(firstNew) ? 80 : 50;
        if (!firstOriginal.equals(firstNew) && PAST_VERBS.contains(firstNew)) {
            action = 95;
        }
        int grammar = hasBrokenGrammar(newText) ? 20 : (firstNew.endsWith("ed") || PAST_VERBS.contains(firstNew) ? 85 : 65);
        int clarity = Math.min(100, 55 + Math.abs(tokenList(newText).size() - tokenList(originalText).size()) * -3
                + (stripBullet(newText).contains(",") ? 8 : 0)
                + (newText.toLowerCase(Locale.ROOT).contains("using") ? 5 : 0));
        clarity = Math.max(30, clarity);
        Set<String> originalTech = ConservativeEvidencePacket.extractNormalizedTech(originalText);
        Set<String> kept = new LinkedHashSet<>(originalTech);
        kept.retainAll(ConservativeEvidencePacket.extractNormalizedTech(newText));
        int specificity = originalTech.isEmpty() ? 70 : (int) Math.round(100.0 * kept.size() / originalTech.size());
        int lengthOriginal = Math.max(1, stripBullet(originalText).length());
        int lengthNew = stripBullet(newText).length();
        double ratio = lengthNew / (double) lengthOriginal;
        int conciseness = ratio > 1.2 ? 50 : ratio < 0.7 ? 55 : 85;
        List<String> jobTerms = ConservativeTailorPlanner.extractTerms(jobDescription);
        Set<String> originalTerms = new LinkedHashSet<>(ConservativeTailorPlanner.extractTerms(originalText));
        int jobHits = 0;
        for (String term : jobTerms) {
            if (originalTerms.contains(term) && normalizeComparable(newText).contains(term)) {
                jobHits++;
            }
        }
        int jobRelevance = Math.min(90, 40 + jobHits * 12);
        int factual = originalTech.isEmpty() ? 90 : (int) Math.round(100.0 * kept.size() / originalTech.size());
        return new QualityScores(grammar, clarity, action, specificity, conciseness, jobRelevance, factual);
    }

    private static final Set<String> FILLER_STEMS = Set.of(
            "conduct", "ensur", "assur", "well", "comprehens"
    );
    private static final Map<String, String> GRAMMAR_EQUIVALENTS = Map.ofEntries(
            Map.entry("management", "manag"),
            Map.entry("manag", "manag"),
            Map.entry("operation", "manag"),
            Map.entry("operat", "manag"),
            Map.entry("support", "handle"),
            Map.entry("handl", "handle"),
            Map.entry("handle", "handle")
    );

    private static boolean isWordOrderOnlyChange(String originalText, String newText) {
        List<String> left = dropFiller(contentStems(originalText));
        List<String> right = dropFiller(contentStems(newText));
        if (left.size() < 4) {
            return false;
        }
        if (!new LinkedHashSet<>(left).equals(new LinkedHashSet<>(right))) {
            return false;
        }
        return !left.equals(right);
    }

    private static final Set<String> IC_VERBS = Set.of(
            "developed", "develop", "implemented", "implement", "built", "build",
            "coded", "code", "programmed", "program", "wrote", "write",
            "designed", "design", "integrated", "integrate", "created", "create"
    );
    private static final Set<String> LEADERSHIP_VERBS = Set.of(
            "led", "lead", "directed", "direct", "managed", "manage",
            "oversaw", "oversee", "owned", "own", "headed", "head",
            "supervised", "supervise", "spearheaded", "spearhead"
    );
    private static final Set<String> LEADING_ADVERBS = Set.of(
            "successfully", "effectively", "independently", "actively",
            "proactively", "closely"
    );
    private static final Set<String> VAGUE_TOKENS = Set.of(
            "best", "practices", "practice", "high-quality", "quality",
            "cross-functional", "cross", "functional", "end-to-end",
            "scalable", "solutions", "solution", "various", "multiple",
            "effectively", "successfully", "stakeholders", "stakeholder",
            "robust", "seamless", "cutting-edge", "cutting", "edge",
            "comprehensive", "proven", "ability", "excellent", "strong",
            "team", "environment", "leverage", "leveraging", "utilize",
            "utilizing", "well-versed", "versed", "state-of-the-art"
    );
    private static final Pattern AGILE_KNOWLEDGE = Pattern.compile(
            "(?i)\\b(?:knowledge of|understanding of|familiar(?:ity)? with)\\s+agile\\b"
                    + "|\\bagile\\s+(?:knowledge|understanding)\\b"
    );
    private static final Pattern AGILE_COLLABORATION = Pattern.compile(
            "(?i)\\bagile\\s+collaboration\\b"
                    + "|\\bcollaborat(?:e|ed|ing|ion)\\b[^.]{0,40}\\bagile\\b"
                    + "|\\bagile\\b[^.]{0,40}\\bcollaborat(?:e|ed|ing|ion)\\b"
    );

    private static final Pattern LEADERSHIP_CREATION = Pattern.compile(
            "(?i)\\b(?:directed|led|oversaw|managed)\\s+the\\s+creation\\b"
    );

    public static boolean hasRoleEscalation(String originalText, String newText) {
        return inflatesSeniority(originalText, newText);
    }

    private static boolean inflatesSeniority(String originalText, String newText) {
        if (LEADERSHIP_CREATION.matcher(newText == null ? "" : newText).find()
                && !LEADERSHIP_CREATION.matcher(originalText == null ? "" : originalText).find()) {
            return true;
        }
        String originalLead = leadingAction(originalText);
        String newLead = leadingAction(newText);
        if (!LEADERSHIP_VERBS.contains(originalLead) && LEADERSHIP_VERBS.contains(newLead)) {
            return true;
        }
        Set<String> originalVerbs = verbTokens(originalText);
        if (originalVerbs.stream().noneMatch(LEADERSHIP_VERBS::contains)
                && hasRoleObjectLeadership(newText)
                && !hasRoleObjectLeadership(originalText)) {
            return true;
        }
        return false;
    }

    private static final Pattern ROLE_OBJECT_LEADERSHIP = Pattern.compile(
            "(?i)\\b(?:led|lead|directed|direct|managed|manage|oversaw|oversee|owned|own|headed|head)\\s+"
                    + "(?:the\\s+)?(?:creation|architecture|team|teams|project|projects|"
                    + "development(?:\\s+team)?|initiative|people|staff|engineers|department|organization)\\b"
    );

    static boolean hasRoleObjectLeadership(String text) {
        return ROLE_OBJECT_LEADERSHIP.matcher(text == null ? "" : text).find()
                || LEADERSHIP_CREATION.matcher(text == null ? "" : text).find();
    }

    private static boolean replacesDeployWithMaintain(String originalText, String newText) {
        boolean originalDeploy = ActionConceptRegistry.contains(originalText, ActionConceptRegistry.DEPLOY);
        boolean newDeploy = ActionConceptRegistry.contains(newText, ActionConceptRegistry.DEPLOY);
        boolean originalMaintain = ActionConceptRegistry.contains(originalText, ActionConceptRegistry.MAINTAIN);
        boolean newMaintain = ActionConceptRegistry.contains(newText, ActionConceptRegistry.MAINTAIN);
        return originalDeploy && !newDeploy && newMaintain && !originalMaintain;
    }

    private static boolean upgradesAgileKnowledgeToCollaboration(String originalText, String newText) {
        String original = originalText == null ? "" : originalText;
        String rewritten = newText == null ? "" : newText;
        return AGILE_KNOWLEDGE.matcher(original).find()
                && AGILE_COLLABORATION.matcher(rewritten).find()
                && !AGILE_COLLABORATION.matcher(original).find();
    }

    private static boolean addsOnlyVaguePhrases(String originalText, String newText) {
        Set<String> original = new LinkedHashSet<>(contentTokens(originalText));
        Set<String> rewritten = new LinkedHashSet<>(contentTokens(newText));
        rewritten.removeAll(original);
        if (rewritten.isEmpty()) {
            return false;
        }
        for (String token : rewritten) {
            if (!VAGUE_TOKENS.contains(token) && !STOPWORDS.contains(token) && !SYNONYM_FILLERS.contains(token)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTrivialSurfaceChange(String originalText, String newText) {
        String original = collapseSpace(stripBullet(originalText)).toLowerCase(Locale.ROOT);
        String rewritten = collapseSpace(stripBullet(newText)).toLowerCase(Locale.ROOT);
        if (isPunctuationOnlyChange(originalText, newText)
                || isPluralityOnlyChange(original, rewritten)
                || isArticleOnlyChange(original, rewritten)
                || isWordOrderOnlyChange(originalText, newText)) {
            return true;
        }
        Set<String> left = new LinkedHashSet<>(dropArticles(singularizeTokenList(originalText)));
        Set<String> right = new LinkedHashSet<>(dropArticles(singularizeTokenList(newText)));
        return left.equals(right)
                && !normalizeComparable(originalText).equals(normalizeComparable(newText));
    }

    private static List<String> singularizeTokenList(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : tokenList(text)) {
            tokens.add(singularize(token));
        }
        return tokens;
    }

    private static boolean hasSignificantUplift(String originalText, String newText, String uplift) {
        if (UpliftType.SUBSTANTIVE_REWRITE.equals(uplift)
                || UpliftType.CLARITY_IMPROVEMENT.equals(uplift)) {
            return true;
        }
        if (UpliftType.GRAMMAR_FIX.equals(uplift)) {
            return isLightGrammarChange(originalText, newText)
                    && !isTrivialSurfaceChange(originalText, newText);
        }
        return false;
    }

    private static String leadingAction(String text) {
        for (String token : tokenList(text)) {
            if (!LEADING_ADVERBS.contains(token) && token.length() >= 3) {
                return token;
            }
        }
        return ConservativeTailorPlanner.firstWord(text);
    }

    private static Set<String> verbTokens(String text) {
        Set<String> verbs = new LinkedHashSet<>();
        for (String token : tokenList(text)) {
            if (IC_VERBS.contains(token) || LEADERSHIP_VERBS.contains(token)) {
                verbs.add(token);
            }
        }
        return verbs;
    }

    private static boolean weakensSpecificVerbs(String originalText, String newText) {
        Set<String> originalStems = new LinkedHashSet<>(contentStems(originalText));
        Set<String> newStems = new LinkedHashSet<>(contentStems(newText));
        boolean hadDesign = originalStems.contains("design");
        boolean hadImplement = originalStems.contains("implement");
        if (hadDesign && hadImplement
                && !newStems.contains("implement")
                && GENERIC_CREATED.contains(ConservativeTailorPlanner.firstWord(newText))) {
            return true;
        }
        return false;
    }

    private static String classifyUplift(String originalText, String newText) {
        String original = collapseSpace(stripBullet(originalText)).toLowerCase(Locale.ROOT);
        String rewritten = collapseSpace(stripBullet(newText)).toLowerCase(Locale.ROOT);
        if (isPunctuationOnlyChange(originalText, newText)
                || isPluralityOnlyChange(original, rewritten)
                || isArticleOnlyChange(original, rewritten)
                || isLightGrammarChange(originalText, newText)) {
            return UpliftType.GRAMMAR_FIX;
        }
        String firstOriginal = ConservativeTailorPlanner.firstWord(originalText);
        String firstNew = ConservativeTailorPlanner.firstWord(newText);
        boolean tenseImproved = !firstOriginal.equals(firstNew)
                && PAST_VERBS.contains(firstNew)
                && stem(firstOriginal).equals(stem(firstNew));
        boolean keptSpecificVerbs = contentStems(originalText).contains("design")
                && contentStems(newText).contains("design")
                && contentStems(originalText).contains("implement")
                && contentStems(newText).contains("implement");
        if (tenseImproved || keptSpecificVerbs && PAST_VERBS.contains(firstNew)) {
            return UpliftType.SUBSTANTIVE_REWRITE;
        }
        if (hasPurposeOrObjectClarification(originalText, newText)) {
            return UpliftType.SUBSTANTIVE_REWRITE;
        }
        return UpliftType.CLARITY_IMPROVEMENT;
    }

    private static boolean isLightGrammarChange(String originalText, String newText) {
        List<String> left = grammarStems(originalText);
        List<String> right = grammarStems(newText);
        return !left.isEmpty() && left.equals(right);
    }

    private static List<String> grammarStems(String text) {
        List<String> stems = new ArrayList<>();
        for (String token : dropLight(contentStems(text))) {
            stems.add(GRAMMAR_EQUIVALENTS.getOrDefault(token, token));
        }
        return stems;
    }

    private static boolean hasPurposeOrObjectClarification(String originalText, String newText) {
        Set<String> original = new LinkedHashSet<>(contentStems(originalText));
        Set<String> rewritten = new LinkedHashSet<>(contentStems(newText));
        rewritten.removeAll(original);
        rewritten.removeAll(List.of("support", "recommend", "architectur", "operat", "configur"));
        return rewritten.size() <= 3 && PAST_VERBS.contains(ConservativeTailorPlanner.firstWord(newText));
    }

    private static List<String> contentStems(String text) {
        List<String> stems = new ArrayList<>();
        for (String token : contentTokens(text)) {
            if (LIGHT_VERBS.contains(token)) {
                continue;
            }
            stems.add(stem(token));
        }
        return stems;
    }

    private static List<String> dropFiller(List<String> tokens) {
        List<String> filtered = new ArrayList<>();
        for (String token : tokens) {
            if (!FILLER_STEMS.contains(token)) {
                filtered.add(token);
            }
        }
        return filtered;
    }

    private static List<String> dropLight(List<String> tokens) {
        List<String> filtered = new ArrayList<>();
        for (String token : tokens) {
            if (!LIGHT_VERBS.contains(token) && !SYNONYM_FILLERS.contains(token)) {
                filtered.add(token);
            }
        }
        return filtered;
    }

    private static List<String> sortedCopy(List<String> values) {
        List<String> copy = new ArrayList<>(values);
        copy.sort(String::compareTo);
        return copy;
    }

    private static boolean isPunctuationOnlyChange(String originalText, String newText) {
        return lettersOnly(originalText).equals(lettersOnly(newText))
                && !stripBullet(originalText).equals(stripBullet(newText));
    }

    private static boolean isPluralityOnlyChange(String original, String rewritten) {
        return singularizeTokens(original).equals(singularizeTokens(rewritten))
                && !original.equals(rewritten)
                && tokenEditCount(original, rewritten) <= 2;
    }

    private static boolean isArticleOnlyChange(String original, String rewritten) {
        List<String> left = dropArticles(tokenList(original));
        List<String> right = dropArticles(tokenList(rewritten));
        return left.equals(right) && !tokenList(original).equals(tokenList(rewritten));
    }

    private static boolean isSingleSynonymChange(String original, String rewritten) {
        List<String> left = tokenList(original);
        List<String> right = tokenList(rewritten);
        if (Math.abs(left.size() - right.size()) > 1) {
            return false;
        }
        int diffs = 0;
        int limit = Math.min(left.size(), right.size());
        for (int i = 0; i < limit; i++) {
            if (left.get(i).equals(right.get(i))) {
                continue;
            }
            diffs++;
            String mapped = LOW_VALUE_SYNONYMS.get(left.get(i));
            boolean synonym = mapped != null
                    && (right.get(i).startsWith(mapped) || mapped.startsWith(right.get(i)));
            if (!synonym && !singularize(left.get(i)).equals(singularize(right.get(i)))) {
                return false;
            }
        }
        diffs += Math.abs(left.size() - right.size());
        return diffs >= 1 && diffs <= 2;
    }

    private static boolean isSynonymBagChange(String original, String rewritten) {
        Set<String> left = synonymBag(original);
        Set<String> right = synonymBag(rewritten);
        return left.equals(right) && !tokenList(original).equals(tokenList(rewritten));
    }

    private static Set<String> synonymBag(String text) {
        Set<String> bag = new LinkedHashSet<>();
        for (String token : tokenList(text)) {
            if (SYNONYM_FILLERS.contains(token)) {
                continue;
            }
            bag.add(stem(LOW_VALUE_SYNONYMS.getOrDefault(token, token)));
        }
        return bag;
    }

    private static boolean losesSkillTokens(String originalText, String newText) {
        if (!ConservativeTailorPlanner.isSkillList(originalText)) {
            return false;
        }
        Set<String> original = skillTokens(originalText);
        Set<String> rewritten = skillTokens(newText);
        return !rewritten.containsAll(original);
    }

    private static boolean losesCoreActions(String originalText, String newText) {
        Set<String> original = coreActions(originalText);
        if (original.isEmpty()) {
            return false;
        }
        Set<String> rewritten = coreActions(newText);
        return !rewritten.containsAll(original);
    }

    private static Set<String> coreActions(String text) {
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        for (String family : ActionConceptRegistry.actionsIn(text)) {
            if (Set.of(
                    ActionConceptRegistry.DESIGN,
                    ActionConceptRegistry.DEVELOP,
                    ActionConceptRegistry.IMPLEMENT,
                    ActionConceptRegistry.BUILD,
                    ActionConceptRegistry.INTEGRATE,
                    ActionConceptRegistry.DEPLOY,
                    ActionConceptRegistry.OPTIMIZE,
                    ActionConceptRegistry.ANALYZE
            ).contains(family)) {
                actions.add(family);
            }
        }
        return actions;
    }

    private static boolean losesImportantTech(String originalText, String newText) {
        Set<String> original = ConservativeEvidencePacket.extractNormalizedTech(originalText);
        Set<String> rewritten = ConservativeEvidencePacket.extractNormalizedTech(newText);
        String haystack = normalizeComparable(newText);
        for (String token : original) {
            if (rewritten.contains(token) || haystack.contains(token)) {
                continue;
            }
            boolean covered = false;
            for (String kept : rewritten) {
                if (kept.contains(token) || token.contains(kept)) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInformationLoss(String originalText, String newText) {
        List<String> original = contentTokens(originalText);
        List<String> rewritten = contentTokens(newText);
        if (rewritten.size() + 2 < original.size() && stripBullet(newText).length() + 12 < stripBullet(originalText).length()) {
            return true;
        }
        Set<String> rewrittenStems = new LinkedHashSet<>();
        for (String token : rewritten) {
            rewrittenStems.add(stem(token));
        }
        int missing = 0;
        for (String token : original) {
            if (token.length() < 5 || importantTech(originalText).contains(token)) {
                continue;
            }
            if (!rewrittenStems.contains(stem(token))) {
                missing++;
            }
        }
        return missing >= 3 && rewritten.size() + 1 < original.size();
    }

    private static boolean isKeywordStuffing(String originalText, String newText, String jobDescription) {
        List<String> jobTerms = ConservativeTailorPlanner.extractTerms(jobDescription);
        if (jobTerms.isEmpty()) {
            return false;
        }
        String rewritten = normalizeComparable(newText);
        String original = normalizeComparable(originalText);
        for (String term : jobTerms) {
            int originalCount = countOccurrences(original, term);
            int newCount = countOccurrences(rewritten, term);
            if (newCount >= 3 && newCount > originalCount + 1) {
                return true;
            }
        }
        Set<String> originalTerms = new LinkedHashSet<>(ConservativeTailorPlanner.extractTerms(originalText));
        int insertedJdTerms = 0;
        for (String term : jobTerms) {
            if (!originalTerms.contains(term) && rewritten.contains(term)) {
                insertedJdTerms++;
            }
        }
        return insertedJdTerms >= 4 && tokenEditCount(original, rewritten) <= insertedJdTerms + 1;
    }

    private static boolean hasBrokenGrammar(String newText) {
        String body = stripBullet(newText);
        if (body.isEmpty()) {
            return true;
        }
        long open = body.chars().filter(ch -> ch == '(').count();
        long close = body.chars().filter(ch -> ch == ')').count();
        return open != close || body.contains("  ");
    }

    private static Set<String> importantTech(String text) {
        Set<String> tech = new LinkedHashSet<>();
        if (text == null) {
            return tech;
        }
        if (text.toUpperCase(Locale.ROOT).contains("C/C++") || text.contains("C++")) {
            tech.add("c/c++");
        }
        for (String token : ConservativeTailorPlanner.extractTerms(text)) {
            if (token.length() >= 3 && (token.contains("+") || token.contains("#") || token.contains("/")
                    || Set.of("java", "spring", "boot", "maven", "python", "javascript", "vue",
                    "html", "css", "mysql", "sql", "linux", "unix", "restful", "rest",
                    "mvc", "cpu", "gpu", "ram", "github", "intellij", "redis", "docker",
                    "kubernetes", "aws", "oop").contains(token))) {
                tech.add(token);
            }
        }
        return tech;
    }

    private static Set<String> skillTokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        String value = stripBullet(text);
        for (String part : value.split("\\s*,\\s*")) {
            String normalized = part.strip().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty()) {
                tokens.add(normalized);
            }
        }
        return tokens;
    }

    private static List<String> contentTokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : tokenList(text)) {
            if (!STOPWORDS.contains(token) && token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static List<String> tokenList(String text) {
        List<String> tokens = new ArrayList<>();
        var matcher = TOKEN.matcher(normalizeComparable(text));
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static List<String> dropArticles(List<String> tokens) {
        List<String> filtered = new ArrayList<>();
        for (String token : tokens) {
            if (!Set.of("a", "an", "the").contains(token)) {
                filtered.add(token);
            }
        }
        return filtered;
    }

    private static String singularizeTokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : tokenList(text)) {
            tokens.add(singularize(token));
        }
        return String.join(" ", tokens);
    }

    private static String singularize(String token) {
        if (token.endsWith("ies") && token.length() > 4) {
            return token.substring(0, token.length() - 3) + "y";
        }
        if (token.endsWith("ses") || token.endsWith("xes") || token.endsWith("zes")) {
            return token.substring(0, token.length() - 2);
        }
        if (token.endsWith("s") && !token.endsWith("ss") && token.length() > 3) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    private static String stem(String token) {
        String value = singularize(token);
        if (value.endsWith("ing") && value.length() > 5) {
            return value.substring(0, value.length() - 3);
        }
        if (value.endsWith("ed") && value.length() > 4) {
            String base = value.substring(0, value.length() - 2);
            if (base.endsWith("i") && base.length() > 2) {
                return base.substring(0, base.length() - 1) + "y";
            }
            return base;
        }
        if (value.endsWith("ly") && value.length() > 5) {
            return value.substring(0, value.length() - 2);
        }
        return value;
    }

    private static String collapseSpace(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static int tokenEditCount(String left, String right) {
        Set<String> a = new LinkedHashSet<>(tokenList(left));
        Set<String> b = new LinkedHashSet<>(tokenList(right));
        Set<String> onlyA = new LinkedHashSet<>(a);
        onlyA.removeAll(b);
        Set<String> onlyB = new LinkedHashSet<>(b);
        onlyB.removeAll(a);
        return onlyA.size() + onlyB.size();
    }

    private static int countOccurrences(String haystack, String term) {
        int count = 0;
        int index = 0;
        while (index >= 0) {
            index = haystack.indexOf(term, index);
            if (index < 0) {
                break;
            }
            count++;
            index += term.length();
        }
        return count;
    }

    private static String normalizeComparable(String text) {
        return stripBullet(text)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9+#/\\s]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String lettersOnly(String text) {
        return stripBullet(text).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+#/]", "");
    }

    private static String stripBullet(String text) {
        if (text == null) {
            return "";
        }
        return BULLET.matcher(text.strip()).replaceFirst("").strip();
    }

    public record QualityScores(
            int grammarScore,
            int clarityScore,
            int actionVerbScore,
            int specificityScore,
            int concisenessScore,
            int jobRelevanceScore,
            int factualPreservationScore
    ) {
        public int total() {
            return grammarScore + clarityScore + actionVerbScore + specificityScore
                    + concisenessScore + jobRelevanceScore + factualPreservationScore;
        }
    }

    public record Verdict(
            boolean accepted,
            String reasonCode,
            QualityScores scores,
            int factualScore,
            String upliftType
    ) {
        static Verdict accept(QualityScores scores, String upliftType) {
            return new Verdict(true, null, scores, scores.factualPreservationScore(), upliftType);
        }

        static Verdict reject(String reasonCode, int factualScore) {
            return new Verdict(false, reasonCode, null, factualScore, UpliftType.LOW_VALUE);
        }
    }
}
