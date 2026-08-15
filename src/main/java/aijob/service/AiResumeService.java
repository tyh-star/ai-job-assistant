package aijob.service;

import aijob.dto.JobMatchResponse;
import aijob.dto.ResumeAnalysisResponse;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.enums.TailorMode;

import java.util.List;

public interface AiResumeService {

    ResumeAnalysisResponse analyzeResume(String resumeText);

    JobMatchResponse matchResumeToJob(String resumeText, String jobDescription);

    List<TailorChange> generateTailorChanges(
            List<ResumeDocumentNode> nodes,
            String jobDescription,
            TailorMode mode
    );
}
