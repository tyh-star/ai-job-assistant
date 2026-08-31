package aijob.tailor.v3;

import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.util.CanonicalEntryKeyResolver;
import aijob.util.ClaimLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class V3BasicTailorService {

    private static final Logger log = LoggerFactory.getLogger(V3BasicTailorService.class);
    private static final int MAX_PARALLEL = 2;

    private final V3EntryRewriteGenerator generator;
    private final V3ResumeLanguageReviewer languageReviewer;

    public V3BasicTailorService(
            V3EntryRewriteGenerator generator,
            V3ResumeLanguageReviewer languageReviewer) {
        this.generator = generator;
        this.languageReviewer = languageReviewer;
    }

    public V3BasicRunResult run(List<ResumeDocumentNode> nodes, String jobDescription) {
        ClaimLedger ledger = ClaimLedger.from(nodes);
        V3ResumeFactGraphs graphs = V3EntryFactGraphFactory.from(ledger);
        CanonicalEntryKeyResolver.Index index = CanonicalEntryKeyResolver.index(ledger);

        List<EntryJob> jobs = new ArrayList<>();
        int skippedUnchanged = 0;
        for (ClaimLedger.Entry entry : ledger.entries()) {
            String key = index.keyForEntryId(entry.entryId());
            if (key.isBlank()) {
                continue;
            }
            List<V3WritableNode> writable = V3WritableEntryNodes.of(entry, nodes);
            V3EntryFactGraph graph = graphs.entry(key);
            if (writable.isEmpty() || graph == null) {
                skippedUnchanged++;
                continue;
            }
            jobs.add(new EntryJob(key, entry, graph, writable));
        }

        AtomicInteger generateCalls = new AtomicInteger();
        AtomicInteger repairCalls = new AtomicInteger();
        List<EntryWork> works = runJobs(jobs, graphs, jobDescription, generateCalls, repairCalls);

        int languageReviewCalls = 0;
        Map<String, V3SelectionChoice> reviewerChoices = new LinkedHashMap<>();
        Map<String, String> reviewerReasons = new LinkedHashMap<>();
        List<V3ResumeLanguageReviewer.EntryComparison> comparisons = new ArrayList<>();
        for (EntryWork work : works) {
            if (work.clarityValid || work.targetedValid || work.hybrids.hasAdmitted()) {
                comparisons.add(toComparison(work));
            }
        }
        if (!comparisons.isEmpty()) {
            languageReviewCalls = 1;
            try {
                for (V3ResumeLanguageReviewer.Choice choice : languageReviewer.review(jobDescription, comparisons)) {
                    reviewerChoices.put(choice.canonicalEntryKey(), choice.selection());
                    reviewerReasons.put(choice.canonicalEntryKey(), choice.reason());
                }
            } catch (RuntimeException ex) {
                log.warn("V3 language review unavailable: {}", ex.getClass().getSimpleName());
                for (EntryWork work : works) {
                    V3SelectionChoice fallback = bestLocalChoice(work);
                    reviewerChoices.put(work.key, fallback);
                    reviewerReasons.put(work.key, "reviewer-unavailable-local-score");
                }
            }
        }

        List<TailorChange> changes = new ArrayList<>();
        List<V3BasicRunResult.V3EntryTrace> traces = new ArrayList<>();
        int rewritten = 0;
        int unchanged = skippedUnchanged;
        for (EntryWork work : works) {
            String reviewerReason = reviewerReasons.get(work.key);
            V3SelectionChoice reviewerChoice = reviewerChoices.get(work.key);
            if (reviewerChoice == null || "missing-choice-original".equals(reviewerReason)) {
                V3SelectionChoice local = bestLocalChoice(work);
                if (reviewerChoice == null || local != V3SelectionChoice.ORIGINAL) {
                    reviewerChoice = local;
                    reviewerReason = local == V3SelectionChoice.ORIGINAL
                            ? (reviewerReason == null ? "original-wins" : reviewerReason)
                            : "reviewer-unmatched-local-score";
                }
            }
            Selected selected = select(work, reviewerChoice, reviewerReason);
            log.info(
                    "v3.basic.entry key={} chosen={} noChangeReason={} prompt={} model={} latencyMs={} tokens={}/{}",
                    work.key,
                    selected.choice,
                    selected.noChangeReason,
                    work.result.telemetry().promptVersion(),
                    work.result.telemetry().model(),
                    work.result.telemetry().latencyMs(),
                    work.result.telemetry().promptTokens(),
                    work.result.telemetry().completionTokens());
            if (selected.choice == V3SelectionChoice.ORIGINAL
                    || selected.choice == V3SelectionChoice.REJECT_ALL_AI
                    || selected.variant == null) {
                unchanged++;
                traces.add(trace(work, V3RewriteDecision.NO_CHANGE, selected, work.originalBullets));
                continue;
            }
            List<TailorChange> entryChanges = V3EqualCountChangeAdapter.toChanges(selected.variant, work.writable);
            if (entryChanges.isEmpty()) {
                unchanged++;
                traces.add(trace(work, V3RewriteDecision.NO_CHANGE, selected, work.originalBullets));
                continue;
            }
            changes.addAll(entryChanges);
            rewritten++;
            traces.add(trace(work, V3RewriteDecision.REWRITE, selected, selected.bullets));
        }
        return new V3BasicRunResult(
                changes,
                List.of(),
                rewritten,
                unchanged,
                generateCalls.get(),
                repairCalls.get(),
                languageReviewCalls,
                traces);
    }

    private List<EntryWork> runJobs(
            List<EntryJob> jobs,
            V3ResumeFactGraphs graphs,
            String jobDescription,
            AtomicInteger generateCalls,
            AtomicInteger repairCalls) {
        if (jobs.isEmpty()) {
            return List.of();
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_PARALLEL, jobs.size()));
        try {
            List<Future<EntryWork>> futures = new ArrayList<>();
            for (EntryJob job : jobs) {
                futures.add(pool.submit(() -> processEntry(job, graphs, jobDescription, generateCalls, repairCalls)));
            }
            List<EntryWork> works = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                EntryJob job = jobs.get(i);
                try {
                    works.add(futures.get(i).get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    works.add(EntryWork.originalOnly(job));
                } catch (ExecutionException ex) {
                    log.warn("V3 entry failed, keeping original: {} {}", job.key, ex.getCause() == null
                            ? ex.getClass().getSimpleName()
                            : ex.getCause().getClass().getSimpleName());
                    works.add(EntryWork.originalOnly(job));
                }
            }
            return works;
        } finally {
            pool.shutdownNow();
        }
    }

    private EntryWork processEntry(
            EntryJob job,
            V3ResumeFactGraphs graphs,
            String jobDescription,
            AtomicInteger generateCalls,
            AtomicInteger repairCalls) {
        try {
            List<V3RankedRequirement> ranked = V3JdRequirementRanker.rank(jobDescription, job.graph);
            V3EntryRewriteRequest request = buildRequest(job, graphs, jobDescription, ranked, "");
            V3EntryRewriteResult generated = generator.generate(request);
            generateCalls.incrementAndGet();
            EntryWork work = safeEvaluate(job, graphs, ranked, generated);
            if (!work.clarityValid && !work.targetedValid && hasRewriteAttempt(generated)) {
                work.repairReason = (work.validatorCode + " " + work.validatorDetail).trim();
                generated = generator.generate(buildRequest(
                        job, graphs, jobDescription, ranked, work.repairReason));
                repairCalls.incrementAndGet();
                String repairReason = work.repairReason;
                work = safeEvaluate(job, graphs, ranked, generated);
                work.repairReason = repairReason;
            }
            return work;
        } catch (RuntimeException ex) {
            log.warn("V3 entry failed, keeping original: {} {}", job.key, ex.getClass().getSimpleName());
            return EntryWork.originalOnly(job);
        }
    }

    private static boolean hasRewriteAttempt(V3EntryRewriteResult generated) {
        if (generated == null) {
            return false;
        }
        return generated.clarityFirst().decision() == V3RewriteDecision.REWRITE
                || generated.jdTargeted().decision() == V3RewriteDecision.REWRITE;
    }

    private static EntryWork safeEvaluate(
            EntryJob job,
            V3ResumeFactGraphs graphs,
            List<V3RankedRequirement> ranked,
            V3EntryRewriteResult generated) {
        try {
            return evaluate(job, graphs, ranked, generated);
        } catch (RuntimeException ex) {
            log.warn("V3 evaluate failed, treating as invalid: {} {}", job.key, ex.getClass().getSimpleName());
            return new EntryWork(
                    job.key,
                    job.graph,
                    job.writable,
                    job.graph.originalBulletTexts(),
                    ranked,
                    generated == null ? V3EntryRewriteResult.noChange(job.key) : generated,
                    false,
                    false,
                    false,
                    false,
                    null,
                    null,
                    V3EntryQualityScorer.scoreOriginal(job.graph, job.graph.originalBulletTexts(), ranked),
                    "EVALUATE_FAILED",
                    ex.getClass().getSimpleName(),
                    null,
                    null,
                    V3HybridSelector.Result.empty());
        }
    }

    private static EntryWork evaluate(
            EntryJob job,
            V3ResumeFactGraphs graphs,
            List<V3RankedRequirement> ranked,
            V3EntryRewriteResult generated) {
        List<String> originalBullets = job.graph.originalBulletTexts();
        V3QualityScore originalScore = V3EntryQualityScorer.scoreOriginal(job.graph, originalBullets, ranked);
        VariantEval clarity = evaluateVariant(graphs, job, generated.clarityFirst(), originalBullets, originalScore, ranked);
        VariantEval targeted = evaluateVariant(graphs, job, generated.jdTargeted(), originalBullets, originalScore, ranked);
        V3HybridSelector.Result hybrids = V3HybridSelector.select(
                graphs,
                job.graph,
                job.writable,
                originalBullets,
                generated.clarityFirst(),
                generated.jdTargeted(),
                originalScore,
                ranked);
        return new EntryWork(
                job.key,
                job.graph,
                job.writable,
                originalBullets,
                ranked,
                generated == null ? V3EntryRewriteResult.noChange(job.key) : generated,
                clarity.valid,
                targeted.valid,
                clarity.admitted,
                targeted.admitted,
                clarity.score,
                targeted.score,
                originalScore,
                firstNonBlank(clarity.validatorCode, targeted.validatorCode),
                firstNonBlank(clarity.detail, targeted.detail),
                clarity.coverage,
                targeted.coverage,
                hybrids);
    }

    private static VariantEval evaluateVariant(
            V3ResumeFactGraphs graphs,
            EntryJob job,
            V3EntryRewriteVariant variant,
            List<String> originalBullets,
            V3QualityScore originalScore,
            List<V3RankedRequirement> ranked) {
        if (variant == null || variant.decision() != V3RewriteDecision.REWRITE) {
            return VariantEval.invalid("NO_CHANGE", "");
        }
        if (variant.rewrittenNodes().size() != job.writable.size()) {
            return VariantEval.invalid(V3ValidationDecision.IDENTITY_MUTATION, "bullet count mismatch");
        }
        List<String> bullets = variant.bulletsInNodeOrder(job.writable);
        V3EntryRewrite rewrite = new V3EntryRewrite(
                job.graph.canonicalEntryKey(),
                job.graph.originalHeading(),
                job.graph.originalDateText(),
                bullets);
        V3ValidationDecision decision = V3EntryRewriteValidator.validate(graphs, rewrite);
        if (!decision.accepted()) {
            return VariantEval.invalid(decision.reasonCode(), decision.detail());
        }
        V3FactCoverage.Result coverage = V3FactCoverage.compute(job.graph, originalBullets, bullets);
        V3QualityScore score = V3EntryQualityScorer.scoreVariant(job.graph, originalBullets, bullets, ranked);
        boolean admitted = V3EntryQualityScorer.admit(score, originalScore);
        return new VariantEval(true, admitted, score, coverage, decision.reasonCode(), decision.detail());
    }

    private static V3EntryRewriteRequest buildRequest(
            EntryJob job,
            V3ResumeFactGraphs graphs,
            String jobDescription,
            List<V3RankedRequirement> ranked,
            String repairError) {
        List<String> ids = new ArrayList<>();
        for (V3RankedRequirement requirement : ranked) {
            ids.add(requirement.id());
        }
        return new V3EntryRewriteRequest(
                V3ProductMode.BASIC,
                job.key,
                job.entry.sectionRole() == null ? "" : job.entry.sectionRole(),
                job.graph.originalHeading(),
                job.graph.originalDateText(),
                jobDescription,
                ids,
                ranked,
                job.writable,
                job.graph,
                graphs,
                List.of(),
                List.of(),
                job.writable.size(),
                V3WritableEntryNodes.characterBudget(job.writable),
                repairError);
    }

    private static V3ResumeLanguageReviewer.EntryComparison toComparison(EntryWork work) {
        V3HybridSelector.Hybrid hybrid1 = work.hybrids.hybrid1();
        V3HybridSelector.Hybrid hybrid2 = work.hybrids.hybrid2();
        return new V3ResumeLanguageReviewer.EntryComparison(
                work.key,
                work.originalBullets,
                work.graph.originalEntryText(),
                work.originalScore,
                work.clarityValid ? work.result.clarityFirst() : null,
                work.clarityScore,
                work.targetedValid ? work.result.jdTargeted() : null,
                work.targetedScore,
                work.ranked,
                hybrid1 != null && hybrid1.admitted() ? hybrid1.variant() : null,
                hybrid1 == null ? null : hybrid1.score(),
                hybrid1 == null ? List.of() : hybrid1.nodeSources(),
                hybrid2 != null && hybrid2.admitted() ? hybrid2.variant() : null,
                hybrid2 == null ? null : hybrid2.score(),
                hybrid2 == null ? List.of() : hybrid2.nodeSources());
    }

    private static V3SelectionChoice bestLocalChoice(EntryWork work) {
        int originalTotal = work.originalScore == null ? 0 : work.originalScore.total();
        V3SelectionChoice best = V3SelectionChoice.ORIGINAL;
        int bestTotal = originalTotal;
        if (work.clarityAdmitted && work.clarityScore != null && work.clarityScore.total() > bestTotal) {
            best = V3SelectionChoice.CLARITY_FIRST;
            bestTotal = work.clarityScore.total();
        }
        if (work.targetedAdmitted && work.targetedScore != null && work.targetedScore.total() > bestTotal) {
            best = V3SelectionChoice.JD_TARGETED;
            bestTotal = work.targetedScore.total();
        }
        if (admitted(work.hybrids.hybrid1()) && work.hybrids.hybrid1().score().total() > bestTotal) {
            best = V3SelectionChoice.HYBRID_1;
            bestTotal = work.hybrids.hybrid1().score().total();
        }
        if (admitted(work.hybrids.hybrid2()) && work.hybrids.hybrid2().score().total() > bestTotal) {
            best = V3SelectionChoice.HYBRID_2;
            bestTotal = work.hybrids.hybrid2().score().total();
        }
        if (best == V3SelectionChoice.ORIGINAL || bestTotal < originalTotal + 8) {
            return V3SelectionChoice.ORIGINAL;
        }
        return best;
    }

    private static boolean admitted(V3HybridSelector.Hybrid hybrid) {
        return hybrid != null && hybrid.admitted() && hybrid.score() != null;
    }

    private static Selected select(EntryWork work, V3SelectionChoice reviewerChoice, String reason) {
        V3SelectionChoice choice = reviewerChoice == null ? V3SelectionChoice.ORIGINAL : reviewerChoice;
        if (choice == V3SelectionChoice.CLARITY_FIRST && !work.clarityAdmitted) {
            choice = bestLocalChoice(work);
            reason = "reviewer-choice-not-admitted";
        } else if (choice == V3SelectionChoice.JD_TARGETED && !work.targetedAdmitted) {
            choice = bestLocalChoice(work);
            reason = "reviewer-choice-not-admitted";
        } else if (choice == V3SelectionChoice.HYBRID_1 && !admitted(work.hybrids.hybrid1())) {
            choice = bestLocalChoice(work);
            reason = "reviewer-choice-not-admitted";
        } else if (choice == V3SelectionChoice.HYBRID_2 && !admitted(work.hybrids.hybrid2())) {
            choice = bestLocalChoice(work);
            reason = "reviewer-choice-not-admitted";
        }
        if (choice == V3SelectionChoice.CLARITY_FIRST && work.clarityAdmitted) {
            return new Selected(
                    choice,
                    work.result.clarityFirst(),
                    work.result.clarityFirst().bulletsInNodeOrder(work.writable),
                    "");
        }
        if (choice == V3SelectionChoice.JD_TARGETED && work.targetedAdmitted) {
            return new Selected(
                    choice,
                    work.result.jdTargeted(),
                    work.result.jdTargeted().bulletsInNodeOrder(work.writable),
                    "");
        }
        if (choice == V3SelectionChoice.HYBRID_1 && admitted(work.hybrids.hybrid1())) {
            return new Selected(
                    choice,
                    work.hybrids.hybrid1().variant(),
                    work.hybrids.hybrid1().bullets(),
                    "");
        }
        if (choice == V3SelectionChoice.HYBRID_2 && admitted(work.hybrids.hybrid2())) {
            return new Selected(
                    choice,
                    work.hybrids.hybrid2().variant(),
                    work.hybrids.hybrid2().bullets(),
                    "");
        }
        return new Selected(
                V3SelectionChoice.ORIGINAL,
                null,
                work.originalBullets,
                reason == null || reason.isBlank() ? "original-wins" : reason);
    }

    private static V3BasicRunResult.V3EntryTrace trace(
            EntryWork work,
            V3RewriteDecision decision,
            Selected selected,
            List<String> finals) {
        V3FactCoverage.Result coverage = V3FactCoverage.compute(work.graph, work.originalBullets, finals);
        List<String> nodeSources = List.of();
        if (selected.choice == V3SelectionChoice.HYBRID_1 && work.hybrids.hybrid1() != null) {
            nodeSources = labels(work.hybrids.hybrid1().nodeSources());
        } else if (selected.choice == V3SelectionChoice.HYBRID_2 && work.hybrids.hybrid2() != null) {
            nodeSources = labels(work.hybrids.hybrid2().nodeSources());
        } else if (selected.choice == V3SelectionChoice.CLARITY_FIRST) {
            nodeSources = repeat("CLARITY_FIRST", finals.size());
        } else if (selected.choice == V3SelectionChoice.JD_TARGETED) {
            nodeSources = repeat("JD_TARGETED", finals.size());
        } else {
            nodeSources = repeat("ORIGINAL", finals.size());
        }
        return new V3BasicRunResult.V3EntryTrace(
                work.key,
                decision,
                selected.choice,
                selected.noChangeReason,
                work.validatorCode,
                selected.choice != V3SelectionChoice.ORIGINAL && selected.choice != V3SelectionChoice.REJECT_ALL_AI,
                decision == V3RewriteDecision.REWRITE,
                coverage.retained(),
                coverage.moved(),
                coverage.omitted(),
                work.originalBullets,
                work.result.clarityFirst().bulletsInNodeOrder(work.writable),
                work.result.jdTargeted().bulletsInNodeOrder(work.writable),
                finals,
                work.originalScore,
                work.clarityScore,
                work.targetedScore,
                work.result.telemetry().promptVersion(),
                work.result.telemetry().model(),
                work.result.telemetry().latencyMs(),
                work.result.telemetry().promptTokens(),
                work.result.telemetry().completionTokens(),
                coverage.used(),
                coverage.introduced(),
                coverage.duplicate(),
                coverage.supportingSourceNodeIds(),
                work.repairReason,
                work.hybrids.hybrid1() == null ? List.of() : work.hybrids.hybrid1().bullets(),
                work.hybrids.hybrid2() == null ? List.of() : work.hybrids.hybrid2().bullets(),
                work.hybrids.hybrid1() == null ? null : work.hybrids.hybrid1().score(),
                work.hybrids.hybrid2() == null ? null : work.hybrids.hybrid2().score(),
                nodeSources,
                coverage.omittedOptional(),
                work.graph.keysOfMateriality(V3FactMateriality.PROTECTED),
                work.graph.keysOfMateriality(V3FactMateriality.OPTIONAL_CONTEXT),
                work.graph.keysOfMateriality(V3FactMateriality.EXTRACTION_NOISE));
    }

    private static String firstNonBlank(String left, String right) {
        if (left != null && !left.isBlank()) {
            return left;
        }
        return right == null ? "" : right;
    }

    private static List<String> labels(List<V3NodeSource> sources) {
        List<String> labels = new ArrayList<>();
        if (sources == null) {
            return labels;
        }
        for (V3NodeSource source : sources) {
            labels.add(source == null ? "" : source.name());
        }
        return labels;
    }

    private static List<String> repeat(String value, int count) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            values.add(value);
        }
        return values;
    }

    private record EntryJob(
            String key,
            ClaimLedger.Entry entry,
            V3EntryFactGraph graph,
            List<V3WritableNode> writable
    ) {
    }

    private static final class EntryWork {
        private final String key;
        private final V3EntryFactGraph graph;
        private final List<V3WritableNode> writable;
        private final List<String> originalBullets;
        private final List<V3RankedRequirement> ranked;
        private final V3EntryRewriteResult result;
        private final boolean clarityValid;
        private final boolean targetedValid;
        private final boolean clarityAdmitted;
        private final boolean targetedAdmitted;
        private final V3QualityScore clarityScore;
        private final V3QualityScore targetedScore;
        private final V3QualityScore originalScore;
        private final String validatorCode;
        private final String validatorDetail;
        private final V3FactCoverage.Result clarityCoverage;
        private final V3FactCoverage.Result targetedCoverage;
        private final V3HybridSelector.Result hybrids;
        private String repairReason = "";

        private EntryWork(
                String key,
                V3EntryFactGraph graph,
                List<V3WritableNode> writable,
                List<String> originalBullets,
                List<V3RankedRequirement> ranked,
                V3EntryRewriteResult result,
                boolean clarityValid,
                boolean targetedValid,
                boolean clarityAdmitted,
                boolean targetedAdmitted,
                V3QualityScore clarityScore,
                V3QualityScore targetedScore,
                V3QualityScore originalScore,
                String validatorCode,
                String validatorDetail,
                V3FactCoverage.Result clarityCoverage,
                V3FactCoverage.Result targetedCoverage,
                V3HybridSelector.Result hybrids) {
            this.key = key;
            this.graph = graph;
            this.writable = writable;
            this.originalBullets = originalBullets;
            this.ranked = ranked;
            this.result = result;
            this.clarityValid = clarityValid;
            this.targetedValid = targetedValid;
            this.clarityAdmitted = clarityAdmitted;
            this.targetedAdmitted = targetedAdmitted;
            this.clarityScore = clarityScore;
            this.targetedScore = targetedScore;
            this.originalScore = originalScore;
            this.validatorCode = validatorCode == null ? "" : validatorCode;
            this.validatorDetail = validatorDetail == null ? "" : validatorDetail;
            this.clarityCoverage = clarityCoverage;
            this.targetedCoverage = targetedCoverage;
            this.hybrids = hybrids == null ? V3HybridSelector.Result.empty() : hybrids;
        }

        static EntryWork originalOnly(EntryJob job) {
            List<String> original = job.graph.originalBulletTexts();
            return new EntryWork(
                    job.key,
                    job.graph,
                    job.writable,
                    original,
                    List.of(),
                    V3EntryRewriteResult.noChange(job.key),
                    false,
                    false,
                    false,
                    false,
                    null,
                    null,
                    V3EntryQualityScorer.scoreOriginal(job.graph, original, List.of()),
                    "ENTRY_FAILED",
                    "kept original",
                    null,
                    null,
                    V3HybridSelector.Result.empty());
        }
    }

    private record VariantEval(
            boolean valid,
            boolean admitted,
            V3QualityScore score,
            V3FactCoverage.Result coverage,
            String validatorCode,
            String detail
    ) {
        static VariantEval invalid(String code, String detail) {
            return new VariantEval(false, false, null, null, code, detail);
        }
    }

    private record Selected(
            V3SelectionChoice choice,
            V3EntryRewriteVariant variant,
            List<String> bullets,
            String noChangeReason
    ) {
    }
}
