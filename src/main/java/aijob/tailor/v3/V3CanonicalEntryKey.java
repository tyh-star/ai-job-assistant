package aijob.tailor.v3;

import aijob.util.CanonicalEntryKeyResolver;
import aijob.util.ClaimLedger;

/**
 * V3 lookup alias. All key material comes from {@link CanonicalEntryKeyResolver}.
 */
public final class V3CanonicalEntryKey {

    private V3CanonicalEntryKey() {
    }

    public static String of(ClaimLedger.Entry entry) {
        return CanonicalEntryKeyResolver.keyForEntry(entry);
    }

    public static String of(ClaimLedger ledger, String nodeId) {
        return CanonicalEntryKeyResolver.index(ledger).keyForNodeId(nodeId);
    }

    public static CanonicalEntryKeyResolver.Index index(ClaimLedger ledger) {
        return CanonicalEntryKeyResolver.index(ledger);
    }
}
