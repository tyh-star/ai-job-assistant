package aijob.tailor.v3;

import aijob.dto.RejectedTailorChange;
import aijob.dto.TailorChange;

import java.util.List;

public record V3BasicRunResult(
        List<TailorChange> changes,
        List<RejectedTailorChange> rejected,
        int rewrittenEntryCount,
        int unchangedEntryCount,
        int generateCalls,
        int repairCalls,
        int languageReviewCalls,
        List<V3EntryTrace> traces
) {
    public V3BasicRunResult {
        changes = changes == null ? List.of() : List.copyOf(changes);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
        traces = traces == null ? List.of() : List.copyOf(traces);
    }

    public record V3EntryTrace(
            String canonicalEntryKey,
            V3RewriteDecision decision,
            V3SelectionChoice chosenVariant,
            String noChangeReason,
            String validatorCode,
            boolean qualityImproved,
            boolean languageAccepted,
            List<String> retainedFactKeys,
            List<String> movedFactKeys,
            List<String> omittedFactKeys,
            List<String> originalBullets,
            List<String> clarityBullets,
            List<String> targetedBullets,
            List<String> finalBullets,
            V3QualityScore originalScore,
            V3QualityScore clarityScore,
            V3QualityScore targetedScore,
            String promptVersion,
            String model,
            long latencyMs,
            Integer promptTokens,
            Integer completionTokens,
            List<String> usedFactKeys,
            List<String> introducedFactKeys,
            List<String> duplicateFactKeys,
            List<String> supportingSourceNodeIds,
            String repairReason,
            List<String> hybrid1Bullets,
            List<String> hybrid2Bullets,
            V3QualityScore hybrid1Score,
            V3QualityScore hybrid2Score,
            List<String> nodeSources,
            List<String> omittedOptionalFactKeys,
            List<String> protectedFactKeys,
            List<String> optionalFactKeys,
            List<String> noiseFactKeys
    ) {
        public V3EntryTrace {
            canonicalEntryKey = canonicalEntryKey == null ? "" : canonicalEntryKey;
            chosenVariant = chosenVariant == null ? V3SelectionChoice.ORIGINAL : chosenVariant;
            noChangeReason = noChangeReason == null ? "" : noChangeReason;
            validatorCode = validatorCode == null ? "" : validatorCode;
            retainedFactKeys = retainedFactKeys == null ? List.of() : List.copyOf(retainedFactKeys);
            movedFactKeys = movedFactKeys == null ? List.of() : List.copyOf(movedFactKeys);
            omittedFactKeys = omittedFactKeys == null ? List.of() : List.copyOf(omittedFactKeys);
            originalBullets = originalBullets == null ? List.of() : List.copyOf(originalBullets);
            clarityBullets = clarityBullets == null ? List.of() : List.copyOf(clarityBullets);
            targetedBullets = targetedBullets == null ? List.of() : List.copyOf(targetedBullets);
            finalBullets = finalBullets == null ? List.of() : List.copyOf(finalBullets);
            promptVersion = promptVersion == null ? "" : promptVersion;
            model = model == null ? "" : model;
            usedFactKeys = usedFactKeys == null ? List.of() : List.copyOf(usedFactKeys);
            introducedFactKeys = introducedFactKeys == null ? List.of() : List.copyOf(introducedFactKeys);
            duplicateFactKeys = duplicateFactKeys == null ? List.of() : List.copyOf(duplicateFactKeys);
            supportingSourceNodeIds = supportingSourceNodeIds == null ? List.of() : List.copyOf(supportingSourceNodeIds);
            repairReason = repairReason == null ? "" : repairReason;
            hybrid1Bullets = hybrid1Bullets == null ? List.of() : List.copyOf(hybrid1Bullets);
            hybrid2Bullets = hybrid2Bullets == null ? List.of() : List.copyOf(hybrid2Bullets);
            nodeSources = nodeSources == null ? List.of() : List.copyOf(nodeSources);
            omittedOptionalFactKeys = omittedOptionalFactKeys == null ? List.of() : List.copyOf(omittedOptionalFactKeys);
            protectedFactKeys = protectedFactKeys == null ? List.of() : List.copyOf(protectedFactKeys);
            optionalFactKeys = optionalFactKeys == null ? List.of() : List.copyOf(optionalFactKeys);
            noiseFactKeys = noiseFactKeys == null ? List.of() : List.copyOf(noiseFactKeys);
        }
    }
}
