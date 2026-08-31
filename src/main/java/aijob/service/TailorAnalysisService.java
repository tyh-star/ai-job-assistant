package aijob.service;

import aijob.dto.TailorAnalysisResponse;

public interface TailorAnalysisService {

    TailorAnalysisResponse analyze(String email, Long jobId, Long resumeId);

    TailorAnalysisResponse refreshProfile(String email, Long jobId, Long resumeId);
}
