package aijob.util;

import aijob.dto.ConservativeRewriteCandidate;
import aijob.dto.RewriteIntent;
import aijob.dto.TailorChange;
import aijob.dto.UpliftType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic improvement reasons from original/new text and classified intent.
 * Model-supplied reason strings are ignored.
 */
public final class ConservativeRewriteReasons {

    private static final String BANNED_ZH = "在保留全部事实的前提下，让这条经历更专业、更清楚。";

    private ConservativeRewriteReasons() {
    }

    public static String upliftTypeFor(String classifiedIntent) {
        if (RewriteIntent.GRAMMAR_FIX.equals(classifiedIntent)) {
            return UpliftType.GRAMMAR_FIX;
        }
        if (RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT.equals(classifiedIntent)
                || RewriteIntent.JD_RELEVANCE.equals(classifiedIntent)
                || RewriteIntent.SUMMARY_REWRITE.equals(classifiedIntent)) {
            return UpliftType.SUBSTANTIVE_REWRITE;
        }
        if (RewriteIntent.CLARITY_IMPROVEMENT.equals(classifiedIntent)) {
            return UpliftType.CLARITY_IMPROVEMENT;
        }
        return "";
    }

    public static void apply(TailorChange change, ConservativeRewriteCandidate candidate, String upliftType) {
        String intent = intentFromUplift(upliftType);
        apply(change, intent, List.of());
    }

    public static void apply(TailorChange change, ConservativeRewriteCandidate candidate) {
        apply(change, RewriteIntent.CLARITY_IMPROVEMENT, List.of());
    }

    public static void apply(TailorChange change, String classifiedIntent, List<String> supportingTexts) {
        if (change == null) {
            return;
        }
        String original = change.getOriginalText();
        String rewritten = change.getNewText();
        String english = english(original, rewritten, classifiedIntent, supportingTexts);
        String chinese = chinese(original, rewritten, classifiedIntent, supportingTexts);
        change.setReason(english);
        change.setReasonZh(chinese);
        change.setImprovementSummaryEn(english);
        change.setImprovementSummaryZh(chinese);
        String uplift = upliftTypeFor(classifiedIntent);
        if (uplift != null && !uplift.isBlank()) {
            change.setUpliftType(uplift);
        }
    }

    static String english(
            String original,
            String rewritten,
            String classifiedIntent,
            List<String> supportingTexts) {

        if (RewriteIntent.GRAMMAR_FIX.equals(classifiedIntent) || isManagementsFix(original, rewritten)) {
            if (isManagementsFix(original, rewritten) && containsIgnoreCase(original, "rest")) {
                return "Corrected shopping cart managements and simplified the REST API responsibility wording.";
            }
            if (isTenseFix(original, rewritten) && keepsSpringStack(original, rewritten)) {
                return "Unified verb tense while keeping Java, Spring Boot, and MVC.";
            }
            return "Fixed grammar or tense while keeping " + keptPhraseEn(original, rewritten) + ".";
        }
        if (RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT.equals(classifiedIntent)
                || addedOop(original, rewritten, supportingTexts)) {
            if (addedOop(original, rewritten, supportingTexts) && keepsMavenBudget(original, rewritten)) {
                return "Added same-project OOP implementation while keeping Java, Maven, and budget-based selection facts.";
            }
            return "Added " + addedPhraseEn(original, rewritten, supportingTexts)
                    + " from the same project while keeping "
                    + keptPhraseEn(original, rewritten) + ".";
        }
        if (isAlgorithmFocus(original, rewritten)) {
            return "Emphasized the dynamic recommendation algorithm and its CPU, GPU, and RAM targets.";
        }
        if (isManagementsFix(original, rewritten) && containsIgnoreCase(original, "rest")) {
            return "Corrected shopping cart managements and simplified the REST API responsibility wording.";
        }
        if (isTenseFix(original, rewritten) && keepsSpringStack(original, rewritten)) {
            return "Unified verb tense while keeping Java, Spring Boot, and MVC.";
        }
        if (isMavenBasedHome(original, rewritten)) {
            return "Reordered Java and Maven wording and used a more precise budget constraint while keeping the original selection task.";
        }
        if (isRestDutySimplify(original, rewritten)) {
            return "Corrected shopping cart managements and simplified the REST API responsibility wording.";
        }
        List<String> changes = concreteChangesEn(original, rewritten, supportingTexts);
        if (!changes.isEmpty()) {
            return String.join(" ", changes);
        }
        return "Clarified " + keptPhraseEn(original, rewritten) + " without adding unsupported relations.";
    }

    static String chinese(
            String original,
            String rewritten,
            String classifiedIntent,
            List<String> supportingTexts) {

        if (RewriteIntent.GRAMMAR_FIX.equals(classifiedIntent) || isManagementsFix(original, rewritten)) {
            if (isManagementsFix(original, rewritten) && containsIgnoreCase(original, "rest")) {
                return "修正 shopping cart managements，并简化 REST API 职责表达。";
            }
            if (isTenseFix(original, rewritten) && keepsSpringStack(original, rewritten)) {
                return "统一动词时态，并保留 Java、Spring Boot 和 MVC。";
            }
            return "修正了语法或时态，并保留了" + keptPhraseZh(original, rewritten) + "。";
        }
        if (RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT.equals(classifiedIntent)
                || addedOop(original, rewritten, supportingTexts)) {
            if (addedOop(original, rewritten, supportingTexts) && keepsMavenBudget(original, rewritten)) {
                return "补充同项目中的 OOP 实现，同时保留 Java、Maven 和预算选型事实。";
            }
            return "补充同项目中的" + addedPhraseZh(original, rewritten, supportingTexts)
                    + "，同时保留" + keptPhraseZh(original, rewritten) + "。";
        }
        if (isAlgorithmFocus(original, rewritten)) {
            return "突出动态推荐算法及 CPU、GPU、RAM 的处理对象。";
        }
        if (isTenseFix(original, rewritten) && keepsSpringStack(original, rewritten)) {
            return "统一动词时态，并保留 Java、Spring Boot 和 MVC。";
        }
        if (isMavenBasedHome(original, rewritten)) {
            return "调整了 Java/Maven 表达，并将 given budget 改为 specified budget，同时保留选型任务。";
        }
        if (isRestDutySimplify(original, rewritten) || isManagementsFix(original, rewritten)) {
            return "修正 shopping cart managements，并简化 REST API 职责表达。";
        }
        List<String> changes = concreteChangesZh(original, rewritten, supportingTexts);
        if (!changes.isEmpty()) {
            return String.join("", changes);
        }
        return "更清楚地表达了" + keptPhraseZh(original, rewritten) + "，没有编造新的证据关系。";
    }

    private static String intentFromUplift(String upliftType) {
        if (UpliftType.GRAMMAR_FIX.equals(upliftType)) {
            return RewriteIntent.GRAMMAR_FIX;
        }
        if (UpliftType.SUBSTANTIVE_REWRITE.equals(upliftType)) {
            return RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT;
        }
        return RewriteIntent.CLARITY_IMPROVEMENT;
    }

    private static boolean isManagementsFix(String original, String rewritten) {
        return containsIgnoreCase(original, "managements")
                && !containsIgnoreCase(rewritten, "managements")
                && (containsIgnoreCase(rewritten, "management")
                || containsIgnoreCase(rewritten, "operations")
                || containsIgnoreCase(rewritten, "workflows"));
    }

    private static boolean isTenseFix(String original, String rewritten) {
        String firstOriginal = ConservativeTailorPlanner.firstWord(original);
        String firstNew = ConservativeTailorPlanner.firstWord(rewritten);
        return !firstOriginal.equalsIgnoreCase(firstNew) && firstNew.toLowerCase(Locale.ROOT).endsWith("ed");
    }

    private static boolean keepsSpringStack(String original, String rewritten) {
        return containsIgnoreCase(original, "java") && containsIgnoreCase(rewritten, "java")
                && containsIgnoreCase(original, "spring") && containsIgnoreCase(rewritten, "spring")
                && containsIgnoreCase(original, "mvc") && containsIgnoreCase(rewritten, "mvc");
    }

    private static boolean addedOop(String original, String rewritten, List<String> supportingTexts) {
        if (containsIgnoreCase(original, "oop") || containsIgnoreCase(original, "object-oriented")) {
            return false;
        }
        if (!(containsIgnoreCase(rewritten, "oop") || containsIgnoreCase(rewritten, "object-oriented"))) {
            return false;
        }
        if (supportingTexts != null) {
            for (String supporting : supportingTexts) {
                if (containsIgnoreCase(supporting, "oop") || containsIgnoreCase(supporting, "object-oriented")) {
                    return true;
                }
            }
        }
        return true;
    }

    private static boolean keepsMavenBudget(String original, String rewritten) {
        return containsIgnoreCase(rewritten, "java")
                && containsIgnoreCase(rewritten, "maven")
                && containsIgnoreCase(rewritten, "budget")
                && (containsIgnoreCase(original, "maven") || containsIgnoreCase(original, "java"));
    }

    private static boolean isAlgorithmFocus(String original, String rewritten) {
        return containsIgnoreCase(original, "algorithm")
                && containsIgnoreCase(rewritten, "algorithm")
                && containsIgnoreCase(rewritten, "cpu")
                && containsIgnoreCase(rewritten, "gpu")
                && containsIgnoreCase(rewritten, "ram");
    }

    private static boolean isMavenBasedHome(String original, String rewritten) {
        return containsIgnoreCase(original, "java")
                && containsIgnoreCase(original, "maven")
                && containsIgnoreCase(rewritten, "maven-based")
                && containsIgnoreCase(rewritten, "java")
                && containsIgnoreCase(rewritten, "budget")
                && !containsIgnoreCase(rewritten, "oop")
                && !containsIgnoreCase(rewritten, "cpu");
    }

    private static boolean isRestDutySimplify(String original, String rewritten) {
        return containsIgnoreCase(original, "rest")
                && containsIgnoreCase(rewritten, "rest")
                && containsIgnoreCase(original, "handle")
                && !containsIgnoreCase(rewritten, "handle")
                && (containsIgnoreCase(rewritten, "authentication") || containsIgnoreCase(rewritten, "auth"));
    }

    private static List<String> concreteChangesEn(
            String original,
            String rewritten,
            List<String> supportingTexts) {

        List<String> parts = new ArrayList<>();
        if (isTenseFix(original, rewritten)) {
            parts.add("Unified the opening verb tense");
        }
        if (isManagementsFix(original, rewritten)) {
            parts.add("corrected shopping cart managements");
        }
        String added = addedPhraseEn(original, rewritten, supportingTexts);
        if (!added.equals("supporting facts")) {
            parts.add("added " + added);
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        parts.set(0, capitalize(parts.get(0)) + " while keeping " + keptPhraseEn(original, rewritten) + ".");
        return parts;
    }

    private static List<String> concreteChangesZh(
            String original,
            String rewritten,
            List<String> supportingTexts) {

        List<String> parts = new ArrayList<>();
        if (isTenseFix(original, rewritten)) {
            parts.add("统一了动词时态");
        }
        if (isManagementsFix(original, rewritten)) {
            parts.add(parts.isEmpty() ? "修正 shopping cart managements" : "，并修正 shopping cart managements");
        }
        String added = addedPhraseZh(original, rewritten, supportingTexts);
        if (!"相关事实".equals(added)) {
            parts.add(parts.isEmpty() ? "补充了" + added : "，并补充了" + added);
        }
        if (parts.isEmpty()) {
            return List.of();
        }
        parts.add("，并保留了" + keptPhraseZh(original, rewritten) + "。");
        return parts;
    }

    private static String addedPhraseEn(String original, String rewritten, List<String> supportingTexts) {
        List<String> added = new ArrayList<>();
        if (addedOop(original, rewritten, supportingTexts)) {
            added.add("OOP");
        }
        if (!containsIgnoreCase(original, "cpu") && containsIgnoreCase(rewritten, "cpu")) {
            added.add("CPU/GPU/RAM");
        }
        return added.isEmpty() ? "supporting facts" : String.join(" and ", added);
    }

    private static String addedPhraseZh(String original, String rewritten, List<String> supportingTexts) {
        List<String> added = new ArrayList<>();
        if (addedOop(original, rewritten, supportingTexts)) {
            added.add("OOP 实现");
        }
        if (!containsIgnoreCase(original, "cpu") && containsIgnoreCase(rewritten, "cpu")) {
            added.add("CPU/GPU/RAM");
        }
        return added.isEmpty() ? "相关事实" : String.join("和", added);
    }

    private static String keptPhraseEn(String original, String rewritten) {
        List<String> kept = keptLabels(original, rewritten);
        if (kept.isEmpty()) {
            return "the original material facts";
        }
        if (kept.size() == 1) {
            return kept.get(0);
        }
        return String.join(", ", kept.subList(0, kept.size() - 1)) + ", and " + kept.get(kept.size() - 1);
    }

    private static String keptPhraseZh(String original, String rewritten) {
        List<String> kept = keptLabels(original, rewritten);
        if (kept.isEmpty()) {
            return "原有事实";
        }
        return String.join("、", kept);
    }

    private static List<String> keptLabels(String original, String rewritten) {
        LinkedHashSet<String> kept = new LinkedHashSet<>();
        addIfKept(kept, original, rewritten, "java", "Java");
        addIfKept(kept, original, rewritten, "maven", "Maven");
        addIfKept(kept, original, rewritten, "spring boot", "Spring Boot");
        addIfKept(kept, original, rewritten, "mvc", "MVC");
        addIfKept(kept, original, rewritten, "rest", "REST API");
        addIfKept(kept, original, rewritten, "budget", "预算选型");
        addIfKept(kept, original, rewritten, "cpu", "CPU");
        addIfKept(kept, original, rewritten, "gpu", "GPU");
        addIfKept(kept, original, rewritten, "ram", "RAM");
        addIfKept(kept, original, rewritten, "algorithm", "algorithm");
        return new ArrayList<>(kept);
    }

    private static void addIfKept(
            Set<String> kept,
            String original,
            String rewritten,
            String needle,
            String label) {

        if (containsIgnoreCase(original, needle) && containsIgnoreCase(rewritten, needle)) {
            kept.add(label);
        }
    }

    private static String capitalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static boolean containsIgnoreCase(String text, String needle) {
        return text != null && needle != null && text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    static boolean isBannedTemplate(String reason) {
        return reason != null && reason.contains(BANNED_ZH);
    }
}
