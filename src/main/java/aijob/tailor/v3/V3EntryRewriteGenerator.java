package aijob.tailor.v3;

/**
 * Shared V3 entry-level generator. One model call rewrites the whole entry.
 * Implementations must not reuse the V2 per-sentence candidate generator.
 */
public interface V3EntryRewriteGenerator {

    V3EntryRewriteResult generate(V3EntryRewriteRequest request);
}
