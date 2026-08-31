package aijob.util;

import aijob.dto.ConservativeRewritePlan;
import aijob.dto.RejectedTailorChange;
import aijob.dto.RewriteIntent;
import aijob.dto.TailorChange;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Joint candidate-level selection for one CONSERVATIVE proposal.
 * At most one rewrite per node; combinations are scored together.
 */
public final class ConservativeBatchSelector {

    public static final int MAX_SUGGESTIONS = 5;
    public static final int MAX_PER_ENTRY = 2;
    public static final double HIGH_SIMILARITY = 0.72;
    public static final double SUPPORTING_REUSE_SIMILARITY = 0.55;
    public static final double REST_ABSORPTION_SIMILARITY = 0.45;

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9+#\\./]{1,}");

    private ConservativeBatchSelector() {
    }

    public static Selection select(List<PlanPick> picks) {
        return select(picks, Context.empty());
    }

    public static Selection select(List<PlanPick> picks, Context context) {
        Context ctx = context == null ? Context.empty() : context;
        List<RejectedTailorChange> dropped = new ArrayList<>();
        if (picks == null || picks.isEmpty()) {
            return new Selection(List.of(), dropped);
        }
        Map<String, List<PlanPick>> byNode = new LinkedHashMap<>();
        List<PlanPick> admitted = new ArrayList<>();
        for (PlanPick pick : picks) {
            if (pick == null || pick.change() == null || pick.score() == null || !pick.score().admitted()) {
                continue;
            }
            String nodeId = pick.change().getNodeId();
            if (nodeId == null || nodeId.isBlank()) {
                continue;
            }
            admitted.add(pick);
            byNode.computeIfAbsent(nodeId, ignored -> new ArrayList<>()).add(pick);
        }
        if (admitted.isEmpty()) {
            return new Selection(List.of(), dropped);
        }
        List<String> nodeOrder = new ArrayList<>(byNode.keySet());
        Best best = new Best();
        search(0, new ArrayList<>(), nodeOrder, byNode, best, ctx);
        List<PlanPick> selected = new ArrayList<>(best.combo);
        selected.sort(Comparator
                .comparingInt((PlanPick pick) -> RewriteIntent.priority(pick.intent()))
                .reversed()
                .thenComparing(Comparator.comparingInt((PlanPick pick) -> pick.score().total()).reversed()));
        Set<String> selectedKeys = new LinkedHashSet<>();
        Set<String> selectedNodeIds = new LinkedHashSet<>();
        for (PlanPick pick : selected) {
            selectedKeys.add(identity(pick));
            selectedNodeIds.add(pick.change().getNodeId());
        }
        Set<String> droppedNodes = new LinkedHashSet<>();
        for (PlanPick pick : admitted) {
            if (selectedKeys.contains(identity(pick))) {
                continue;
            }
            String nodeId = pick.change().getNodeId();
            if (selectedNodeIds.contains(nodeId) || !droppedNodes.add(nodeId)) {
                continue;
            }
            dropped.add(new RejectedTailorChange(nodeId, dropReason(selected, pick)));
        }
        return new Selection(List.copyOf(selected), List.copyOf(dropped));
    }

    private static void search(
            int index,
            List<PlanPick> current,
            List<String> nodeOrder,
            Map<String, List<PlanPick>> byNode,
            Best best,
            Context context) {

        if (index >= nodeOrder.size()) {
            if (!current.isEmpty() && prefixValid(current, context)) {
                int scored = score(current, byNode, context);
                if (scored > best.score || (scored == best.score && current.size() > best.combo.size())) {
                    best.combo = new ArrayList<>(current);
                    best.score = scored;
                }
            }
            return;
        }
        search(index + 1, current, nodeOrder, byNode, best, context);
        for (PlanPick pick : byNode.get(nodeOrder.get(index))) {
            current.add(pick);
            if (prefixValid(current, context)) {
                search(index + 1, current, nodeOrder, byNode, best, context);
            }
            current.remove(current.size() - 1);
        }
    }

    private static boolean prefixValid(List<PlanPick> combo, Context context) {
        boolean hasSummary = false;
        for (PlanPick pick : combo) {
            if (RewriteIntent.SUMMARY_REWRITE.equals(pick.intent())) {
                hasSummary = true;
            }
            if (reusesLockedSupporting(pick, context.lockedSupportingNodeIds())) {
                return false;
            }
        }
        if (hasSummary && combo.size() > 1) {
            return false;
        }
        List<PlanPick> locked = context.locked();
        for (int i = 0; i < combo.size(); i++) {
            PlanPick left = combo.get(i);
            for (PlanPick lockedPick : locked) {
                if (circularEnrichment(lockedPick, left)) {
                    return false;
                }
                if (jaccard(textOf(lockedPick), left.change().getNewText()) >= HIGH_SIMILARITY) {
                    return false;
                }
            }
            for (int j = i + 1; j < combo.size(); j++) {
                PlanPick right = combo.get(j);
                if (circularEnrichment(left, right)) {
                    return false;
                }
                if (jaccard(left.change().getNewText(), right.change().getNewText()) >= HIGH_SIMILARITY) {
                    return false;
                }
            }
        }
        return uniqueFactContribution(combo, context)
                && !sharesOptionalSupporting(combo, context)
                && !sharesSupportingFactWithSimilar(combo);
    }

    private static boolean uniqueFactContribution(List<PlanPick> combo, Context context) {
        List<String> lockedTexts = lockedTexts(context);
        for (PlanPick pick : combo) {
            List<String> others = new ArrayList<>(lockedTexts);
            for (PlanPick other : combo) {
                if (other != pick) {
                    others.add(other.change().getNewText());
                }
            }
            if (ConservativeMaterialFacts.uniqueKeys(pick.change().getNewText(), others).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static int poolFactCoverage(
            List<PlanPick> combo,
            Map<String, List<PlanPick>> byNode) {

        List<String> originals = new ArrayList<>();
        for (List<PlanPick> picks : byNode.values()) {
            if (picks == null || picks.isEmpty()) {
                continue;
            }
            PlanPick sample = picks.get(0);
            if (ConservativeMaterialFacts.skipSummary(sample.intent())) {
                continue;
            }
            originals.add(sample.change().getOriginalText());
        }
        List<String> rewritten = new ArrayList<>();
        for (PlanPick pick : combo) {
            rewritten.add(pick.change().getNewText());
        }
        return (int) Math.round(ConservativeMaterialFacts.batchRetention(originals, rewritten) * 100);
    }

    private static int score(List<PlanPick> combo, Map<String, List<PlanPick>> byNode, Context context) {
        Set<String> unique = new LinkedHashSet<>();
        int home = 0;
        int packing = 0;
        int totals = 0;
        List<String> newTexts = new ArrayList<>();
        for (PlanPick pick : combo) {
            Set<String> originalTech = ConservativeEvidencePacket.extractNormalizedTech(
                    pick.change().getOriginalText());
            Set<String> newTech = ConservativeEvidencePacket.extractNormalizedTech(pick.change().getNewText());
            unique.addAll(newTech);
            Set<String> kept = new LinkedHashSet<>(originalTech);
            kept.retainAll(newTech);
            home += kept.size();
            Set<String> extra = new LinkedHashSet<>(newTech);
            extra.removeAll(originalTech);
            packing += extra.size();
            totals += pick.score().total();
            newTexts.add(pick.change().getNewText());
        }
        int uniqueFacts = 0;
        for (PlanPick pick : combo) {
            List<String> others = new ArrayList<>();
            for (PlanPick other : combo) {
                if (other != pick) {
                    others.add(other.change().getNewText());
                }
            }
            uniqueFacts += ConservativeMaterialFacts.uniqueKeys(pick.change().getNewText(), others).size();
        }
        int unionFacts = ConservativeMaterialFacts.coveredKeys(String.join(" ", newTexts)).size();
        int overlap = 0;
        for (int i = 0; i < combo.size(); i++) {
            for (int j = i + 1; j < combo.size(); j++) {
                double similarity = jaccard(combo.get(i).change().getNewText(), combo.get(j).change().getNewText());
                if (similarity >= SUPPORTING_REUSE_SIMILARITY) {
                    overlap += (int) Math.round(similarity * 100);
                }
            }
        }
        int size = combo.size();
        int sizeBonus = 0;
        if (size >= 2 && size <= 4) {
            sizeBonus = size * 28;
        } else if (size == 5) {
            sizeBonus = 80;
        }
        return unique.size() * 20
                + unionFacts * 30
                + uniqueFacts * 18
                + poolFactCoverage(combo, byNode) * 4
                + home * 14
                + sizeBonus
                + totals
                - packing * 4
                - overlap
                - reservedSubsumePenalty(combo, byNode, context);
    }

    private static int reservedSubsumePenalty(
            List<PlanPick> combo,
            Map<String, List<PlanPick>> byNode,
            Context context) {

        if (context == null || context.reservedTargetNodeIds().isEmpty()) {
            return 0;
        }
        Set<String> comboIds = new LinkedHashSet<>();
        for (PlanPick pick : combo) {
            comboIds.add(pick.change().getNodeId());
        }
        int penalty = 0;
        for (String reservedId : context.reservedTargetNodeIds()) {
            if (comboIds.contains(reservedId) || !byNode.containsKey(reservedId)) {
                continue;
            }
            List<PlanPick> reservedPicks = byNode.get(reservedId);
            if (reservedPicks == null || reservedPicks.isEmpty()) {
                continue;
            }
            String original = reservedPicks.get(0).change().getOriginalText();
            for (PlanPick pick : combo) {
                if (ConservativeMaterialFacts.fullySubsumes(original, pick.change().getNewText())) {
                    penalty += 50;
                }
            }
        }
        return penalty;
    }

    private static String dropReason(List<PlanPick> selected, PlanPick pick) {
        for (PlanPick kept : selected) {
            if (circularEnrichment(kept, pick)
                    || jaccard(kept.change().getNewText(), pick.change().getNewText()) >= HIGH_SIMILARITY
                    || ConservativeMaterialFacts.fullySubsumes(
                    pick.change().getOriginalText(), kept.change().getNewText())) {
                return RejectedTailorChange.LOW_VALUE_REWRITE;
            }
        }
        return RejectedTailorChange.LOW_VALUE_REWRITE;
    }

    private static int entryCount(List<PlanPick> selected, String entryKey) {
        int count = 0;
        for (PlanPick pick : selected) {
            if (entryKey != null && entryKey.equals(pick.entryKey())) {
                count++;
            }
        }
        return count;
    }

    static boolean circularEnrichment(PlanPick left, PlanPick right) {
        if (left == null || right == null || !isEnrichment(left) || !isEnrichment(right)) {
            return false;
        }
        String leftId = left.change().getNodeId();
        String rightId = right.change().getNodeId();
        return supportingOnly(left).contains(rightId) && supportingOnly(right).contains(leftId);
    }

    static int circularSupportingCount(List<PlanPick> selected) {
        int count = 0;
        if (selected == null) {
            return 0;
        }
        for (int i = 0; i < selected.size(); i++) {
            for (int j = i + 1; j < selected.size(); j++) {
                if (circularEnrichment(selected.get(i), selected.get(j))) {
                    count++;
                }
            }
        }
        return count;
    }

    static int semanticOverlapCount(List<PlanPick> selected) {
        int count = 0;
        if (selected == null) {
            return 0;
        }
        for (int i = 0; i < selected.size(); i++) {
            for (int j = i + 1; j < selected.size(); j++) {
                if (jaccard(selected.get(i).change().getNewText(), selected.get(j).change().getNewText())
                        >= HIGH_SIMILARITY) {
                    count++;
                }
            }
        }
        return count;
    }

    private static boolean isEnrichment(PlanPick pick) {
        return RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT.equals(pick.intent());
    }

    private static boolean sharesOptionalSupporting(List<PlanPick> combo, Context context) {
        List<PlanPick> all = new ArrayList<>(context.locked());
        all.addAll(combo);
        Set<String> targets = new LinkedHashSet<>();
        for (PlanPick pick : all) {
            if (pick.change() != null && pick.change().getNodeId() != null) {
                targets.add(pick.change().getNodeId());
            }
        }
        for (int i = 0; i < all.size(); i++) {
            Set<String> left = supportingOnly(all.get(i));
            if (left.isEmpty()) {
                continue;
            }
            for (int j = i + 1; j < all.size(); j++) {
                Set<String> shared = new LinkedHashSet<>(supportingOnly(all.get(j)));
                shared.retainAll(left);
                shared.removeAll(targets);
                if (!shared.isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean reusesLockedSupporting(PlanPick pick, Set<String> lockedSupportingNodeIds) {
        if (lockedSupportingNodeIds == null || lockedSupportingNodeIds.isEmpty()) {
            return false;
        }
        Set<String> supporting = supportingOnly(pick);
        supporting.retainAll(lockedSupportingNodeIds);
        return !supporting.isEmpty();
    }

    private static int grammarCount(List<PlanPick> picks) {
        int count = 0;
        for (PlanPick pick : picks) {
            if (pick != null && pick.score() != null && pick.score().grammarOnly()) {
                count++;
            }
        }
        return count;
    }

    private static Map<String, Integer> entryCounts(List<PlanPick> picks) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (PlanPick pick : picks) {
            if (pick == null) {
                continue;
            }
            String entry = pick.entryKey() == null ? "" : pick.entryKey();
            counts.merge(entry, 1, Integer::sum);
        }
        return counts;
    }

    private static List<String> lockedTexts(Context context) {
        List<String> texts = new ArrayList<>();
        for (PlanPick pick : context.locked()) {
            String text = textOf(pick);
            if (text != null && !text.isBlank()) {
                texts.add(text);
            }
        }
        return texts;
    }

    private static String textOf(PlanPick pick) {
        if (pick == null || pick.change() == null) {
            return "";
        }
        return pick.change().getNewText();
    }

    private static boolean sharesSupportingFactWithSimilar(List<PlanPick> combo) {
        for (int i = 0; i < combo.size(); i++) {
            Set<String> supporting = supportingOnly(combo.get(i));
            if (supporting.isEmpty()) {
                continue;
            }
            for (int j = i + 1; j < combo.size(); j++) {
                Set<String> shared = new LinkedHashSet<>(supportingOnly(combo.get(j)));
                shared.retainAll(supporting);
                if (!shared.isEmpty()
                        && jaccard(combo.get(i).change().getNewText(), combo.get(j).change().getNewText())
                        >= SUPPORTING_REUSE_SIMILARITY) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<String> supportingOnly(PlanPick pick) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (pick.usedFactNodeIds() != null) {
            ids.addAll(pick.usedFactNodeIds());
        }
        String target = pick.plan() == null ? pick.change().getNodeId() : pick.plan().nodeId();
        ids.remove(target);
        if (pick.change() != null) {
            ids.remove(pick.change().getNodeId());
        }
        return ids;
    }

    static double jaccard(String left, String right) {
        Set<String> a = tokens(left);
        Set<String> b = tokens(right);
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new LinkedHashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return intersection.size() / (double) union.size();
    }

    private static Set<String> tokens(String text) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        var matcher = TOKEN.matcher(lower(text));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 3) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private static String identity(PlanPick pick) {
        return pick.change().getNodeId() + "\n" + pick.change().getNewText();
    }

    private static final class Best {
        private List<PlanPick> combo = List.of();
        private int score = Integer.MIN_VALUE;
    }

    public record PlanPick(
            ConservativeRewritePlan plan,
            TailorChange change,
            ConservativeCandidateLocalScorer.Score score,
            List<String> usedFactNodeIds
    ) {
        public String intent() {
            if (score != null && score.classifiedIntent() != null && !score.classifiedIntent().isBlank()) {
                return score.classifiedIntent();
            }
            return plan == null ? "" : plan.rewriteIntent();
        }

        public String entryKey() {
            return plan == null ? "" : plan.entryKey();
        }
    }

    public record Selection(
            List<PlanPick> selected,
            List<RejectedTailorChange> dropped
    ) {
    }

    public record Context(
            List<PlanPick> locked,
            Set<String> reservedTargetNodeIds,
            Set<String> lockedSupportingNodeIds
    ) {
        public Context {
            locked = locked == null ? List.of() : List.copyOf(locked);
            reservedTargetNodeIds = reservedTargetNodeIds == null
                    ? Set.of()
                    : Set.copyOf(reservedTargetNodeIds);
            lockedSupportingNodeIds = lockedSupportingNodeIds == null
                    ? Set.of()
                    : Set.copyOf(lockedSupportingNodeIds);
        }

        public static Context empty() {
            return new Context(List.of(), Set.of(), Set.of());
        }
    }
}
