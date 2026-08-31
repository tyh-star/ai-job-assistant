package aijob.tailor.v3;

/**
 * How a Fact Graph node affects missing-fact accounting.
 * OPTIONAL_CONTEXT may be omitted without failing evaluation.
 * EXTRACTION_NOISE is never counted as a missing fact.
 */
public enum V3FactMateriality {
    PROTECTED,
    OPTIONAL_CONTEXT,
    EXTRACTION_NOISE
}
