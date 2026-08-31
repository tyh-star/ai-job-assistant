package aijob.util;

import aijob.dto.CoverageStatus;
import aijob.dto.EvidenceKind;
import aijob.dto.JobRequirementDraft;
import aijob.dto.RequirementCategory;
import aijob.dto.RequirementPriority;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeStructure;
import aijob.dto.TailorAnalysisRequirement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic resume evidence matching for Tailor Analysis V1.
 */
public final class ResumeRequirementMatcher {

    private static final Set<ResumeNodeRole> BLOCKED_ROLES = Set.of(
            ResumeNodeRole.HEADING,
            ResumeNodeRole.DATE,
            ResumeNodeRole.CONTACT,
            ResumeNodeRole.LINK
    );
    private static final Set<ResumeNodeRole> BULLET_ROLES = Set.of(
            ResumeNodeRole.EXPERIENCE_BULLET,
            ResumeNodeRole.PROJECT_BULLET
    );

    private ResumeRequirementMatcher() {
    }

    public static List<TailorAnalysisRequirement> match(
            List<JobRequirementDraft> drafts,
            List<ResumeDocumentNode> nodes) {
        return match(drafts, nodes, null);
    }

    public static List<TailorAnalysisRequirement> match(
            List<JobRequirementDraft> drafts,
            List<ResumeDocumentNode> nodes,
            ResumeStructure structure) {

        List<Classified> classified = classifyEligible(nodes);
        List<TailorAnalysisRequirement> results = new ArrayList<>();
        for (JobRequirementDraft draft : drafts == null ? List.<JobRequirementDraft>of() : drafts) {
            if (draft == null || isBlank(draft.getRequirement())) {
                continue;
            }
            TailorAnalysisRequirement item = new TailorAnalysisRequirement();
            item.setId(RequirementProfileIds.stableId(
                    draft.getCategory(),
                    draft.getPriority(),
                    draft.getRequirement()
            ));
            item.setCategory(normalizeCategory(draft.getCategory()));
            item.setPriority(normalizePriority(draft.getPriority()));
            item.setRequirement(draft.getRequirement().strip());
            item.setExactTerms(cleanTerms(draft.getExactTerms()));
            fillCoverage(item, classified, nodes, structure);
            results.add(item);
        }
        return results;
    }

    public static TailorAnalysisRequirement sanitizeEvidence(
            TailorAnalysisRequirement requirement,
            List<ResumeDocumentNode> nodes) {
        return sanitizeEvidence(
                requirement,
                ResumeAnalysisInputFactory.matchingNodeSources(nodes),
                rolesFrom(nodes)
        );
    }

    public static TailorAnalysisRequirement sanitizeEvidence(
            TailorAnalysisRequirement requirement,
            ResumeAnalysisInput input) {
        Map<String, AnalysisEvidenceSource> sources = input == null
                ? Map.of()
                : input.evidenceSourcesById();
        List<ResumeDocumentNode> nodes = input == null ? List.of() : input.matchingNodes();
        return sanitizeEvidence(requirement, sources, rolesFrom(nodes));
    }

    public static TailorAnalysisRequirement sanitizeEvidence(
            TailorAnalysisRequirement requirement,
            Map<String, AnalysisEvidenceSource> sources) {
        return sanitizeEvidence(requirement, sources, Map.of());
    }

    private static TailorAnalysisRequirement sanitizeEvidence(
            TailorAnalysisRequirement requirement,
            Map<String, AnalysisEvidenceSource> sources,
            Map<String, ResumeNodeRole> roles) {

        if (requirement == null) {
            return null;
        }
        Map<String, AnalysisEvidenceSource> registry = sources == null ? Map.of() : sources;
        Map<String, ResumeNodeRole> classifiedRoles = roles == null ? Map.of() : roles;
        List<String> ids = requirement.getEvidenceNodeIds() == null
                ? List.of()
                : requirement.getEvidenceNodeIds();
        List<String> quotes = requirement.getEvidenceQuotes() == null
                ? List.of()
                : requirement.getEvidenceQuotes();
        List<String> keptIds = new ArrayList<>();
        List<String> keptQuotes = new ArrayList<>();
        LinkedHashSet<String> seenIds = new LinkedHashSet<>();
        String lastAcceptedId = null;
        int limit = Math.max(ids.size(), quotes.size());
        for (int i = 0; i < limit; i++) {
            String nodeId = i < ids.size() ? ids.get(i) : null;
            String quote = i < quotes.size() ? quotes.get(i) : null;
            if (isBlank(nodeId)) {
                if (lastAcceptedId != null && keepAdditionalQuote(registry.get(lastAcceptedId), quote, keptQuotes)) {
                    keptQuotes.add(quote.strip());
                }
                continue;
            }
            AnalysisEvidenceSource source = registry.get(nodeId);
            if (!isVerifiedSource(requirement, source, quote, classifiedRoles)) {
                continue;
            }
            String resolvedQuote = isBlank(quote) ? source.text() : quote.strip();
            if (!seenIds.add(nodeId)) {
                if (keepAdditionalQuote(source, resolvedQuote, keptQuotes)) {
                    keptQuotes.add(resolvedQuote);
                }
                lastAcceptedId = nodeId;
                continue;
            }
            keptIds.add(nodeId);
            keptQuotes.add(resolvedQuote);
            lastAcceptedId = nodeId;
        }
        requirement.setEvidenceNodeIds(keptIds);
        requirement.setEvidenceQuotes(keptQuotes);
        if (RequirementCategory.QUALIFICATION.equals(requirement.getCategory()) && !keptIds.isEmpty()) {
            requirement.setEvidenceKind(EvidenceKind.EDUCATION);
        } else {
            requirement.setEvidenceKind(strongestKindFromSources(keptIds, registry, classifiedRoles));
        }
        if (keptIds.isEmpty()
                && (CoverageStatus.SUPPORTED.equals(requirement.getStatus())
                || CoverageStatus.PARTIALLY_SUPPORTED.equals(requirement.getStatus()))) {
            requirement.setStatus(CoverageStatus.MISSING_EVIDENCE);
            requirement.setEvidenceKind(null);
        }
        return requirement;
    }

    public static String strongestEvidenceKind(
            TailorAnalysisRequirement requirement,
            List<ResumeDocumentNode> nodes) {

        if (requirement == null || requirement.getEvidenceNodeIds() == null) {
            return null;
        }
        Map<String, ResumeNodeRole> roles = new LinkedHashMap<>();
        for (Classified classified : classifyEligible(nodes)) {
            roles.put(classified.node.getNodeId(), classified.role);
        }
        return strongestKind(requirement.getEvidenceNodeIds(), roles);
    }

    private static void fillCoverage(
            TailorAnalysisRequirement item,
            List<Classified> classified,
            List<ResumeDocumentNode> nodes,
            ResumeStructure structure) {
        if (RequirementCategory.QUALIFICATION.equals(item.getCategory())) {
            fillQualification(item, nodes, structure);
            return;
        }
        if (RequirementCategory.RESPONSIBILITY.equals(item.getCategory())) {
            ResponsibilityEvidenceMatcher.fill(item, nodes);
            return;
        }

        List<String> terms = item.getExactTerms();
        if ((RequirementCategory.SOFT_SKILL.equals(item.getCategory())
                || RequirementCategory.OTHER_KEYWORD.equals(item.getCategory()))
                && terms.isEmpty()) {
            item.setStatus(CoverageStatus.NOT_APPLICABLE);
            item.setEvidenceNodeIds(List.of());
            item.setEvidenceQuotes(List.of());
            return;
        }

        List<Hit> hits = new ArrayList<>();
        Set<String> matchedTerms = new LinkedHashSet<>();
        for (String term : terms) {
            boolean matched = false;
            for (Classified classifiedNode : classified) {
                if (!containsTerm(classifiedNode.node.getText(), term)) {
                    continue;
                }
                matched = true;
                hits.add(new Hit(classifiedNode, quoteFor(classifiedNode.node.getText(), term)));
            }
            if (matched) {
                matchedTerms.add(EvidenceText.normalize(term));
            }
        }

        if (!terms.isEmpty() && matchedTerms.size() == terms.size()) {
            applyHits(item, hits, CoverageStatus.SUPPORTED);
            return;
        }
        if (!terms.isEmpty() && !matchedTerms.isEmpty()) {
            applyHits(item, hits, CoverageStatus.PARTIALLY_SUPPORTED);
            return;
        }
        if (terms.isEmpty() && hasLooseOverlap(item.getRequirement(), classified)) {
            applyHits(item, looseHits(item.getRequirement(), classified), CoverageStatus.PARTIALLY_SUPPORTED);
            return;
        }
        item.setStatus(CoverageStatus.MISSING_EVIDENCE);
        item.setEvidenceKind(null);
        item.setEvidenceNodeIds(List.of());
        item.setEvidenceQuotes(List.of());
    }

    private static void fillQualification(
            TailorAnalysisRequirement item,
            List<ResumeDocumentNode> nodes,
            ResumeStructure structure) {

        QualificationMatcher.EducationHit hit = QualificationMatcher.match(item, nodes, structure);
        if (hit == null) {
            item.setStatus(CoverageStatus.MISSING_EVIDENCE);
            item.setEvidenceKind(null);
            item.setEvidenceNodeIds(List.of());
            item.setEvidenceQuotes(List.of());
            item.setCoverageReason(null);
            return;
        }
        item.setStatus(hit.inProgress() ? CoverageStatus.PARTIALLY_SUPPORTED : CoverageStatus.SUPPORTED);
        item.setEvidenceKind(EvidenceKind.EDUCATION);
        item.setEvidenceNodeIds(List.of(hit.nodeId()));
        item.setEvidenceQuotes(hit.quotes() == null ? List.of() : List.copyOf(hit.quotes()));
        item.setCoverageReason(hit.inProgress() ? QualificationMatcher.CURRENTLY_PURSUING : null);
    }

    private static void applyHits(TailorAnalysisRequirement item, List<Hit> hits, String status) {
        hits.sort((left, right) -> Double.compare(
                EvidenceKind.strength(fromRole(right.classified.role)),
                EvidenceKind.strength(fromRole(left.classified.role))
        ));
        List<String> ids = new ArrayList<>();
        List<String> quotes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String kind = null;
        for (Hit hit : hits) {
            kind = EvidenceKind.stronger(kind, fromRole(hit.classified.role));
            String nodeId = hit.classified.node.getNodeId();
            if (!seen.add(nodeId) || ids.size() >= 5) {
                continue;
            }
            ids.add(nodeId);
            quotes.add(hit.quote);
        }
        item.setStatus(status);
        item.setEvidenceKind(kind);
        item.setEvidenceNodeIds(ids);
        item.setEvidenceQuotes(quotes);
    }

    static String fromRole(ResumeNodeRole role) {
        if (role == null) {
            return EvidenceKind.OTHER;
        }
        return switch (role) {
            case EXPERIENCE_BULLET -> EvidenceKind.EXPERIENCE_BULLET;
            case PROJECT_BULLET -> EvidenceKind.PROJECT_BULLET;
            case SUMMARY_BULLET -> EvidenceKind.SUMMARY;
            case SKILLS -> EvidenceKind.SKILLS_ONLY;
            default -> EvidenceKind.OTHER;
        };
    }

    private static boolean isVerifiedSource(
            TailorAnalysisRequirement requirement,
            AnalysisEvidenceSource source,
            String quote,
            Map<String, ResumeNodeRole> roles) {
        if (source == null || isBlank(source.id()) || isBlank(source.text())) {
            return false;
        }
        if (source.kind() == AnalysisEvidenceSource.Kind.STRUCTURED_EDUCATION) {
            return AnalysisEvidenceValidator.quoteMatches(source, quote);
        }
        ResumeNodeRole role = resolveRole(source, roles);
        if (RequirementCategory.QUALIFICATION.equals(requirement.getCategory())) {
            if (role == ResumeNodeRole.HEADING
                    || role == ResumeNodeRole.CONTACT
                    || role == ResumeNodeRole.LINK) {
                return false;
            }
            return AnalysisEvidenceValidator.quoteMatches(source, quote);
        }
        if (!AnalysisEvidenceValidator.isUsableMatchingNode(role)) {
            return false;
        }
        return AnalysisEvidenceValidator.quoteMatches(source, quote);
    }

    private static boolean keepAdditionalQuote(
            AnalysisEvidenceSource source,
            String quote,
            List<String> keptQuotes) {
        if (source == null || isBlank(quote) || !AnalysisEvidenceValidator.quoteMatches(source, quote)) {
            return false;
        }
        String normalized = EvidenceText.normalize(quote);
        for (String existing : keptQuotes) {
            if (EvidenceText.normalize(existing).equals(normalized)) {
                return false;
            }
        }
        return true;
    }

    private static ResumeNodeRole resolveRole(
            AnalysisEvidenceSource source,
            Map<String, ResumeNodeRole> roles) {
        if (source != null && roles != null && roles.containsKey(source.id())) {
            return roles.get(source.id());
        }
        return source == null ? ResumeNodeRole.OTHER : roleOf(source);
    }

    private static Map<String, ResumeNodeRole> rolesFrom(List<ResumeDocumentNode> nodes) {
        Map<String, ResumeNodeRole> roles = new LinkedHashMap<>();
        for (ConservativeTailorPlanner.ClassifiedNode classified : ConservativeTailorPlanner.classify(nodes)) {
            if (classified.node() == null || isBlank(classified.node().getNodeId())) {
                continue;
            }
            roles.put(classified.node().getNodeId(), classified.role());
        }
        return roles;
    }

    private static ResumeNodeRole roleOf(AnalysisEvidenceSource source) {
        ResumeDocumentNode node = new ResumeDocumentNode(source.id(), "PARAGRAPH", source.text());
        List<ConservativeTailorPlanner.ClassifiedNode> classified = ConservativeTailorPlanner.classify(List.of(node));
        if (classified.isEmpty()) {
            return ResumeNodeRole.OTHER;
        }
        return classified.get(0).role();
    }

    private static String strongestKind(List<String> nodeIds, Map<String, ResumeNodeRole> roles) {
        String kind = null;
        if (nodeIds == null) {
            return null;
        }
        for (String nodeId : nodeIds) {
            kind = EvidenceKind.stronger(kind, fromRole(roles.get(nodeId)));
        }
        return kind;
    }

    private static String strongestKindFromSources(
            List<String> nodeIds,
            Map<String, AnalysisEvidenceSource> sources,
            Map<String, ResumeNodeRole> roles) {
        String kind = null;
        if (nodeIds == null) {
            return null;
        }
        for (String nodeId : nodeIds) {
            AnalysisEvidenceSource source = sources.get(nodeId);
            if (source != null && source.kind() == AnalysisEvidenceSource.Kind.STRUCTURED_EDUCATION) {
                kind = EvidenceKind.stronger(kind, EvidenceKind.EDUCATION);
                continue;
            }
            kind = EvidenceKind.stronger(kind, fromRole(resolveRole(source, roles)));
        }
        return kind;
    }

    private static boolean hasLooseOverlap(String requirement, List<Classified> classified) {
        return !looseHits(requirement, classified).isEmpty();
    }

    private static List<Hit> looseHits(String requirement, List<Classified> classified) {
        List<String> requiredAliases = distinctiveAliases(requirement);
        List<String> words = contentWords(requirement);
        if (requiredAliases.isEmpty() && words.size() < 2) {
            return List.of();
        }
        List<Hit> hits = new ArrayList<>();
        for (Classified classifiedNode : classified) {
            if (!BULLET_ROLES.contains(classifiedNode.role)) {
                continue;
            }
            if (!requiredAliases.isEmpty()) {
                boolean aliasHit = false;
                for (String alias : requiredAliases) {
                    if (TermAliasNormalizer.matches(classifiedNode.node.getText(), alias)) {
                        aliasHit = true;
                        break;
                    }
                }
                if (!aliasHit) {
                    continue;
                }
            }
            String normalized = EvidenceText.normalize(classifiedNode.node.getText());
            int matched = 0;
            for (String word : words) {
                if (containsTerm(normalized, word)) {
                    matched++;
                }
            }
            if (!requiredAliases.isEmpty() || matched >= Math.max(2, (int) Math.ceil(words.size() * 0.5))) {
                hits.add(new Hit(classifiedNode, classifiedNode.node.getText()));
            }
        }
        return hits;
    }

    private static List<String> distinctiveAliases(String requirement) {
        List<String> aliases = new ArrayList<>();
        if (requirement == null) {
            return aliases;
        }
        if (TermAliasNormalizer.matches(requirement, "mvc")) {
            aliases.add("mvc");
        }
        if (TermAliasNormalizer.matches(requirement, "rest api")) {
            aliases.add("rest api");
        }
        if (TermAliasNormalizer.matches(requirement, "computer science")) {
            aliases.add("computer science");
        }
        return aliases;
    }

    static boolean containsTerm(String text, String term) {
        return TermAliasNormalizer.matches(text, term);
    }

    private static String quoteFor(String text, String term) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        if (stripped.length() <= 180) {
            return stripped;
        }
        String lower = EvidenceText.normalize(stripped);
        String needle = EvidenceText.normalize(term);
        int index = lower.indexOf(needle);
        if (index < 0) {
            return stripped.substring(0, 180);
        }
        int start = Math.max(0, index - 20);
        int end = Math.min(stripped.length(), index + needle.length() + 80);
        return stripped.substring(start, end).strip();
    }

    private static List<Classified> classifyEligible(List<ResumeDocumentNode> nodes) {
        List<Classified> eligible = new ArrayList<>();
        for (ConservativeTailorPlanner.ClassifiedNode classified :
                ConservativeTailorPlanner.classify(nodes)) {
            if (classified.node() == null || BLOCKED_ROLES.contains(classified.role())) {
                continue;
            }
            eligible.add(new Classified(classified.node(), classified.role()));
        }
        return eligible;
    }

    private static List<String> cleanTerms(List<String> terms) {
        if (terms == null) {
            return List.of();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        List<String> cleaned = new ArrayList<>();
        for (String term : terms) {
            if (isBlank(term)) {
                continue;
            }
            String key = EvidenceText.normalize(term);
            if (unique.add(key)) {
                cleaned.add(term.strip());
            }
        }
        return cleaned;
    }

    private static List<String> contentWords(String text) {
        List<String> words = new ArrayList<>();
        for (String word : EvidenceText.normalize(text).split("[^a-z0-9+#]+")) {
            if (word.length() >= 4) {
                words.add(word);
            }
        }
        return words;
    }

    private static String normalizeCategory(String category) {
        if (category == null) {
            return RequirementCategory.OTHER_KEYWORD;
        }
        String value = category.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
        return switch (value) {
            case RequirementCategory.RESPONSIBILITY,
                    RequirementCategory.QUALIFICATION,
                    RequirementCategory.DOMAIN,
                    RequirementCategory.SOFT_SKILL,
                    RequirementCategory.HARD_SKILL,
                    RequirementCategory.OTHER_KEYWORD -> value;
            default -> RequirementCategory.OTHER_KEYWORD;
        };
    }

    private static String normalizePriority(String priority) {
        if (priority == null) {
            return RequirementPriority.PREFERRED;
        }
        String value = priority.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
        return switch (value) {
            case RequirementPriority.MUST_HAVE,
                    RequirementPriority.PREFERRED,
                    RequirementPriority.OPTIONAL -> value;
            default -> RequirementPriority.PREFERRED;
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Classified(ResumeDocumentNode node, ResumeNodeRole role) {
    }

    private record Hit(Classified classified, String quote) {
    }
}
