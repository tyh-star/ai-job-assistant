package aijob.tailor.v3;

import java.util.List;

public record V3RankedRequirement(
        String id,
        String text,
        V3RequirementCoverage coverage,
        List<String> terms
) {
    public V3RankedRequirement {
        id = id == null ? "" : id;
        text = text == null ? "" : text;
        coverage = coverage == null ? V3RequirementCoverage.UNSUPPORTED : coverage;
        terms = terms == null ? List.of() : List.copyOf(terms);
    }
}
