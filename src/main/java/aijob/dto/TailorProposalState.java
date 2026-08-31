package aijob.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Backward-compatible PENDING proposal payload.
 * Legacy rows store a raw TailorChange array; new rows store this envelope.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TailorProposalState {

    private List<TailorChange> changes = new ArrayList<>();
    private List<String> exploredNodeIds = new ArrayList<>();
    private Boolean hasMoreSuggestions;
    private List<String> eligibleNodeIds = new ArrayList<>();
    private List<String> remainingNodeIds = new ArrayList<>();
    private List<String> noChangeNodeIds = new ArrayList<>();
    private String coverageStatus;
    private String engineVersion;
    private String productMode;
    private List<V3EntryReview> entryReviews = new ArrayList<>();
    private List<Long> evidenceItemIds = new ArrayList<>();
    private List<V3EvidenceUsage> evidenceUsage = new ArrayList<>();
    private List<V3SkillReview> skillReviews = new ArrayList<>();

    public static final String COVERAGE_RUNNING = "RUNNING";
    public static final String COVERAGE_COMPLETE = "COMPLETE";
    public static final String COVERAGE_PARTIAL = "PARTIAL";
    public static final String COVERAGE_FAILED = "FAILED";

    public static TailorProposalState fromChanges(List<TailorChange> changes, List<String> exploredNodeIds) {
        TailorProposalState state = new TailorProposalState();
        state.changes = changes == null ? new ArrayList<>() : new ArrayList<>(changes);
        LinkedHashSet<String> explored = new LinkedHashSet<>();
        if (exploredNodeIds != null) {
            for (String nodeId : exploredNodeIds) {
                if (nodeId != null && !nodeId.isBlank()) {
                    explored.add(nodeId);
                }
            }
        }
        for (TailorChange change : state.changes) {
            if (change != null && change.getNodeId() != null && !change.getNodeId().isBlank()) {
                explored.add(change.getNodeId());
            }
        }
        state.exploredNodeIds = new ArrayList<>(explored);
        return state;
    }

    public static TailorProposalState parse(ObjectMapper mapper, String json) {
        if (mapper == null) {
            throw new IllegalStateException("Failed to deserialize tailor proposal");
        }
        if (json == null || json.isBlank()) {
            TailorProposalState empty = new TailorProposalState();
            empty.setHasMoreSuggestions(Boolean.TRUE);
            return empty;
        }
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || root.isNull()) {
                TailorProposalState empty = new TailorProposalState();
                empty.setHasMoreSuggestions(Boolean.TRUE);
                return empty;
            }
            if (root.isArray()) {
                List<TailorChange> changes = mapper.convertValue(root, new TypeReference<>() {
                });
                TailorProposalState state = fromChanges(changes, null);
                state.setHasMoreSuggestions(Boolean.TRUE);
                return state;
            }
            if (root.isObject()) {
                TailorProposalState state = mapper.convertValue(root, TailorProposalState.class);
                if (state == null) {
                    state = new TailorProposalState();
                }
                if (state.getChanges() == null) {
                    state.setChanges(new ArrayList<>());
                }
                TailorProposalState seeded = fromChanges(state.getChanges(), state.getExploredNodeIds());
                seeded.setHasMoreSuggestions(
                        state.getHasMoreSuggestions() == null ? Boolean.TRUE : state.getHasMoreSuggestions());
                seeded.setEligibleNodeIds(copyIds(state.getEligibleNodeIds()));
                seeded.setRemainingNodeIds(copyIds(state.getRemainingNodeIds()));
                seeded.setNoChangeNodeIds(copyIds(state.getNoChangeNodeIds()));
                seeded.setCoverageStatus(state.getCoverageStatus());
                seeded.setEngineVersion(state.getEngineVersion());
                seeded.setProductMode(state.getProductMode());
                seeded.setEntryReviews(state.getEntryReviews() == null
                        ? new ArrayList<>() : new ArrayList<>(state.getEntryReviews()));
                seeded.setEvidenceItemIds(state.getEvidenceItemIds() == null
                        ? new ArrayList<>() : new ArrayList<>(state.getEvidenceItemIds()));
                seeded.setEvidenceUsage(state.getEvidenceUsage() == null
                        ? new ArrayList<>() : new ArrayList<>(state.getEvidenceUsage()));
                seeded.setSkillReviews(state.getSkillReviews() == null
                        ? new ArrayList<>() : new ArrayList<>(state.getSkillReviews()));
                return seeded;
            }
            throw new IllegalStateException("Failed to deserialize tailor proposal");
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new IllegalStateException("Failed to deserialize tailor proposal", ex);
        }
    }

    private static List<String> copyIds(List<String> ids) {
        if (ids == null) {
            return new ArrayList<>();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                unique.add(id);
            }
        }
        return new ArrayList<>(unique);
    }
}
