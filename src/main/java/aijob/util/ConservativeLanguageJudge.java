package aijob.util;

import aijob.dto.ConservativeCandidateRanking;
import aijob.dto.ConservativeCandidateScore;
import aijob.dto.ConservativeRewriteCandidate;
import aijob.dto.RejectedTailorChange;
import aijob.service.AiResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Final language-quality veto using the existing ranking Judge.
 * Hard-gate rejects are never restored. Judge may only drop or reorder survivors.
 */
public final class ConservativeLanguageJudge {

    static final String REWRITE_GOAL =
            "Veto unnatural, awkward, or not-better resume rewrites. "
                    + "Score grammaticality, natural resume English, semantic precision, "
                    + "and whether the candidate is actually better than the original. "
                    + "Do not invent facts. Do not write new text.";

    private static final Logger log = LoggerFactory.getLogger(ConservativeLanguageJudge.class);
    private static final int MIN_SCORE = 60;
    private static final List<String> AWKWARD = List.of(
            "utilizing java spring boot",
            "updates to products",
            "shopping cart processes",
            "suggest dynamic component selections"
    );

    private ConservativeLanguageJudge() {
    }

    public static ConservativeBatchSelector.Selection apply(
            AiResumeService ai,
            String jobDescription,
            ConservativeBatchSelector.Selection batch) {

        List<RejectedTailorChange> dropped = new ArrayList<>();
        if (batch != null && batch.dropped() != null) {
            dropped.addAll(batch.dropped());
        }
        List<ConservativeBatchSelector.PlanPick> selected = new ArrayList<>();
        if (batch != null && batch.selected() != null) {
            selected.addAll(batch.selected());
        }
        List<ConservativeBatchSelector.PlanPick> remaining = new ArrayList<>();
        for (ConservativeBatchSelector.PlanPick pick : selected) {
            if (awkward(pick)) {
                dropped.add(new RejectedTailorChange(
                        pick.change().getNodeId(),
                        RejectedTailorChange.LOW_VALUE_REWRITE));
            } else {
                remaining.add(pick);
            }
        }
        if (remaining.isEmpty()) {
            return new ConservativeBatchSelector.Selection(List.of(), List.copyOf(dropped));
        }
        return compareEachToOriginal(ai, jobDescription, remaining, dropped);
    }

    static boolean awkward(ConservativeBatchSelector.PlanPick pick) {
        if (pick == null || pick.change() == null || pick.change().getNewText() == null) {
            return false;
        }
        String lower = pick.change().getNewText().toLowerCase(Locale.ROOT);
        for (String phrase : AWKWARD) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    public static ConservativeCandidateRanking passthroughRanking(List<ConservativeRewriteCandidate> candidates) {
        ConservativeCandidateRanking ranking = new ConservativeCandidateRanking();
        ranking.setSelectedIndex(candidates == null || candidates.isEmpty() ? null
                : candidates.size() == 1 ? 0 : 1);
        ranking.setReason("passthrough");
        List<ConservativeCandidateScore> scores = new ArrayList<>();
        if (candidates != null) {
            for (int i = 0; i < candidates.size(); i++) {
                ConservativeCandidateScore row = new ConservativeCandidateScore();
                row.setIndex(i);
                row.setGrammarScore(90);
                row.setClarityScore(90);
                row.setFactualPreservationScore(100);
                row.setTotalScore(90);
                scores.add(row);
            }
        }
        ranking.setScores(scores);
        return ranking;
    }

    private static ConservativeBatchSelector.Selection compareEachToOriginal(
            AiResumeService ai,
            String jobDescription,
            List<ConservativeBatchSelector.PlanPick> remaining,
            List<RejectedTailorChange> dropped) {

        List<ConservativeBatchSelector.PlanPick> kept = new ArrayList<>();
        List<String> terms = ConservativeTailorPlanner.extractTerms(jobDescription);
        for (ConservativeBatchSelector.PlanPick pick : remaining) {
            ConservativeCandidateRanking ranking = rankPair(ai, terms, pick);
            if (ranking == null) {
                if (grammarEstablished(pick)) {
                    annotate(pick, "KEEP", 70);
                    kept.add(pick);
                } else {
                    annotate(pick, "VETO", 0);
                    dropped.add(new RejectedTailorChange(
                            pick.change().getNodeId(),
                            RejectedTailorChange.LOW_VALUE_REWRITE));
                }
                continue;
            }
            if (Integer.valueOf(0).equals(ranking.getSelectedIndex()) || vetoed(scoreAt(ranking, 1))) {
                annotate(pick, "VETO", confidence(scoreAt(ranking, 1)));
                dropped.add(new RejectedTailorChange(
                        pick.change().getNodeId(),
                        RejectedTailorChange.LOW_VALUE_REWRITE));
            } else {
                annotate(pick, "KEEP", confidence(scoreAt(ranking, 1)));
                kept.add(pick);
            }
        }
        return new ConservativeBatchSelector.Selection(List.copyOf(kept), List.copyOf(dropped));
    }

    private static ConservativeCandidateRanking rankPair(
            AiResumeService ai,
            List<String> terms,
            ConservativeBatchSelector.PlanPick pick) {

        if (ai == null || pick == null || pick.change() == null) {
            return null;
        }
        try {
            ConservativeRewriteCandidate original = new ConservativeRewriteCandidate();
            original.setNodeId(pick.change().getNodeId());
            original.setNewText(pick.change().getOriginalText());
            ConservativeRewriteCandidate rewrite = new ConservativeRewriteCandidate();
            rewrite.setNodeId(pick.change().getNodeId());
            rewrite.setNewText(pick.change().getNewText());
            return ai.rankConservativeCandidates(
                    pick.change().getOriginalText(),
                    REWRITE_GOAL,
                    terms,
                    List.of(original, rewrite));
        } catch (RuntimeException ex) {
            log.warn("Language judge failed: {}", ex.getClass().getSimpleName());
            return null;
        }
    }

    private static void annotate(ConservativeBatchSelector.PlanPick pick, String verdict, int confidence) {
        if (pick == null || pick.change() == null) {
            return;
        }
        pick.change().setJudgeVerdict(verdict);
        pick.change().setJudgeConfidence(confidence);
    }

    private static int confidence(ConservativeCandidateScore score) {
        if (score == null || score.getTotalScore() == null) {
            return 0;
        }
        return score.getTotalScore();
    }

    private static ConservativeBatchSelector.Selection grammarOnly(
            List<ConservativeBatchSelector.PlanPick> remaining,
            List<RejectedTailorChange> dropped) {

        List<ConservativeBatchSelector.PlanPick> kept = new ArrayList<>();
        for (ConservativeBatchSelector.PlanPick pick : remaining) {
            if (grammarEstablished(pick)) {
                kept.add(pick);
            } else {
                dropped.add(new RejectedTailorChange(
                        pick.change().getNodeId(),
                        RejectedTailorChange.LOW_VALUE_REWRITE));
            }
        }
        return new ConservativeBatchSelector.Selection(List.copyOf(kept), List.copyOf(dropped));
    }

    private static ConservativeBatchSelector.Selection applyRanking(
            List<ConservativeBatchSelector.PlanPick> remaining,
            List<RejectedTailorChange> dropped,
            ConservativeCandidateRanking ranking) {

        List<ConservativeBatchSelector.PlanPick> kept = new ArrayList<>();
        for (int i = 0; i < remaining.size(); i++) {
            ConservativeBatchSelector.PlanPick pick = remaining.get(i);
            if (vetoed(scoreAt(ranking, i))) {
                dropped.add(new RejectedTailorChange(
                        pick.change().getNodeId(),
                        RejectedTailorChange.LOW_VALUE_REWRITE));
            } else {
                kept.add(pick);
            }
        }
        Integer selected = ranking.getSelectedIndex();
        if (selected != null && selected >= 0 && selected < remaining.size()) {
            ConservativeBatchSelector.PlanPick preferred = remaining.get(selected);
            if (kept.contains(preferred) && kept.indexOf(preferred) > 0) {
                kept.remove(preferred);
                kept.add(0, preferred);
            }
        }
        return new ConservativeBatchSelector.Selection(List.copyOf(kept), List.copyOf(dropped));
    }

    private static boolean grammarEstablished(ConservativeBatchSelector.PlanPick pick) {
        if (pick == null || pick.change() == null) {
            return false;
        }
        String uplift = pick.score() == null ? "" : pick.score().upliftType();
        return SurfaceRewriteEquivalence.grammarFixEstablished(
                pick.change().getOriginalText(),
                pick.change().getNewText(),
                pick.intent(),
                uplift);
    }

    private static boolean vetoed(ConservativeCandidateScore score) {
        if (score == null) {
            return false;
        }
        if (score.getFactualPreservationScore() != null && score.getFactualPreservationScore() < MIN_SCORE) {
            return true;
        }
        return score.getGrammarScore() != null && score.getGrammarScore() < MIN_SCORE;
    }

    private static ConservativeCandidateScore scoreAt(ConservativeCandidateRanking ranking, int index) {
        if (ranking == null || ranking.getScores() == null) {
            return null;
        }
        for (ConservativeCandidateScore score : ranking.getScores()) {
            if (score != null && score.getIndex() != null && score.getIndex() == index) {
                return score;
            }
        }
        if (index >= 0 && index < ranking.getScores().size()) {
            return ranking.getScores().get(index);
        }
        return null;
    }

    private static List<ConservativeRewriteCandidate> toCandidates(
            List<ConservativeBatchSelector.PlanPick> picks) {

        List<ConservativeRewriteCandidate> candidates = new ArrayList<>();
        for (ConservativeBatchSelector.PlanPick pick : picks) {
            ConservativeRewriteCandidate candidate = new ConservativeRewriteCandidate();
            candidate.setNodeId(pick.change().getNodeId());
            candidate.setNewText(pick.change().getNewText());
            candidates.add(candidate);
        }
        return candidates;
    }

    private static String joinedOriginals(List<ConservativeBatchSelector.PlanPick> picks) {
        StringBuilder text = new StringBuilder();
        for (ConservativeBatchSelector.PlanPick pick : picks) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(pick.change().getOriginalText() == null ? "" : pick.change().getOriginalText());
        }
        return text.toString();
    }
}
