package aijob.tailor.v3;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side fact coverage. Model-declared fact keys are ignored.
 * {@code omittedFactKeys} contains only PROTECTED facts.
 */
public final class V3FactCoverage {

    private V3FactCoverage() {
    }

    public static Result compute(V3EntryFactGraph graph, List<String> originalBullets, List<String> rewrittenBullets) {
        return V3FactCoverageResolver.resolve(originalBullets, rewrittenBullets, graph);
    }

    public record Result(
            List<String> usedFactKeys,
            List<String> retainedFactKeys,
            List<String> movedFactKeys,
            List<String> omittedFactKeys,
            List<String> introducedFactKeys,
            List<String> duplicateFactKeys,
            List<String> supportingSourceNodeIds,
            List<String> omittedOptionalFactKeys,
            List<String> extractionNoiseFactKeys
    ) {
        public Result {
            usedFactKeys = copy(usedFactKeys);
            retainedFactKeys = copy(retainedFactKeys);
            movedFactKeys = copy(movedFactKeys);
            omittedFactKeys = copy(omittedFactKeys);
            introducedFactKeys = copy(introducedFactKeys);
            duplicateFactKeys = copy(duplicateFactKeys);
            supportingSourceNodeIds = copy(supportingSourceNodeIds);
            omittedOptionalFactKeys = copy(omittedOptionalFactKeys);
            extractionNoiseFactKeys = copy(extractionNoiseFactKeys);
        }

        public Result(List<String> retained, List<String> moved, List<String> omitted) {
            this(union(retained, moved), retained, moved, omitted, List.of(), List.of(), List.of(), List.of(), List.of());
        }

        public static Result empty() {
            return new Result(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        public List<String> retained() {
            return retainedFactKeys;
        }

        public List<String> moved() {
            return movedFactKeys;
        }

        public List<String> omitted() {
            return omittedFactKeys;
        }

        public List<String> omittedProtected() {
            return omittedFactKeys;
        }

        public List<String> omittedOptional() {
            return omittedOptionalFactKeys;
        }

        public List<String> extractionNoise() {
            return extractionNoiseFactKeys;
        }

        public List<String> used() {
            return usedFactKeys;
        }

        public List<String> introduced() {
            return introducedFactKeys;
        }

        public List<String> duplicate() {
            return duplicateFactKeys;
        }

        public boolean protectedComplete() {
            return omittedFactKeys.isEmpty();
        }

        private static List<String> copy(List<String> values) {
            return values == null ? List.of() : List.copyOf(values);
        }

        private static List<String> union(List<String> retained, List<String> moved) {
            List<String> used = new ArrayList<>();
            if (retained != null) {
                used.addAll(retained);
            }
            if (moved != null) {
                used.addAll(moved);
            }
            return used;
        }
    }
}
