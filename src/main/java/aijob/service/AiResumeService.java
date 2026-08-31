package aijob.service;

import aijob.dto.ConservativeCandidateRanking;
import aijob.dto.ConservativeRewriteCandidate;
import aijob.dto.ConservativeRewriteCandidatesResponse;
import aijob.dto.ConservativeRewritePlan;
import aijob.dto.ConservativeRewriteRepair;
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

    ConservativeRewriteCandidatesResponse generateConstrainedTailorCandidates(
            ResumeDocumentNode node,
            ConservativeRewritePlan plan,
            String jobDescription,
            ConservativeRewriteRepair repair
    );

    ConservativeCandidateRanking rankConservativeCandidates(
            String originalText,
            String rewriteGoal,
            List<String> relevantJobTerms,
            List<ConservativeRewriteCandidate> candidates
    );
}
