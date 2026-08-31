package aijob.util;

import aijob.dto.ConservativeCandidateRanking;
import aijob.dto.ConservativeCandidateScore;
import aijob.dto.ConservativeRewriteCandidate;
import aijob.dto.UpliftType;

import java.util.ArrayList;
import java.util.List;

/**
 * Local deterministic ranking used when an independent Judge is unavailable.
 * Only admitted hard-gate survivors are ranked. Judge may only tie-break.
 */
public final class LocalConservativeCandidateRanker {

    private LocalConservativeCandidateRanker() {
    }

    public static ConservativeCandidateRanking rank(
            String originalText,
            String jobDescription,
            List<ScoredCandidate> passing) {

        ConservativeCandidateRanking ranking = new ConservativeCandidateRanking();
        if (passing == null || passing.isEmpty()) {
            ranking.setSelectedIndex(null);
            ranking.setReason("No passing candidates");
            ranking.setScores(List.of());
            return ranking;
        }
        int bestIndex = 0;
        int bestTotal = Integer.MIN_VALUE;
        List<ConservativeCandidateScore> scores = new ArrayList<>();
        for (int i = 0; i < passing.size(); i++) {
            ScoredCandidate candidate = passing.get(i);
            ConservativeCandidateScore row = candidate.localScore() == null
                    ? fromQuality(i, candidate)
                    : candidate.localScore().toRow(i);
            scores.add(row);
            if (candidate.localScore() != null && !candidate.localScore().admitted()) {
                continue;
            }
            int total = candidate.localScore() != null
                    ? candidate.localScore().total()
                    : candidate.scores().total() + UpliftType.rank(candidate.upliftType()) * 40;
            if (total > bestTotal) {
                bestTotal = total;
                bestIndex = i;
            }
        }
        ranking.setScores(scores);
        ranking.setSelectedIndex(bestIndex);
        ranking.setReason("Selected the highest local T2C quality score among admitted candidates");
        return ranking;
    }

    public static List<Integer> closeTies(List<ScoredCandidate> passing) {
        if (passing == null || passing.isEmpty()) {
            return List.of();
        }
        ConservativeCandidateRanking ranked = rank("", "", passing);
        if (ranked.getSelectedIndex() == null) {
            return List.of();
        }
        int best = passing.get(ranked.getSelectedIndex()).localScore() == null
                ? Integer.MIN_VALUE
                : passing.get(ranked.getSelectedIndex()).localScore().total();
        List<Integer> ties = new ArrayList<>();
        for (int i = 0; i < passing.size(); i++) {
            ConservativeCandidateLocalScorer.Score score = passing.get(i).localScore();
            if (score != null && score.admitted()
                    && best - score.total() <= ConservativeCandidateLocalScorer.TIE_WINDOW) {
                ties.add(i);
            }
        }
        return ties;
    }

    private static ConservativeCandidateScore fromQuality(int index, ScoredCandidate candidate) {
        ConservativeRewriteQualityEvaluator.QualityScores quality = candidate.scores();
        ConservativeCandidateScore row = new ConservativeCandidateScore();
        row.setIndex(index);
        row.setGrammarScore(quality.grammarScore());
        row.setClarityScore(quality.clarityScore());
        row.setActionVerbScore(quality.actionVerbScore());
        row.setSpecificityScore(quality.specificityScore());
        row.setConcisenessScore(quality.concisenessScore());
        row.setJobRelevanceScore(quality.jobRelevanceScore());
        row.setFactualPreservationScore(quality.factualPreservationScore());
        return row;
    }

    public record ScoredCandidate(
            ConservativeRewriteCandidate candidate,
            ConservativeRewriteQualityEvaluator.QualityScores scores,
            String upliftType,
            List<String> usedFactNodeIds,
            ConservativeCandidateLocalScorer.Score localScore
    ) {
        public ScoredCandidate(
                ConservativeRewriteCandidate candidate,
                ConservativeRewriteQualityEvaluator.QualityScores scores) {
            this(candidate, scores, UpliftType.CLARITY_IMPROVEMENT, List.of(), null);
        }

        public ScoredCandidate(
                ConservativeRewriteCandidate candidate,
                ConservativeRewriteQualityEvaluator.QualityScores scores,
                String upliftType,
                List<String> usedFactNodeIds) {
            this(candidate, scores, upliftType, usedFactNodeIds, null);
        }
    }
}
