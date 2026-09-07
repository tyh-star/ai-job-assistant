package aijob.service;

import aijob.dto.ResumeAnalysisResponse;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeFileDownload;
import aijob.dto.ResumeQualityScoreResponse;
import aijob.dto.ResumeResponse;
import aijob.dto.ResumeStructure;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ResumeService {

    ResumeResponse uploadResume(String email, MultipartFile file);

    List<ResumeResponse> getMyResumes(String email, boolean includeDerived);

    ResumeResponse getResume(String email, Long id);

    void deleteResume(String email, Long id);

    void deleteResumeFamily(String email, Long id);

    ResumeAnalysisResponse analyzeResume(String email, Long resumeId);

    List<ResumeDocumentNode> getDocumentNodes(String email, Long resumeId);

    ResumeStructure getResumeStructure(String email, Long resumeId);

    ResumeStructure updateResumeStructure(String email, Long resumeId, ResumeStructure structure);

    ResumeResponse canonicalizeResume(String email, Long pdfResumeId);

    ResumeFileDownload downloadResume(String email, Long resumeId);

    /**
     * The resume's own structure/content-completeness score -- never a match score
     * against any job. Deterministic and computed only from data already stored on
     * this resume; never calls OpenAI or any other AI service. See
     * {@link aijob.util.ResumeQualityScorer}.
     */
    ResumeQualityScoreResponse getQualityScore(String email, Long resumeId);
}
