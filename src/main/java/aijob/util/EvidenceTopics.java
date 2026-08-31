package aijob.util;

import aijob.dto.RequirementCategory;
import aijob.dto.TailorAnalysisRequirement;

import java.util.Set;

public final class EvidenceTopics {

    public static final String ENHANCED_MAINTAIN = "maintain";
    public static final String ENHANCED_TESTING = "testing";
    public static final String ENHANCED_BUILD_TOOL = "build-tool";
    public static final String ENHANCED_DATA_STORE = "data-store";
    public static final String ENHANCED_METRIC = "metric";
    public static final String ENHANCED_API_CONTRACT = "api-contract";
    public static final String ENHANCED_NX_MONOREPO = "nx-monorepo";
    public static final String ENHANCED_EVENT_DRIVEN = "event-driven";

    private static final Set<String> ENHANCED_CATALOG = Set.of(
            ENHANCED_MAINTAIN,
            ENHANCED_TESTING,
            ENHANCED_BUILD_TOOL,
            ENHANCED_DATA_STORE,
            ENHANCED_METRIC,
            ENHANCED_API_CONTRACT,
            ENHANCED_NX_MONOREPO,
            ENHANCED_EVENT_DRIVEN
    );

    private static final Set<String> QUALIFICATION_NOISE = Set.of(
            "degree",
            "bachelor",
            "bachelors",
            "master",
            "pursuing",
            "completed",
            "related",
            "field",
            "education",
            "qualification"
    );

    private EvidenceTopics() {
    }

    public static String topicOf(TailorAnalysisRequirement requirement) {
        if (requirement == null) {
            return "";
        }
        if (RequirementCategory.QUALIFICATION.equals(requirement.getCategory())) {
            String fromRequirement = distinctiveQualificationTopic(requirement.getRequirement());
            if (!fromRequirement.isBlank()) {
                return fromRequirement;
            }
        }
        if (RequirementCategory.RESPONSIBILITY.equals(requirement.getCategory())) {
            if (requirement.getRequirement() != null && !requirement.getRequirement().isBlank()) {
                return requirement.getRequirement().strip();
            }
        }
        if (requirement.getExactTerms() != null && !requirement.getExactTerms().isEmpty()
                && requirement.getExactTerms().get(0) != null
                && !requirement.getExactTerms().get(0).isBlank()) {
            String first = requirement.getExactTerms().get(0).strip();
            if (!RequirementCategory.QUALIFICATION.equals(requirement.getCategory())
                    || !QUALIFICATION_NOISE.contains(EvidenceText.normalize(first))) {
                return first;
            }
        }
        if (requirement.getRequirement() == null) {
            return "";
        }
        return requirement.getRequirement().strip();
    }

    public static String topicKey(String topic) {
        return EvidenceText.normalize(topic);
    }

    public static boolean isEnhancedCatalogTopic(String topic) {
        return ENHANCED_CATALOG.contains(topicKey(topic));
    }

    public static boolean isEnhancedMetricTopic(String topic) {
        return ENHANCED_METRIC.equals(topicKey(topic));
    }

    public static String topicKey(TailorAnalysisRequirement requirement) {
        return topicKey(topicOf(requirement));
    }

    private static String distinctiveQualificationTopic(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return "";
        }
        if (TermAliasNormalizer.matches(requirement, "computer science")) {
            return "Computer Science";
        }
        return "";
    }
}
