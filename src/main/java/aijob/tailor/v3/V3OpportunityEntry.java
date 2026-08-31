package aijob.tailor.v3;

import java.util.List;

/**
 * User-facing Opportunity Map row for one PROJECT/EXPERIENCE entry.
 * Internal fact keys and node ids are omitted.
 */
public record V3OpportunityEntry(
        String canonicalEntryKey,
        String entryTitle,
        String entryType,
        V3OpportunityPriority priority,
        List<String> coveredRequirements,
        List<String> partiallyCoveredRequirements,
        List<String> canStrengthenWithExistingFacts,
        List<String> missingFactsThatCanBeConfirmed,
        List<String> cannotInvent,
        List<String> missingRequirements,
        List<V3JdRequirementBreakdown> requirements
) {
    public V3OpportunityEntry(
            String canonicalEntryKey,
            String entryTitle,
            String entryType,
            V3OpportunityPriority priority,
            List<String> coveredRequirements,
            List<String> partiallyCoveredRequirements,
            List<String> canStrengthenWithExistingFacts,
            List<String> missingFactsThatCanBeConfirmed,
            List<String> cannotInvent) {
        this(
                canonicalEntryKey,
                entryTitle,
                entryType,
                priority,
                coveredRequirements,
                partiallyCoveredRequirements,
                canStrengthenWithExistingFacts,
                missingFactsThatCanBeConfirmed,
                cannotInvent,
                List.of(),
                List.of());
    }

    public V3OpportunityEntry {
        canonicalEntryKey = canonicalEntryKey == null ? "" : canonicalEntryKey;
        entryTitle = entryTitle == null ? "" : entryTitle;
        entryType = entryType == null ? "" : entryType;
        priority = priority == null ? V3OpportunityPriority.NO_ACTION : priority;
        coveredRequirements = coveredRequirements == null ? List.of() : List.copyOf(coveredRequirements);
        partiallyCoveredRequirements = partiallyCoveredRequirements == null
                ? List.of() : List.copyOf(partiallyCoveredRequirements);
        canStrengthenWithExistingFacts = canStrengthenWithExistingFacts == null
                ? List.of() : List.copyOf(canStrengthenWithExistingFacts);
        missingFactsThatCanBeConfirmed = missingFactsThatCanBeConfirmed == null
                ? List.of() : List.copyOf(missingFactsThatCanBeConfirmed);
        cannotInvent = cannotInvent == null ? List.of() : List.copyOf(cannotInvent);
        missingRequirements = missingRequirements == null ? List.of() : List.copyOf(missingRequirements);
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
    }
}
