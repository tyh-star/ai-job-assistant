package aijob.tailor.v3;

import java.util.List;

public record V3EntryRewriteRequest(
        V3ProductMode productMode,
        String canonicalEntryKey,
        String sectionRole,
        String entryTitle,
        String entryDate,
        String jobDescription,
        List<String> rankedRequirementIds,
        List<V3RankedRequirement> rankedRequirements,
        List<V3WritableNode> writableNodes,
        V3EntryFactGraph factGraph,
        V3ResumeFactGraphs resumeFactGraphs,
        List<V3UserEvidence> userConfirmedFacts,
        List<V3UserEvidence> rejectedFacts,
        int bulletCountLimit,
        int totalCharacterBudget,
        String repairError
) {
    public V3EntryRewriteRequest {
        productMode = productMode == null ? V3ProductMode.BASIC : productMode;
        canonicalEntryKey = canonicalEntryKey == null ? "" : canonicalEntryKey;
        sectionRole = sectionRole == null ? "" : sectionRole;
        entryTitle = entryTitle == null ? "" : entryTitle;
        entryDate = entryDate == null ? "" : entryDate;
        jobDescription = jobDescription == null ? "" : jobDescription;
        rankedRequirementIds = rankedRequirementIds == null ? List.of() : List.copyOf(rankedRequirementIds);
        rankedRequirements = rankedRequirements == null ? List.of() : List.copyOf(rankedRequirements);
        writableNodes = writableNodes == null ? List.of() : List.copyOf(writableNodes);
        userConfirmedFacts = userConfirmedFacts == null ? List.of() : List.copyOf(userConfirmedFacts);
        rejectedFacts = rejectedFacts == null ? List.of() : List.copyOf(rejectedFacts);
        repairError = repairError == null ? "" : repairError;
    }

    public boolean repair() {
        return !repairError.isBlank();
    }
}
