package aijob.util;

import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;

/**
 * Supporting bullets are context only. Copying their material facts into another
 * target would duplicate them because Writer does not delete the source paragraph.
 */
public final class SourceFactDuplicationGate {

    private SourceFactDuplicationGate() {
    }

    public static String rejectReason(
            String targetNodeId,
            String targetOriginal,
            String newText,
            ClaimLedger ledger) {

        if (newText == null || newText.isBlank() || ledger == null || targetNodeId == null) {
            return null;
        }
        ClaimLedger.Entry entry = ledger.entryOf(targetNodeId);
        if (entry == null || entry.nodes() == null) {
            return null;
        }
        int index = 0;
        for (ResumeDocumentNode sibling : entry.nodes()) {
            if (sibling == null) {
                index++;
                continue;
            }
            String siblingId = sibling.getNodeId();
            if (siblingId == null || siblingId.equals(targetNodeId) || ConservativeEvidencePacket.isEntryHeading(sibling, index)) {
                index++;
                continue;
            }
            String siblingText = sibling.getText();
            if (siblingText == null || siblingText.isBlank()) {
                index++;
                continue;
            }
            if (ConservativeMaterialFacts.contributesConcreteFact(targetOriginal, siblingText, newText)) {
                return RejectedTailorChange.SOURCE_FACT_WOULD_DUPLICATE;
            }
            index++;
        }
        return null;
    }
}
