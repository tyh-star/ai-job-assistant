package aijob.enums;

/**
 * Public ATS boards the collector is allowed to read.
 * Phase 1 ships GREENHOUSE and LEVER; adding ASHBY, SMARTRECRUITERS, WORKDAY or a
 * public JobPosting reader only needs a new constant plus an adapter bean.
 */
public enum AtsProviderType {
    GREENHOUSE,
    LEVER
}
