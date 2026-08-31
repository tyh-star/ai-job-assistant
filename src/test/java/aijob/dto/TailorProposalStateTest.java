package aijob.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailorProposalStateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parse_legacyArraySeedsExploredIdsAndAllowsContinue() throws Exception {
        TailorChange change = new TailorChange();
        change.setNodeId("p-16");
        change.setOriginalText("Built Java APIs.");
        change.setNewText("Built Java REST APIs.");
        String json = mapper.writeValueAsString(List.of(change));

        TailorProposalState state = TailorProposalState.parse(mapper, json);

        assertEquals(1, state.getChanges().size());
        assertEquals("p-16", state.getChanges().get(0).getNodeId());
        assertEquals(List.of("p-16"), state.getExploredNodeIds());
        assertEquals(Boolean.TRUE, state.getHasMoreSuggestions());
    }

    @Test
    void parse_envelopeKeepsExploredAndHasMore() throws Exception {
        TailorProposalState stored = TailorProposalState.fromChanges(List.of(), List.of("p-16", "p-21"));
        stored.setHasMoreSuggestions(Boolean.FALSE);
        String json = mapper.writeValueAsString(stored);

        TailorProposalState state = TailorProposalState.parse(mapper, json);

        assertEquals(List.of("p-16", "p-21"), state.getExploredNodeIds());
        assertEquals(Boolean.FALSE, state.getHasMoreSuggestions());
    }

    @Test
    void parse_enhancedEnvelopeKeepsEntryReviewsAndEvidenceIds() throws Exception {
        TailorChange change = new TailorChange();
        change.setNodeId("p-4");
        change.setOriginalText("Deployed to AWS");
        change.setNewText("Deployed and maintained on AWS with weekly patching");
        TailorProposalState stored = TailorProposalState.fromChanges(List.of(change), List.of("p-4"));
        stored.setHasMoreSuggestions(Boolean.FALSE);
        stored.setEngineVersion("V3");
        stored.setProductMode("ENHANCED");
        stored.setEvidenceItemIds(List.of(41L));
        V3EntryReview review = new V3EntryReview();
        review.setCanonicalEntryKey("PROJECT:prime-deals");
        review.setEntryTitle("Prime Deals");
        review.setOriginalNodes(List.of(new V3ReviewNode("p-4", "Deployed to AWS")));
        review.setProposedNodes(List.of(new V3ReviewNode(
                "p-4", "Deployed and maintained on AWS with weekly patching")));
        review.setWhyBetter("Uses your confirmed Prime Deals detail: weekly patching.");
        review.setSuggestionType("EVIDENCE_ENHANCED");
        review.setUsedEvidenceItemIds(List.of(41L));
        stored.setEntryReviews(List.of(review));

        TailorProposalState state = TailorProposalState.parse(mapper, mapper.writeValueAsString(stored));

        assertEquals("ENHANCED", state.getProductMode());
        assertEquals("V3", state.getEngineVersion());
        assertEquals(List.of(41L), state.getEvidenceItemIds());
        assertEquals(1, state.getEntryReviews().size());
        assertEquals("p-4", state.getEntryReviews().get(0).getProposedNodes().get(0).getNodeId());
        assertEquals("p-4", state.getChanges().get(0).getNodeId());
        assertTrue(state.getHasMoreSuggestions() == Boolean.FALSE);
    }
}
