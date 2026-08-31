package aijob.util;

import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeStructure;
import aijob.entity.Resume;

import java.util.List;
import java.util.Map;

/**
 * Unified Tailor Analysis view of a selected resume. Matching nodes may come
 * from a canonical DOCX child while structuredResume always comes from the
 * UPLOAD parent when one exists.
 */
public record ResumeAnalysisInput(
        Resume selectedResume,
        Resume uploadRootResume,
        Resume canonicalChildResume,
        List<ResumeDocumentNode> matchingNodes,
        Map<String, AnalysisEvidenceSource> evidenceSourcesById,
        ResumeStructure structuredResume,
        Long evidenceRootId
) {

    public ResumeAnalysisInput {
        matchingNodes = matchingNodes == null ? List.of() : List.copyOf(matchingNodes);
        evidenceSourcesById = evidenceSourcesById == null ? Map.of() : Map.copyOf(evidenceSourcesById);
    }
}
