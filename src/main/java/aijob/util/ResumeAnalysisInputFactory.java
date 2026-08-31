package aijob.util;

import aijob.dto.PdfLayout;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeStructure;
import aijob.dto.ResumeTextBlock;
import aijob.entity.Resume;
import aijob.enums.ResumeOrigin;
import aijob.enums.ResumeReviewStatus;
import aijob.mapper.ResumeMapper;
import aijob.service.ResumeDocumentService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a single analysis view from PDF parent, canonical child, and native DOCX.
 */
@Component
public class ResumeAnalysisInputFactory {

    public static final String STRUCTURED_EDUCATION_PREFIX = "edu-structure-";

    private final ResumeMapper resumeMapper;
    private final ResumeDocumentService resumeDocumentService;
    private final ObjectMapper objectMapper;

    public ResumeAnalysisInputFactory(
            ResumeMapper resumeMapper,
            ResumeDocumentService resumeDocumentService,
            ObjectMapper objectMapper) {
        this.resumeMapper = resumeMapper;
        this.resumeDocumentService = resumeDocumentService;
        this.objectMapper = objectMapper;
    }

    public ResumeAnalysisInput resolve(Resume selected, Long userId) {
        if (selected == null || userId == null) {
            throw new IllegalArgumentException("Resume and userId are required");
        }
        if (isTemplate(selected)) {
            return resolveTemplateChild(selected, userId);
        }
        if (isPdf(selected)) {
            return resolvePdfParent(selected, userId);
        }
        return resolveNativeDocx(selected);
    }

    private ResumeAnalysisInput resolvePdfParent(Resume parent, Long userId) {
        ResumeStructure structure = readStructure(parent.getStructuredJson());
        Resume child = loadReadyCanonicalChild(parent, userId);
        List<ResumeDocumentNode> matchingNodes = parseDocxIfPresent(child);
        if (matchingNodes.isEmpty()) {
            matchingNodes = layoutNodes(parent);
        }
        if (matchingNodes.isEmpty()) {
            matchingNodes = linesAsNodes(parent.getExtractedText());
        }
        return new ResumeAnalysisInput(
                parent,
                parent,
                child,
                matchingNodes,
                buildRegistry(matchingNodes, parent, structure),
                structure,
                parent.getId()
        );
    }

    private ResumeAnalysisInput resolveTemplateChild(Resume child, Long userId) {
        Resume parent = ResumeFamily.uploadRoot(resumeMapper, child, userId);
        ResumeStructure structure = readStructure(parent.getStructuredJson());
        List<ResumeDocumentNode> matchingNodes = parseDocxIfPresent(child);
        if (matchingNodes.isEmpty()) {
            matchingNodes = linesAsNodes(child.getExtractedText());
        }
        Resume canonical = child;
        if (parent.getCanonicalResumeId() != null && parent.getCanonicalResumeId().equals(child.getId())) {
            canonical = child;
        }
        return new ResumeAnalysisInput(
                child,
                parent,
                canonical,
                matchingNodes,
                buildRegistry(matchingNodes, parent, structure),
                structure,
                parent.getId()
        );
    }

    private ResumeAnalysisInput resolveNativeDocx(Resume resume) {
        ResumeStructure structure = readStructure(resume.getStructuredJson());
        List<ResumeDocumentNode> matchingNodes = parseDocxIfPresent(resume);
        if (matchingNodes.isEmpty()) {
            matchingNodes = linesAsNodes(resume.getExtractedText());
        }
        return new ResumeAnalysisInput(
                resume,
                resume,
                null,
                matchingNodes,
                buildRegistry(matchingNodes, resume, structure),
                structure,
                resume.getId()
        );
    }

    public static Map<String, AnalysisEvidenceSource> matchingNodeSources(List<ResumeDocumentNode> nodes) {
        Map<String, AnalysisEvidenceSource> sources = new LinkedHashMap<>();
        if (nodes == null) {
            return sources;
        }
        for (ResumeDocumentNode node : nodes) {
            if (node == null || isBlank(node.getNodeId()) || isBlank(node.getText())) {
                continue;
            }
            sources.put(node.getNodeId(), new AnalysisEvidenceSource(
                    node.getNodeId(),
                    node.getText(),
                    AnalysisEvidenceSource.Kind.MATCHING_NODE
            ));
        }
        return sources;
    }

    Map<String, AnalysisEvidenceSource> buildRegistry(
            List<ResumeDocumentNode> matchingNodes,
            Resume structureOwner,
            ResumeStructure structure) {
        Map<String, AnalysisEvidenceSource> sources = matchingNodeSources(matchingNodes);
        PdfLayout layout = readLayout(structureOwner == null ? null : structureOwner.getLayoutJson());
        if (layout != null && layout.getBlocks() != null) {
            for (ResumeTextBlock block : layout.getBlocks()) {
                if (block == null || isBlank(block.getId()) || isBlank(block.getText())) {
                    continue;
                }
                sources.putIfAbsent(block.getId(), new AnalysisEvidenceSource(
                        block.getId(),
                        block.getText(),
                        AnalysisEvidenceSource.Kind.LAYOUT_BLOCK
                ));
            }
        }
        if (structure != null && structure.getEducation() != null) {
            int index = 0;
            for (ResumeStructure.ResumeEducation education : structure.getEducation()) {
                if (education == null) {
                    continue;
                }
                String text = QualificationMatcher.joinEducation(education);
                if (text.isBlank()) {
                    index++;
                    continue;
                }
                String id = STRUCTURED_EDUCATION_PREFIX + index;
                sources.put(id, new AnalysisEvidenceSource(
                        id,
                        text,
                        AnalysisEvidenceSource.Kind.STRUCTURED_EDUCATION
                ));
                index++;
            }
        }
        return sources;
    }

    private Resume loadReadyCanonicalChild(Resume parent, Long userId) {
        if (parent == null || parent.getCanonicalResumeId() == null) {
            return null;
        }
        Resume child = resumeMapper.findById(parent.getCanonicalResumeId());
        if (child == null || !userId.equals(child.getUserId())) {
            return null;
        }
        if (parent.getId() != null && !parent.getId().equals(child.getParentResumeId())) {
            return null;
        }
        if (!isTemplate(child) || !isDocx(child)) {
            return null;
        }
        if (child.getReviewStatus() == null
                || !ResumeReviewStatus.READY.name().equalsIgnoreCase(child.getReviewStatus())) {
            return null;
        }
        return child;
    }

    private List<ResumeDocumentNode> parseDocxIfPresent(Resume resume) {
        if (resume == null || !isDocx(resume) || isBlank(resume.getFilePath())) {
            return List.of();
        }
        Path path = Path.of(resume.getFilePath());
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            List<ResumeDocumentNode> parsed = resumeDocumentService.parseDocx(path);
            if (parsed == null || parsed.isEmpty()) {
                return List.of();
            }
            return parsed;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private List<ResumeDocumentNode> layoutNodes(Resume resume) {
        PdfLayout layout = readLayout(resume == null ? null : resume.getLayoutJson());
        if (layout == null || layout.getBlocks() == null || layout.getBlocks().isEmpty()) {
            return List.of();
        }
        List<ResumeDocumentNode> nodes = new ArrayList<>();
        for (ResumeTextBlock block : layout.getBlocks()) {
            if (block == null || isBlank(block.getId()) || isBlank(block.getText())) {
                continue;
            }
            nodes.add(new ResumeDocumentNode(block.getId(), "LAYOUT_BLOCK", block.getText().strip()));
        }
        return nodes;
    }

    static List<ResumeDocumentNode> linesAsNodes(String resumeText) {
        List<ResumeDocumentNode> nodes = new ArrayList<>();
        if (resumeText == null || resumeText.isBlank()) {
            return nodes;
        }
        int index = 0;
        for (String line : resumeText.lines().toList()) {
            if (line == null || line.isBlank()) {
                continue;
            }
            nodes.add(new ResumeDocumentNode("t-" + index++, "TEXT", line.strip()));
        }
        if (nodes.isEmpty()) {
            nodes.add(new ResumeDocumentNode("t-0", "TEXT", resumeText.strip()));
        }
        return nodes;
    }

    private ResumeStructure readStructure(String json) {
        if (isBlank(json)) {
            return null;
        }
        try {
            ResumeStructure structure = objectMapper.readValue(json, ResumeStructure.class);
            if (structure != null) {
                structure.normalize();
            }
            return structure;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private PdfLayout readLayout(String json) {
        if (isBlank(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PdfLayout.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private static boolean isPdf(Resume resume) {
        return resume.getFileType() != null && "pdf".equalsIgnoreCase(resume.getFileType());
    }

    private static boolean isDocx(Resume resume) {
        return resume.getFileType() != null && "docx".equalsIgnoreCase(resume.getFileType());
    }

    private static boolean isTemplate(Resume resume) {
        return resume.getOrigin() != null
                && ResumeOrigin.TEMPLATE.name().equalsIgnoreCase(resume.getOrigin());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
