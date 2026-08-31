package aijob.util;

import aijob.dto.CoverageStatus;
import aijob.dto.EvidenceAvailability;
import aijob.dto.RequirementCategory;
import aijob.dto.TailorAnalysisRequirement;
import aijob.entity.ResumeEvidenceItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EvidenceAvailabilityMapperTest {

    @Test
    void mapsInResumeUserConfirmedRejectedAndNone() {
        TailorAnalysisRequirement inResume = req("Java", CoverageStatus.SUPPORTED, List.of("p-16"));
        TailorAnalysisRequirement confirmed = req("Docker", CoverageStatus.MISSING_EVIDENCE, List.of());
        TailorAnalysisRequirement rejected = req("Kubernetes", CoverageStatus.MISSING_EVIDENCE, List.of());
        TailorAnalysisRequirement none = req("AWS", CoverageStatus.MISSING_EVIDENCE, List.of());

        ResumeEvidenceItem used = item(12L, "docker", "CONFIRMED_USED");
        used.setTopicLabel("Docker");
        used.setCategory(RequirementCategory.HARD_SKILL);
        used.setActionText("Containerized the Spring Boot application with Docker");
        ResumeEvidenceItem notUsed = item(13L, "kubernetes", "CONFIRMED_NOT_USED");

        EvidenceAvailabilityMapper.apply(List.of(inResume, confirmed, rejected, none), List.of(used, notUsed));

        assertEquals(EvidenceAvailability.IN_RESUME, inResume.getEvidenceAvailability());
        assertEquals(EvidenceAvailability.USER_CONFIRMED, confirmed.getEvidenceAvailability());
        assertEquals(List.of(12L), confirmed.getEvidenceItemIds());
        assertEquals(EvidenceAvailability.USER_REJECTED, rejected.getEvidenceAvailability());
        assertEquals(EvidenceAvailability.NONE, none.getEvidenceAvailability());
    }

    @Test
    void mixedUsedAndRejectedForSameTopicIsUserConfirmedOnly() {
        TailorAnalysisRequirement docker = req("Docker", CoverageStatus.MISSING_EVIDENCE, List.of());
        ResumeEvidenceItem used = item(12L, "docker", "CONFIRMED_USED");
        used.setTopicLabel("Docker");
        used.setCategory(RequirementCategory.HARD_SKILL);
        used.setActionText("Containerized the Spring Boot application with Docker");
        ResumeEvidenceItem rejected = item(13L, "docker", "CONFIRMED_NOT_USED");

        EvidenceAvailabilityMapper.apply(List.of(docker), List.of(used, rejected));

        assertEquals(EvidenceAvailability.USER_CONFIRMED, docker.getEvidenceAvailability());
        assertEquals(List.of(12L), docker.getEvidenceItemIds());
        assertNotEquals(EvidenceAvailability.USER_REJECTED, docker.getEvidenceAvailability());
    }

    private TailorAnalysisRequirement req(String topic, String status, List<String> nodeIds) {
        TailorAnalysisRequirement requirement = new TailorAnalysisRequirement();
        requirement.setRequirement(topic);
        requirement.setExactTerms(List.of(topic));
        requirement.setStatus(status);
        requirement.setEvidenceNodeIds(nodeIds);
        return requirement;
    }

    private ResumeEvidenceItem item(Long id, String topicKey, String status) {
        ResumeEvidenceItem item = new ResumeEvidenceItem();
        item.setId(id);
        item.setTopicKey(topicKey);
        item.setAnswerStatus(status);
        return item;
    }
}
