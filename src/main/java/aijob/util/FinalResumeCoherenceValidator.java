package aijob.util;

import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory final-resume check before a PENDING proposal is stored.
 * Does not call Writer and does not write files.
 */
public final class FinalResumeCoherenceValidator {

    private FinalResumeCoherenceValidator() {
    }

    public record Issue(String nodeId, String reasonCode) {
    }

    public record Result(boolean passed, List<Issue> issues, int copiedSupportingFacts) {
        static Result ok() {
            return new Result(true, List.of(), 0);
        }
    }

    public static ConservativeBatchSelector.Selection prune(
            List<ResumeDocumentNode> nodes,
            ConservativeBatchSelector.Selection batch,
            List<TailorChange> lockedChanges) {

        List<RejectedTailorChange> dropped = new ArrayList<>();
        if (batch != null && batch.dropped() != null) {
            dropped.addAll(batch.dropped());
        }
        List<ConservativeBatchSelector.PlanPick> selected = new ArrayList<>();
        if (batch != null && batch.selected() != null) {
            selected.addAll(batch.selected());
        }
        Set<String> lockedIds = lockedNodeIds(lockedChanges);
        while (!selected.isEmpty()) {
            List<TailorChange> applied = new ArrayList<>();
            if (lockedChanges != null) {
                applied.addAll(lockedChanges);
            }
            for (ConservativeBatchSelector.PlanPick pick : selected) {
                applied.add(pick.change());
            }
            Result result = evaluate(nodes, applied);
            if (result.passed()) {
                break;
            }
            ConservativeBatchSelector.PlanPick victim = lowestNewPick(selected, result, lockedIds);
            if (victim == null) {
                break;
            }
            selected.remove(victim);
            dropped.add(new RejectedTailorChange(
                    victim.change().getNodeId(),
                    reasonFor(victim, result)));
        }
        return new ConservativeBatchSelector.Selection(List.copyOf(selected), List.copyOf(dropped));
    }

    public static Result evaluate(List<ResumeDocumentNode> nodes, List<TailorChange> changes) {
        Map<String, String> simulated = simulate(nodes, changes);
        Map<String, String> originals = originals(nodes);
        ClaimLedger ledger = ClaimLedger.from(nodes);
        List<Issue> issues = new ArrayList<>();
        int copied = 0;
        if (changes != null) {
            for (TailorChange change : changes) {
                if (change == null || change.getNodeId() == null || change.getNewText() == null) {
                    continue;
                }
                String original = originals.getOrDefault(change.getNodeId(), change.getOriginalText());
                String copiedReason = SourceFactDuplicationGate.rejectReason(
                        change.getNodeId(), original, change.getNewText(), ledger);
                if (copiedReason != null) {
                    copied++;
                    issues.add(new Issue(change.getNodeId(), copiedReason));
                    continue;
                }
                String action = ActionProvenanceGate.rejectReason(original, change.getNewText());
                if (action != null) {
                    issues.add(new Issue(change.getNodeId(), action));
                    continue;
                }
                List<String> evidence = entryTexts(change.getNodeId(), nodes, simulated, ledger);
                String relation = EvidenceRelationValidator.rejectReason(
                        change.getNewText(), original, evidence);
                if (relation != null) {
                    issues.add(new Issue(change.getNodeId(), relation));
                }
            }
        }
        issues.addAll(semanticDuplicates(nodes, simulated, originals, ledger));
        List<Issue> spread = spreadSupportingFacts(nodes, simulated, originals, ledger);
        copied += spread.size();
        issues.addAll(spread);
        return issues.isEmpty()
                ? Result.ok()
                : new Result(false, List.copyOf(issues), copied);
    }

    public static int copiedSupportingFactCount(List<ResumeDocumentNode> nodes, List<TailorChange> changes) {
        return evaluate(nodes, changes).copiedSupportingFacts();
    }

    private static List<Issue> spreadSupportingFacts(
            List<ResumeDocumentNode> nodes,
            Map<String, String> simulated,
            Map<String, String> originals,
            ClaimLedger ledger) {

        List<Issue> issues = new ArrayList<>();
        if (nodes == null || ledger == null) {
            return issues;
        }
        LinkedHashSet<String> seenEntries = new LinkedHashSet<>();
        for (ResumeDocumentNode node : nodes) {
            if (node == null || node.getNodeId() == null) {
                continue;
            }
            ClaimLedger.Entry entry = ledger.entryOf(node.getNodeId());
            if (entry == null || entry.nodes() == null || !seenEntries.add(entry.entryId())) {
                continue;
            }
            Map<String, Set<String>> originalOwners = owners(entry, originals);
            Map<String, Set<String>> simulatedOwners = owners(entry, simulated);
            for (Map.Entry<String, Set<String>> fact : simulatedOwners.entrySet()) {
                Set<String> before = originalOwners.getOrDefault(fact.getKey(), Set.of());
                if (before.isEmpty()) {
                    continue;
                }
                for (String nodeId : fact.getValue()) {
                    if (!before.contains(nodeId)) {
                        issues.add(new Issue(nodeId, RejectedTailorChange.SOURCE_FACT_WOULD_DUPLICATE));
                    }
                }
            }
        }
        return issues;
    }

    private static Map<String, Set<String>> owners(
            ClaimLedger.Entry entry,
            Map<String, String> texts) {

        LinkedHashMap<String, Set<String>> owners = new LinkedHashMap<>();
        int index = 0;
        for (ResumeDocumentNode node : entry.nodes()) {
            if (node == null || node.getNodeId() == null
                    || ConservativeEvidencePacket.isEntryHeading(node, index)) {
                index++;
                continue;
            }
            String text = texts.getOrDefault(node.getNodeId(), node.getText());
            if (text == null || text.isBlank()) {
                index++;
                continue;
            }
            for (ConservativeMaterialFacts.Fact fact : ConservativeMaterialFacts.distinctive(
                    ConservativeMaterialFacts.extract(text))) {
                if (fact == null || fact.key() == null) {
                    continue;
                }
                String token = fact.key().contains(":")
                        ? fact.key().substring(fact.key().indexOf(':') + 1)
                        : fact.key();
                if (ConservativeMaterialFacts.isGenericToken(token)) {
                    continue;
                }
                owners.computeIfAbsent(fact.key(), ignored -> new LinkedHashSet<>()).add(node.getNodeId());
            }
            index++;
        }
        return owners;
    }

    private static List<Issue> semanticDuplicates(
            List<ResumeDocumentNode> nodes,
            Map<String, String> simulated,
            Map<String, String> originals,
            ClaimLedger ledger) {

        List<Issue> issues = new ArrayList<>();
        if (nodes == null) {
            return issues;
        }
        for (int i = 0; i < nodes.size(); i++) {
            ResumeDocumentNode left = nodes.get(i);
            if (left == null || left.getNodeId() == null) {
                continue;
            }
            for (int j = i + 1; j < nodes.size(); j++) {
                ResumeDocumentNode right = nodes.get(j);
                if (right == null || right.getNodeId() == null) {
                    continue;
                }
                if (ledger != null && !ledger.sameEntry(left.getNodeId(), right.getNodeId())) {
                    continue;
                }
                String leftOriginal = originals.get(left.getNodeId());
                String rightOriginal = originals.get(right.getNodeId());
                String leftText = simulated.getOrDefault(left.getNodeId(), left.getText());
                String rightText = simulated.getOrDefault(right.getNodeId(), right.getText());
                boolean leftChanged = !sameText(leftText, leftOriginal);
                boolean rightChanged = !sameText(rightText, rightOriginal);
                if (!leftChanged && !rightChanged) {
                    continue;
                }
                if (ConservativeBatchSelector.HIGH_SIMILARITY > jaccard(leftText, rightText)) {
                    continue;
                }
                boolean originallySimilar = ConservativeBatchSelector.HIGH_SIMILARITY
                        <= jaccard(leftOriginal, rightOriginal);
                if (originallySimilar && !(leftChanged && rightChanged)) {
                    continue;
                }
                issues.add(new Issue(left.getNodeId(), RejectedTailorChange.LOW_VALUE_REWRITE));
                issues.add(new Issue(right.getNodeId(), RejectedTailorChange.LOW_VALUE_REWRITE));
            }
        }
        return issues;
    }

    private static double jaccard(String left, String right) {
        Set<String> a = ConservativeCandidateLocalScorer.tokens(left);
        Set<String> b = ConservativeCandidateLocalScorer.tokens(right);
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

    static Map<String, String> simulate(List<ResumeDocumentNode> nodes, List<TailorChange> changes) {
        LinkedHashMap<String, String> texts = new LinkedHashMap<>();
        if (nodes != null) {
            for (ResumeDocumentNode node : nodes) {
                if (node != null && node.getNodeId() != null) {
                    texts.put(node.getNodeId(), node.getText());
                }
            }
        }
        if (changes != null) {
            for (TailorChange change : changes) {
                if (change != null && change.getNodeId() != null && change.getNewText() != null) {
                    texts.put(change.getNodeId(), change.getNewText());
                }
            }
        }
        return texts;
    }

    private static Map<String, String> originals(List<ResumeDocumentNode> nodes) {
        LinkedHashMap<String, String> texts = new LinkedHashMap<>();
        if (nodes == null) {
            return texts;
        }
        for (ResumeDocumentNode node : nodes) {
            if (node != null && node.getNodeId() != null) {
                texts.put(node.getNodeId(), node.getText());
            }
        }
        return texts;
    }

    private static List<String> entryTexts(
            String nodeId,
            List<ResumeDocumentNode> nodes,
            Map<String, String> simulated,
            ClaimLedger ledger) {

        List<String> texts = new ArrayList<>();
        if (ledger == null || nodeId == null) {
            return texts;
        }
        ClaimLedger.Entry entry = ledger.entryOf(nodeId);
        if (entry == null || entry.nodes() == null) {
            return texts;
        }
        for (ResumeDocumentNode node : entry.nodes()) {
            if (node == null || node.getNodeId() == null) {
                continue;
            }
            texts.add(simulated.getOrDefault(node.getNodeId(), node.getText()));
        }
        if (texts.isEmpty() && nodes != null) {
            for (ResumeDocumentNode node : nodes) {
                if (node != null && node.getText() != null) {
                    texts.add(node.getText());
                }
            }
        }
        return texts;
    }

    private static Set<String> lockedNodeIds(List<TailorChange> lockedChanges) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (lockedChanges == null) {
            return ids;
        }
        for (TailorChange change : lockedChanges) {
            if (change != null && change.getNodeId() != null) {
                ids.add(change.getNodeId());
            }
        }
        return ids;
    }

    private static ConservativeBatchSelector.PlanPick lowestNewPick(
            List<ConservativeBatchSelector.PlanPick> selected,
            Result result,
            Set<String> lockedIds) {

        Set<String> offending = new LinkedHashSet<>();
        if (result != null && result.issues() != null) {
            for (Issue issue : result.issues()) {
                if (issue != null && issue.nodeId() != null && !lockedIds.contains(issue.nodeId())) {
                    offending.add(issue.nodeId());
                }
            }
        }
        List<ConservativeBatchSelector.PlanPick> pool = new ArrayList<>();
        for (ConservativeBatchSelector.PlanPick pick : selected) {
            String id = pick.change().getNodeId();
            if (lockedIds.contains(id)) {
                continue;
            }
            if (offending.isEmpty() || offending.contains(id)) {
                pool.add(pick);
            }
        }
        return pool.stream()
                .min(Comparator.comparingInt(pick -> pick.score() == null ? 0 : pick.score().total()))
                .orElse(null);
    }

    private static String reasonFor(ConservativeBatchSelector.PlanPick pick, Result result) {
        if (pick == null || result == null || result.issues() == null) {
            return RejectedTailorChange.LOW_VALUE_REWRITE;
        }
        String nodeId = pick.change().getNodeId();
        for (Issue issue : result.issues()) {
            if (issue != null && nodeId.equals(issue.nodeId())) {
                return issue.reasonCode();
            }
        }
        return RejectedTailorChange.LOW_VALUE_REWRITE;
    }

    private static boolean sameText(String left, String right) {
        String a = left == null ? "" : left.strip();
        String b = right == null ? "" : right.strip();
        return a.equals(b);
    }
}
