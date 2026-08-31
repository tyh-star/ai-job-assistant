package aijob.util;

import aijob.dto.CoverageStatus;
import aijob.dto.EvidenceKind;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorAnalysisRequirement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Matches responsibility concepts against PROJECT/EXPERIENCE entries from
 * {@link ClaimLedger}. Actions and object/domain must come from the same entry.
 */
public final class ResponsibilityEvidenceMatcher {

    private static final Set<ResumeNodeRole> STRONG_ROLES = Set.of(
            ResumeNodeRole.EXPERIENCE_BULLET,
            ResumeNodeRole.PROJECT_BULLET
    );
    private static final Set<ResumeNodeRole> WEAK_ROLES = Set.of(
            ResumeNodeRole.SUMMARY_BULLET,
            ResumeNodeRole.SKILLS
    );

    private ResponsibilityEvidenceMatcher() {
    }

    public static void fill(TailorAnalysisRequirement item, List<ResumeDocumentNode> nodes) {
        if (item == null) {
            return;
        }
        ResponsibilityConcept concept = ResponsibilityConceptExtractor.extract(item);
        if (!concept.hasCoreActions()) {
            miss(item);
            return;
        }
        Map<String, ResumeNodeRole> roles = roles(nodes);
        List<Group> groups = groups(nodes, roles);
        Group bestStrong = best(groups, concept, true);
        if (bestStrong != null
                && bestStrong.allActions
                && bestStrong.domainSufficient
                && bestStrong.allTechnical
                && bestStrong.allSupporting
                && !bestStrong.domainConflict) {
            apply(item, bestStrong, CoverageStatus.SUPPORTED);
            return;
        }
        if (bestStrong != null && bestStrong.allActions
                && bestStrong.domainConflict
                && !bestStrong.domainSufficient) {
            miss(item);
            return;
        }
        if (bestStrong != null && bestStrong.actionHits > 0) {
            apply(item, bestStrong, CoverageStatus.PARTIALLY_SUPPORTED);
            return;
        }
        Group bestWeak = best(groups, concept, false);
        if (bestWeak != null && (bestWeak.actionHits > 0 || bestWeak.supportingHits > 0)) {
            apply(item, bestWeak, CoverageStatus.PARTIALLY_SUPPORTED);
            return;
        }
        miss(item);
    }

    private static Map<String, ResumeNodeRole> roles(List<ResumeDocumentNode> nodes) {
        Map<String, ResumeNodeRole> roles = new LinkedHashMap<>();
        for (ConservativeTailorPlanner.ClassifiedNode classified : ConservativeTailorPlanner.classify(nodes)) {
            if (classified.node() != null && !isBlank(classified.node().getNodeId())) {
                roles.put(classified.node().getNodeId(), classified.role());
            }
        }
        return roles;
    }

    private static List<Group> groups(List<ResumeDocumentNode> nodes, Map<String, ResumeNodeRole> roles) {
        ClaimLedger ledger = ClaimLedger.from(nodes);
        Set<String> claimed = new LinkedHashSet<>();
        List<Group> groups = new ArrayList<>();
        for (ClaimLedger.Entry entry : ledger.entries()) {
            List<ResumeDocumentNode> members = new ArrayList<>();
            for (ResumeDocumentNode node : entry.nodes()) {
                if (node == null || isBlank(node.getNodeId())) {
                    continue;
                }
                ResumeNodeRole role = roles.get(node.getNodeId());
                if (STRONG_ROLES.contains(role)) {
                    members.add(node);
                    claimed.add(node.getNodeId());
                }
            }
            if (!members.isEmpty()) {
                groups.add(Group.strong(members, roles));
            }
        }
        Map<String, ResumeDocumentNode> byId = new LinkedHashMap<>();
        if (nodes != null) {
            for (ResumeDocumentNode node : nodes) {
                if (node != null && !isBlank(node.getNodeId())) {
                    byId.put(node.getNodeId(), node);
                }
            }
        }
        for (Map.Entry<String, ResumeNodeRole> entry : roles.entrySet()) {
            if (claimed.contains(entry.getKey())) {
                continue;
            }
            ResumeDocumentNode node = byId.get(entry.getKey());
            if (node == null) {
                continue;
            }
            if (STRONG_ROLES.contains(entry.getValue())) {
                groups.add(Group.strong(List.of(node), roles));
            } else if (WEAK_ROLES.contains(entry.getValue())) {
                groups.add(Group.weak(List.of(node), entry.getValue()));
            }
        }
        return groups;
    }

    private static Group best(List<Group> groups, ResponsibilityConcept concept, boolean strongOnly) {
        Group best = null;
        for (Group group : groups) {
            if (strongOnly != group.strong) {
                continue;
            }
            group.score(concept);
            if (group.actionHits == 0
                    && group.domainHits == 0
                    && group.technicalHits == 0
                    && group.supportingHits == 0) {
                continue;
            }
            if (best == null || group.betterThan(best)) {
                best = group;
            }
        }
        return best;
    }

    private static void apply(TailorAnalysisRequirement item, Group group, String status) {
        item.setStatus(status);
        item.setEvidenceKind(group.kind);
        item.setEvidenceNodeIds(List.copyOf(group.evidenceIds));
        item.setEvidenceQuotes(List.copyOf(group.evidenceQuotes));
    }

    private static void miss(TailorAnalysisRequirement item) {
        item.setStatus(CoverageStatus.MISSING_EVIDENCE);
        item.setEvidenceKind(null);
        item.setEvidenceNodeIds(List.of());
        item.setEvidenceQuotes(List.of());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static final class Group {
        private final boolean strong;
        private final List<ResumeDocumentNode> members;
        private final Map<String, ResumeNodeRole> roles;
        private int actionHits;
        private int technicalHits;
        private int supportingHits;
        private int domainHits;
        private boolean allActions;
        private boolean allTechnical;
        private boolean allSupporting;
        private boolean domainSufficient;
        private boolean domainConflict;
        private String kind;
        private final List<String> evidenceIds = new ArrayList<>();
        private final List<String> evidenceQuotes = new ArrayList<>();

        private Group(boolean strong, List<ResumeDocumentNode> members, Map<String, ResumeNodeRole> roles) {
            this.strong = strong;
            this.members = members;
            this.roles = roles == null ? Map.of() : roles;
        }

        private static Group strong(List<ResumeDocumentNode> members, Map<String, ResumeNodeRole> roles) {
            return new Group(true, members, roles);
        }

        private static Group weak(List<ResumeDocumentNode> members, ResumeNodeRole role) {
            Group group = new Group(false, members, Map.of());
            group.kind = ResumeRequirementMatcher.fromRole(role);
            return group;
        }

        private void score(ResponsibilityConcept concept) {
            Set<String> actionsFound = new LinkedHashSet<>();
            Set<String> techFound = new LinkedHashSet<>();
            Set<String> supportFound = new LinkedHashSet<>();
            StringBuilder combined = new StringBuilder();
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            for (ResumeDocumentNode node : members) {
                String text = node.getText();
                combined.append(' ').append(text == null ? "" : text);
                boolean matchedAction = false;
                for (String action : concept.requiredActions()) {
                    if (ActionConceptRegistry.contains(text, action)) {
                        actionsFound.add(action);
                        matchedAction = true;
                    }
                }
                boolean matchedDomain = ObjectDomainRegistry.nodeMatchesAny(
                        text, concept.requiredObjectOrDomain());
                for (String technical : concept.requiredTechnicalConcepts()) {
                    if (TermAliasNormalizer.matches(text, technical)) {
                        techFound.add(EvidenceText.normalize(technical));
                    }
                }
                for (String supporting : concept.supportingContext()) {
                    if (TermAliasNormalizer.matches(text, supporting)) {
                        supportFound.add(supporting);
                    }
                }
                if ((matchedAction || matchedDomain) && seen.add(node.getNodeId()) && evidenceIds.size() < 5) {
                    evidenceIds.add(node.getNodeId());
                    evidenceQuotes.add(text == null ? "" : text.strip());
                    if (strong) {
                        kind = EvidenceKind.stronger(
                                kind,
                                ResumeRequirementMatcher.fromRole(roles.get(node.getNodeId()))
                        );
                    }
                }
            }
            Set<String> domainsFound = ObjectDomainRegistry.domainsIn(combined.toString());
            actionHits = actionsFound.size();
            technicalHits = techFound.size();
            supportingHits = supportFound.size();
            domainHits = 0;
            for (String required : concept.requiredObjectOrDomain()) {
                if (ObjectDomainRegistry.satisfies(required, domainsFound)) {
                    domainHits++;
                }
            }
            allActions = actionHits == concept.requiredActions().size();
            allTechnical = concept.requiredTechnicalConcepts().isEmpty()
                    || technicalHits >= concept.requiredTechnicalConcepts().size();
            allSupporting = concept.supportingContext().isEmpty()
                    || supportingHits >= concept.supportingContext().size();
            domainSufficient = ObjectDomainRegistry.satisfiesAll(
                    concept.requiredObjectOrDomain(), domainsFound);
            domainConflict = ObjectDomainRegistry.conflicts(
                    concept.requiredObjectOrDomain(), domainsFound);
            if (evidenceIds.isEmpty()) {
                for (ResumeDocumentNode node : members) {
                    String text = node.getText();
                    boolean contextOnly = false;
                    for (String technical : concept.requiredTechnicalConcepts()) {
                        if (TermAliasNormalizer.matches(text, technical)) {
                            contextOnly = true;
                        }
                    }
                    for (String supporting : concept.supportingContext()) {
                        if (TermAliasNormalizer.matches(text, supporting)) {
                            contextOnly = true;
                        }
                    }
                    if (contextOnly && seen.add(node.getNodeId()) && evidenceIds.size() < 5) {
                        evidenceIds.add(node.getNodeId());
                        evidenceQuotes.add(text == null ? "" : text.strip());
                        if (kind == null) {
                            kind = ResumeRequirementMatcher.fromRole(roles.get(node.getNodeId()));
                        }
                    }
                }
            }
        }

        private boolean betterThan(Group other) {
            if (domainConflict != other.domainConflict) {
                return !domainConflict;
            }
            if ((actionHits > 0) != (other.actionHits > 0)) {
                return actionHits > 0;
            }
            if (allActions != other.allActions) {
                return allActions;
            }
            if (domainSufficient != other.domainSufficient) {
                return domainSufficient;
            }
            if (actionHits != other.actionHits) {
                return actionHits > other.actionHits;
            }
            if (domainHits != other.domainHits) {
                return domainHits > other.domainHits;
            }
            if (allTechnical != other.allTechnical) {
                return allTechnical;
            }
            return supportingHits > other.supportingHits;
        }
    }
}
