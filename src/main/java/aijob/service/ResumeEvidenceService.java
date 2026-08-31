package aijob.service;

import aijob.dto.ResumeEvidenceItemResponse;
import aijob.dto.SaveResumeEvidenceRequest;

import java.util.List;

public interface ResumeEvidenceService {

    List<ResumeEvidenceItemResponse> list(String email, Long resumeId);

    SaveResult save(String email, Long resumeId, SaveResumeEvidenceRequest request);

    void delete(String email, Long resumeId, Long evidenceId);

    record SaveResult(ResumeEvidenceItemResponse item, boolean created) {
    }
}
