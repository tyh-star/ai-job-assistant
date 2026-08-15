package aijob.service;

import aijob.dto.ResumeAnalysisResponse;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface ResumeService {

    ResumeResponse uploadResume(String email, MultipartFile file);

    List<ResumeResponse> getMyResumes(String email);

    ResumeResponse getResume(String email, Long id);

    void deleteResume(String email, Long id);

    ResumeAnalysisResponse analyzeResume(String email, Long resumeId);

    List<ResumeDocumentNode> getDocumentNodes(String email, Long resumeId);
}
