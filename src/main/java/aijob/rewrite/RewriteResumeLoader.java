package aijob.rewrite;

import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeStructure;
import aijob.entity.Resume;
import aijob.entity.User;
import aijob.exception.InvalidResumeFileException;
import aijob.exception.ResumeNotFoundException;
import aijob.exception.UserNotFoundException;
import aijob.mapper.ResumeMapper;
import aijob.mapper.UserMapper;
import aijob.service.ResumeDocumentService;
import aijob.util.ResumeAnalysisInput;
import aijob.util.ResumeAnalysisInputFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the DOCX a rewrite works on, reusing the existing resume resolution: a native
 * DOCX, or the canonical DOCX child of a PDF (created by POST /resumes/{id}/canonicalize).
 */
@Component
public class RewriteResumeLoader {

    private final UserMapper userMapper;
    private final ResumeMapper resumeMapper;
    private final ResumeAnalysisInputFactory inputFactory;
    private final ResumeDocumentService resumeDocumentService;

    public RewriteResumeLoader(UserMapper userMapper, ResumeMapper resumeMapper,
                               ResumeAnalysisInputFactory inputFactory,
                               ResumeDocumentService resumeDocumentService) {
        this.userMapper = userMapper;
        this.resumeMapper = resumeMapper;
        this.inputFactory = inputFactory;
        this.resumeDocumentService = resumeDocumentService;
    }

    public record LoadedResume(User user, Resume resume, Path docxPath, List<ResumeDocumentNode> nodes,
                               List<String> knownNames) {
    }

    public User user(String email) {
        User user = userMapper.findByEmail(email);
        if (user == null) {
            throw new UserNotFoundException("User not found");
        }
        return user;
    }

    public Resume ownedResume(Long resumeId, Long userId) {
        Resume resume = resumeId == null ? null : resumeMapper.findById(resumeId);
        if (resume == null || !userId.equals(resume.getUserId())) {
            throw new ResumeNotFoundException("Resume not found");
        }
        return resume;
    }

    public LoadedResume load(String email, Long resumeId) {
        User user = user(email);
        Resume resume = ownedResume(resumeId, user.getId());
        ResumeAnalysisInput input = inputFactory.resolve(resume, user.getId());
        Path docx = docxPath(input);
        List<ResumeDocumentNode> nodes = resumeDocumentService.parseDocx(docx);
        return new LoadedResume(user, resume, docx, nodes, knownNames(user, input, nodes));
    }

    private static Path docxPath(ResumeAnalysisInput input) {
        for (Resume candidate : new Resume[]{input.canonicalChildResume(), input.selectedResume()}) {
            if (candidate != null && candidate.getFilePath() != null
                    && "docx".equalsIgnoreCase(candidate.getFileType())
                    && Files.exists(Path.of(candidate.getFilePath()))) {
                return Path.of(candidate.getFilePath());
            }
        }
        throw new InvalidResumeFileException(
                "Resume rewrite needs a DOCX. For a PDF, confirm its recognized content first.");
    }

    private static List<String> knownNames(User user, ResumeAnalysisInput input, List<ResumeDocumentNode> nodes) {
        List<String> names = new ArrayList<>();
        if (user.getName() != null) {
            names.add(user.getName());
        }
        ResumeStructure structure = input.structuredResume();
        if (structure != null && structure.getContact() != null) {
            names.add(structure.getContact().getFullName());
        }
        // A resume usually opens with the person's name on its own short line.
        for (ResumeDocumentNode node : nodes) {
            String text = node.getText() == null ? "" : node.getText().trim();
            if (text.isEmpty()) {
                continue;
            }
            boolean nameLike = text.length() <= 40 && text.split("\\s+").length <= 4
                    && text.chars().noneMatch(Character::isDigit) && !text.contains("@");
            if (nameLike) {
                names.add(text);
            }
            break;
        }
        return names;
    }
}
