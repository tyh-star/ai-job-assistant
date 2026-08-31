package aijob.util;

import aijob.dto.ConservativeRewritePlan;
import aijob.dto.RejectedTailorChange;
import aijob.dto.RewriteIntent;
import aijob.dto.TailorChange;
import aijob.dto.UpliftType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Continue-round selection. Existing proposal suggestions stay locked.
 * Does not change first-round {@link ConservativeBatchSelector} behavior.
 */
public final class ConservativeContinueSelector {

    public static final int MAX_ADDED = ConservativeBatchSelector.MAX_SUGGESTIONS;

    private ConservativeContinueSelector() {
    }

    public static ConservativeBatchSelector.Selection select(
            List<TailorChange> lockedChanges,
            Map<String, String> lockedEntryByNodeId,
            List<ConservativeBatchSelector.PlanPick> newcomers) {

        return select(lockedChanges, lockedEntryByNodeId, newcomers, Set.of(), null);
    }

    public static ConservativeBatchSelector.Selection select(
            List<TailorChange> lockedChanges,
            Map<String, String> lockedEntryByNodeId,
            List<ConservativeBatchSelector.PlanPick> newcomers,
            Set<String> reservedTargetNodeIds,
            Set<String> lockedSupportingNodeIds) {

        List<TailorChange> locked = lockedChanges == null ? List.of() : lockedChanges;
        Map<String, String> entryByNode = lockedEntryByNodeId == null
                ? Map.of()
                : lockedEntryByNodeId;
        Set<String> lockedIds = new LinkedHashSet<>();
        List<String> lockedTexts = new ArrayList<>();
        Map<String, Integer> lockedEntryCounts = new LinkedHashMap<>();
        boolean lockedHasGrammar = false;
        List<ConservativeBatchSelector.PlanPick> lockedPicks = new ArrayList<>();
        for (TailorChange change : locked) {
            if (change == null || change.getNodeId() == null || change.getNodeId().isBlank()) {
                continue;
            }
            lockedIds.add(change.getNodeId());
            lockedTexts.add(change.getNewText());
            String entry = entryByNode.getOrDefault(change.getNodeId(), "");
            lockedEntryCounts.merge(entry, 1, Integer::sum);
            if (UpliftType.GRAMMAR_FIX.equals(change.getUpliftType())) {
                lockedHasGrammar = true;
            }
            lockedPicks.add(dummyLockedPick(change, entry));
        }
        Set<String> lockedSupporting = lockedSupportingNodeIds == null
                ? supportingFromEvidence(locked)
                : new LinkedHashSet<>(lockedSupportingNodeIds);
        ConservativeBatchSelector.Context context = new ConservativeBatchSelector.Context(
                lockedPicks,
                reservedTargetNodeIds == null ? Set.of() : reservedTargetNodeIds,
                lockedSupporting
        );

        List<ConservativeBatchSelector.PlanPick> eligible = new ArrayList<>();
        List<RejectedTailorChange> preDropped = new ArrayList<>();
        if (newcomers != null) {
            for (ConservativeBatchSelector.PlanPick pick : newcomers) {
                String reason = incompatibility(
                        pick, locked, lockedIds, lockedTexts, lockedEntryCounts, lockedHasGrammar, lockedPicks);
                if (reason == null && reusesLockedSupporting(pick, lockedSupporting)) {
                    reason = RejectedTailorChange.LOW_VALUE_REWRITE;
                }
                if (reason == null) {
                    eligible.add(pick);
                } else if (pick.change() != null && pick.change().getNodeId() != null) {
                    preDropped.add(new RejectedTailorChange(pick.change().getNodeId(), reason));
                }
            }
        }

        ConservativeBatchSelector.Selection batch = ConservativeBatchSelector.select(eligible, context);
        List<ConservativeBatchSelector.PlanPick> kept = new ArrayList<>();
        List<RejectedTailorChange> dropped = new ArrayList<>(preDropped);
        dropped.addAll(batch.dropped());
        List<String> acceptedTexts = new ArrayList<>(lockedTexts);
        Set<String> acceptedIds = new LinkedHashSet<>(lockedIds);
        Map<String, Integer> acceptedEntries = new LinkedHashMap<>(lockedEntryCounts);
        boolean acceptedGrammar = lockedHasGrammar;
        List<ConservativeBatchSelector.PlanPick> acceptedPicks = new ArrayList<>(lockedPicks);
        for (ConservativeBatchSelector.PlanPick pick : batch.selected()) {
            String reason = incompatibility(
                    pick, locked, acceptedIds, acceptedTexts, acceptedEntries, acceptedGrammar, acceptedPicks);
            if (reason != null) {
                dropped.add(new RejectedTailorChange(pick.change().getNodeId(), reason));
                continue;
            }
            kept.add(pick);
            acceptedIds.add(pick.change().getNodeId());
            acceptedTexts.add(pick.change().getNewText());
            acceptedEntries.merge(pick.entryKey() == null ? "" : pick.entryKey(), 1, Integer::sum);
            if (pick.score() != null && pick.score().grammarOnly()) {
                acceptedGrammar = true;
            }
            acceptedPicks.add(pick);
        }
        return new ConservativeBatchSelector.Selection(List.copyOf(kept), List.copyOf(dropped));
    }

    public static boolean nodeFullySubsumed(String originalText, List<TailorChange> lockedChanges) {
        if (originalText == null || originalText.isBlank() || lockedChanges == null) {
            return false;
        }
        for (TailorChange locked : lockedChanges) {
            if (locked == null || locked.getNewText() == null) {
                continue;
            }
            if (ConservativeMaterialFacts.fullySubsumes(originalText, locked.getNewText())) {
                return true;
            }
        }
        return false;
    }

    static String incompatibility(
            ConservativeBatchSelector.PlanPick pick,
            List<TailorChange> locked,
            Set<String> lockedIds,
            List<String> lockedTexts,
            Map<String, Integer> lockedEntryCounts,
            boolean lockedHasGrammar,
            List<ConservativeBatchSelector.PlanPick> lockedPicks) {

        if (pick == null || pick.change() == null) {
            return RejectedTailorChange.EMPTY_REQUIRED_FIELD;
        }
        String nodeId = pick.change().getNodeId();
        if (nodeId == null || nodeId.isBlank() || lockedIds.contains(nodeId)) {
            return RejectedTailorChange.DUPLICATE_NODE_ID;
        }
        String newText = pick.change().getNewText();
        if (newText == null || newText.isBlank()) {
            return RejectedTailorChange.EMPTY_REQUIRED_FIELD;
        }
        if (nodeFullySubsumed(pick.change().getOriginalText(), locked)) {
            return RejectedTailorChange.LOW_VALUE_REWRITE;
        }
        if (ConservativeMaterialFacts.uniqueKeys(newText, lockedTexts).isEmpty()) {
            return RejectedTailorChange.LOW_VALUE_REWRITE;
        }
        for (TailorChange existing : locked) {
            if (existing == null || existing.getNewText() == null) {
                continue;
            }
            if (ConservativeBatchSelector.jaccard(existing.getNewText(), newText)
                    >= ConservativeBatchSelector.HIGH_SIMILARITY) {
                return RejectedTailorChange.LOW_VALUE_REWRITE;
            }
        }
        for (ConservativeBatchSelector.PlanPick lockedPick : lockedPicks) {
            if (ConservativeBatchSelector.circularEnrichment(lockedPick, pick)) {
                return RejectedTailorChange.LOW_VALUE_REWRITE;
            }
        }
        return null;
    }

    private static ConservativeBatchSelector.PlanPick dummyLockedPick(TailorChange change, String entryKey) {
        ConservativeRewritePlan rewritePlan = new ConservativeRewritePlan(
                change.getNodeId(),
                change.getNodeId(),
                entryKey == null ? "" : entryKey,
                List.of(),
                RewriteIntent.SAME_ENTRY_EVIDENCE_ENRICHMENT,
                List.of(),
                List.of(),
                change.getEvidenceNodeIds() == null ? List.of() : change.getEvidenceNodeIds(),
                List.of(),
                List.of(),
                "",
                170,
                "BULLET",
                change.getEvidenceNodeIds() == null ? List.of() : change.getEvidenceNodeIds(),
                "",
                0,
                ""
        );
        List<String> used = change.getEvidenceNodeIds() == null ? List.of() : change.getEvidenceNodeIds();
        return new ConservativeBatchSelector.PlanPick(rewritePlan, change, dummyLockedScore(change), used);
    }

    private static ConservativeCandidateLocalScorer.Score dummyLockedScore(TailorChange change) {
        boolean grammar = UpliftType.GRAMMAR_FIX.equals(change.getUpliftType());
        return new ConservativeCandidateLocalScorer.Score(
                100, 80, 90, 80, 80, 80, 90, 60, 30, true, true,
                change.getUpliftType(),
                grammar,
                grammar ? RewriteIntent.GRAMMAR_FIX : RewriteIntent.CLARITY_IMPROVEMENT,
                null);
    }

    private static Set<String> supportingFromEvidence(List<TailorChange> locked) {
        LinkedHashSet<String> supporting = new LinkedHashSet<>();
        for (TailorChange change : locked) {
            if (change == null || change.getEvidenceNodeIds() == null) {
                continue;
            }
            for (String id : change.getEvidenceNodeIds()) {
                if (id != null && !id.isBlank() && !id.equals(change.getNodeId())) {
                    supporting.add(id);
                }
            }
        }
        return supporting;
    }

    private static boolean reusesLockedSupporting(
            ConservativeBatchSelector.PlanPick pick,
            Set<String> lockedSupporting) {

        if (pick == null || lockedSupporting == null || lockedSupporting.isEmpty()) {
            return false;
        }
        if (pick.usedFactNodeIds() == null) {
            return false;
        }
        for (String id : pick.usedFactNodeIds()) {
            if (id != null && !id.equals(pick.change().getNodeId()) && lockedSupporting.contains(id)) {
                return true;
            }
        }
        return false;
    }
}
