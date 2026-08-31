package aijob.tailor.v3;

import aijob.dto.ResumeDocumentNode;
import aijob.util.ClaimLedger;
import aijob.util.ResumeEntryCatalog;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * V3-3A Enhanced Opportunity Map. Read-only: no Writer, no OpenAI, no job/resume writes.
 */
@Service
public class V3EnhancedOpportunityService {

    public V3OpportunityMap scan(List<ResumeDocumentNode> nodes, String jobDescription) {
        return scan(nodes, jobDescription, List.of(), List.of());
    }

    public V3OpportunityMap scan(
            List<ResumeDocumentNode> nodes,
            String jobDescription,
            List<V3UserEvidence> evidence) {
        return scan(nodes, jobDescription, evidence, List.of());
    }

    public V3OpportunityMap scan(
            List<ResumeDocumentNode> nodes,
            String jobDescription,
            List<V3UserEvidence> evidence,
            List<String> skippedQuestionIds) {

        List<V3EnhancedQuestionPlanner.Candidate> candidates = new ArrayList<>();
        List<V3OpportunityEntry> entries = new ArrayList<>();
        collectPlan(nodes, jobDescription, evidence, candidates, entries);
        Set<String> skipped = new LinkedHashSet<>();
        if (skippedQuestionIds != null) {
            skipped.addAll(skippedQuestionIds);
        }
        List<V3EnhancedQuestion> questions = V3EnhancedQuestionPlanner.select(candidates, skipped);
        return new V3OpportunityMap(entries, questions, List.copyOf(skipped), 0, 0, false);
    }

    /**
     * Binds an answer to the replanned closed-catalog question. Looks up every
     * planned question, not only the displayed top 5, so metric/topic keys stay
     * the planner values. Unknown or stale ids return null.
     */
    public V3EnhancedQuestion plannedQuestion(
            List<ResumeDocumentNode> nodes,
            String jobDescription,
            List<V3UserEvidence> evidence,
            String questionId) {
        if (questionId == null || questionId.isBlank()) {
            return null;
        }
        List<V3EnhancedQuestionPlanner.Candidate> candidates = new ArrayList<>();
        collectPlan(nodes, jobDescription, evidence, candidates, new ArrayList<>());
        for (V3EnhancedQuestionPlanner.Candidate candidate : candidates) {
            if (questionId.equals(candidate.question().id())) {
                return candidate.question();
            }
        }
        return null;
    }

    public boolean entryBelongsToResume(List<ResumeDocumentNode> nodes, String entryKey) {
        if (entryKey == null || entryKey.isBlank() || nodes == null) {
            return false;
        }
        ClaimLedger ledger = ClaimLedger.from(nodes);
        V3ResumeFactGraphs graphs = V3EntryFactGraphFactory.from(ledger);
        for (V3EntryFactGraph graph : graphs.entries()) {
            if (entryKey.equals(graph.canonicalEntryKey())
                    && (ResumeEntryCatalog.PROJECT.equals(graph.sectionRole())
                    || ResumeEntryCatalog.EXPERIENCE.equals(graph.sectionRole()))) {
                return true;
            }
        }
        return false;
    }

    private static void collectPlan(
            List<ResumeDocumentNode> nodes,
            String jobDescription,
            List<V3UserEvidence> evidence,
            List<V3EnhancedQuestionPlanner.Candidate> candidates,
            List<V3OpportunityEntry> entries) {
        ClaimLedger ledger = ClaimLedger.from(nodes);
        V3ResumeFactGraphs graphs = V3EntryFactGraphFactory.from(ledger, evidence);
        String skills = V3EnhancedAtomicCoverage.skillsText(nodes);
        List<String> requirementTexts = V3EnhancedAtomicCoverage.requirementTexts(jobDescription);
        List<V3EntryFactGraph> projectGraphs = new ArrayList<>();
        for (V3EntryFactGraph graph : graphs.entries()) {
            if (ResumeEntryCatalog.PROJECT.equals(graph.sectionRole())
                    || ResumeEntryCatalog.EXPERIENCE.equals(graph.sectionRole())) {
                projectGraphs.add(graph);
            }
        }
        String hostKey = V3EnhancedQuestionPlanner.preferredHost(projectGraphs);
        List<String> allowedKeys = V3EnhancedQuestionPlanner.projectEntryKeys(projectGraphs);
        for (V3EntryFactGraph graph : projectGraphs) {
            List<V3EnhancedQuestionPlanner.Candidate> entryCandidates =
                    V3EnhancedQuestionPlanner.candidates(graph, jobDescription, evidence);
            candidates.addAll(entryCandidates);
            boolean host = graph.canonicalEntryKey().equals(hostKey);
            entries.add(entryRow(graph, jobDescription, skills, requirementTexts, entryCandidates, host));
        }
        candidates.addAll(V3EnhancedQuestionPlanner.skillGapCandidates(
                jobDescription, skills, projectGraphs, evidence, hostKey, allowedKeys));
    }

    private static V3OpportunityEntry entryRow(
            V3EntryFactGraph graph,
            String jobDescription,
            String skills,
            List<String> requirementTexts,
            List<V3EnhancedQuestionPlanner.Candidate> entryCandidates,
            boolean host) {
        List<String> covered = new ArrayList<>();
        List<String> partial = new ArrayList<>();
        List<String> strengthen = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> cannotInvent = new ArrayList<>(
                V3EnhancedQuestionPlanner.forbiddenInventions(
                        jobDescription, V3EnhancedAtomicCoverage.evidenceText(graph)));
        LinkedHashSet<String> askable = new LinkedHashSet<>();
        List<V3JdRequirementBreakdown> breakdowns = new ArrayList<>();
        boolean localCovered = false;
        boolean localPartial = false;
        for (String requirement : requirementTexts) {
            V3JdRequirementBreakdown breakdown = V3EnhancedAtomicCoverage.breakdown(requirement, graph, skills);
            if (breakdown.coveredAtoms().isEmpty() && breakdown.missingAtoms().isEmpty()) {
                continue;
            }
            boolean local = V3EnhancedAtomicCoverage.localEvidence(requirement, graph);
            boolean skillGap = !V3EnhancedAtomicCoverage.skillGapTopic(requirement).isBlank();
            boolean show = local || (host && skillGap);
            if (V3EnhancedAtomicCoverage.dockerOrCommunication(requirement)) {
                show = local;
            }
            if (!show) {
                continue;
            }
            breakdowns.add(breakdown);
            if (V3JdRequirementBreakdown.COVERED.equals(breakdown.status())) {
                covered.add(breakdown.requirementText());
                if (local) {
                    localCovered = true;
                }
            } else if (V3JdRequirementBreakdown.PARTIALLY_COVERED.equals(breakdown.status())) {
                partial.add(breakdown.requirementText());
                if (local) {
                    localPartial = true;
                }
                for (String label : breakdown.missingAtoms()) {
                    if (!looksInternal(label)) {
                        askable.add(label);
                    }
                }
            } else {
                if (!V3EnhancedAtomicCoverage.dockerOrCommunication(requirement)) {
                    missing.add(breakdown.requirementText());
                    for (String label : breakdown.missingAtoms()) {
                        if (!looksInternal(label)) {
                            askable.add(label);
                        }
                    }
                }
            }
        }
        for (V3EnhancedQuestionPlanner.Candidate candidate : entryCandidates) {
            askable.add(askableLabel(candidate.question().topic()));
        }
        V3OpportunityPriority priority = priority(entryCandidates, host, localCovered, localPartial, skillGapOnHost(host, breakdowns));
        return new V3OpportunityEntry(
                graph.canonicalEntryKey(),
                V3EnhancedQuestionPlanner.displayTitle(graph.originalHeading()),
                graph.sectionRole(),
                priority,
                covered,
                partial,
                strengthen,
                List.copyOf(askable),
                distinct(cannotInvent),
                missing,
                breakdowns);
    }

    private static boolean skillGapOnHost(boolean host, List<V3JdRequirementBreakdown> breakdowns) {
        if (!host) {
            return false;
        }
        for (V3JdRequirementBreakdown breakdown : breakdowns) {
            if (!V3JdRequirementBreakdown.COVERED.equals(breakdown.status())
                    && !V3EnhancedAtomicCoverage.skillGapTopic(breakdown.requirementText()).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static V3OpportunityPriority priority(
            List<V3EnhancedQuestionPlanner.Candidate> candidates,
            boolean host,
            boolean localCovered,
            boolean localPartial,
            boolean confirmableGap) {
        boolean high = false;
        boolean medium = localPartial || (host && confirmableGap);
        for (V3EnhancedQuestionPlanner.Candidate candidate : candidates) {
            if (candidate.rank() == 1) {
                high = true;
            } else {
                medium = true;
            }
        }
        if (host && confirmableGap) {
            high = true;
        }
        if (localCovered && !localPartial && !high && !medium) {
            medium = true;
        }
        if (high) {
            return V3OpportunityPriority.HIGH;
        }
        if (medium) {
            return V3OpportunityPriority.MEDIUM;
        }
        return V3OpportunityPriority.NO_ACTION;
    }

    private static boolean looksInternal(String label) {
        return label != null && (label.startsWith("action:") || label.startsWith("tech:") || label.startsWith("process:"));
    }

    private static String askableLabel(String topic) {
        return switch (topic) {
            case "maintain" -> "Later maintenance work after deployment";
            case "testing" -> "Automated tests and tools actually used";
            case "build-tool" -> "Build tool used on this project";
            case "data-store" -> "Data store or query work on this project";
            case "metric" -> "A confirmable performance or volume figure";
            default -> topic;
        };
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }
}
