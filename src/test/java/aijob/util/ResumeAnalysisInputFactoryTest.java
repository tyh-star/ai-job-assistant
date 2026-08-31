package aijob.util;

import aijob.dto.PdfLayout;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.ResumeStructure;
import aijob.dto.ResumeTextBlock;
import aijob.entity.Resume;
import aijob.mapper.ResumeMapper;
import aijob.service.ResumeDocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeAnalysisInputFactoryTest {

    @Mock
    private ResumeMapper resumeMapper;
    @Mock
    private ResumeDocumentService resumeDocumentService;

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ResumeAnalysisInputFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ResumeAnalysisInputFactory(resumeMapper, resumeDocumentService, objectMapper);
    }

    @Test
    void pdfParentWithReadyChild_usesChildDocxNodesAndParentStructure() throws Exception {
        Resume parent = pdfParent(1L);
        parent.setCanonicalResumeId(2L);
        Resume child = templateChild(2L, 1L);
        when(resumeMapper.findById(2L)).thenReturn(child);
        when(resumeDocumentService.parseDocx(any())).thenReturn(List.of(
                new ResumeDocumentNode("p-4", "PARAGRAPH", "EDUCATION"),
                new ResumeDocumentNode("p-5", "PARAGRAPH", "Bachelor of Computer Science")
        ));

        ResumeAnalysisInput input = factory.resolve(parent, 7L);

        assertEquals(parent, input.selectedResume());
        assertEquals(parent, input.uploadRootResume());
        assertEquals(child, input.canonicalChildResume());
        assertEquals(1L, input.evidenceRootId());
        assertEquals("p-4", input.matchingNodes().get(0).getNodeId());
        assertEquals("York University", input.structuredResume().getEducation().get(0).getSchool());
        assertTrue(input.evidenceSourcesById().containsKey("p-5"));
        assertEquals(
                AnalysisEvidenceSource.Kind.LAYOUT_BLOCK,
                input.evidenceSourcesById().get("b-p1-0").kind()
        );
        assertTrue(input.evidenceSourcesById().containsKey("edu-structure-0"));
        assertEquals(
                AnalysisEvidenceSource.Kind.STRUCTURED_EDUCATION,
                input.evidenceSourcesById().get("edu-structure-0").kind()
        );
        assertFalse(input.evidenceSourcesById().containsKey("b-p-not-real"));
    }

    @Test
    void pdfParentWithoutChild_usesLayoutBlockIds() throws Exception {
        Resume parent = pdfParent(1L);
        parent.setCanonicalResumeId(null);

        ResumeAnalysisInput input = factory.resolve(parent, 7L);

        assertNull(input.canonicalChildResume());
        assertTrue(input.matchingNodes().stream().allMatch(node -> node.getNodeId().startsWith("b-p")));
        assertEquals("b-p1-0", input.matchingNodes().get(0).getNodeId());
        assertEquals(
                AnalysisEvidenceSource.Kind.MATCHING_NODE,
                input.evidenceSourcesById().get("b-p1-0").kind()
        );
        assertTrue(input.evidenceSourcesById().containsKey("edu-structure-0"));
    }

    @Test
    void pdfParentWithoutLayout_fallsBackToExtractedTextNodes() throws Exception {
        Resume parent = pdfParent(1L);
        parent.setCanonicalResumeId(null);
        parent.setLayoutJson(null);
        parent.setExtractedText("Yaohang Tang\nBachelor of Computer Science");

        ResumeAnalysisInput input = factory.resolve(parent, 7L);

        assertEquals("t-0", input.matchingNodes().get(0).getNodeId());
        assertEquals("Yaohang Tang", input.matchingNodes().get(0).getText());
        assertTrue(input.evidenceSourcesById().containsKey("edu-structure-0"));
        assertFalse(input.evidenceSourcesById().keySet().stream().anyMatch(id -> id.startsWith("b-p")));
    }

    @Test
    void templateChild_readsParentStructureAndChildDocx() throws Exception {
        Resume parent = pdfParent(1L);
        Resume child = templateChild(2L, 1L);
        child.setStructuredJson(null);
        when(resumeMapper.findById(1L)).thenReturn(parent);
        when(resumeDocumentService.parseDocx(any())).thenReturn(List.of(
                new ResumeDocumentNode("p-8", "PARAGRAPH", "Java Spring Boot")
        ));

        ResumeAnalysisInput input = factory.resolve(child, 7L);

        assertEquals(child, input.selectedResume());
        assertEquals(parent, input.uploadRootResume());
        assertEquals(1L, input.evidenceRootId());
        assertEquals("p-8", input.matchingNodes().get(0).getNodeId());
        assertEquals("Bachelor of Computer Science", input.structuredResume().getEducation().get(0).getDegree());
        assertTrue(input.evidenceSourcesById().containsKey("edu-structure-0"));
        assertTrue(input.evidenceSourcesById().containsKey("b-p1-0"));
    }

    @Test
    void nativeDocx_usesCurrentFileAndAllowsEmptyStructure() throws Exception {
        Path file = tempDir.resolve("native.docx");
        Files.writeString(file, "placeholder");
        Resume resume = new Resume();
        resume.setId(9L);
        resume.setUserId(7L);
        resume.setFileType("docx");
        resume.setOrigin("UPLOAD");
        resume.setFilePath(file.toString());
        resume.setExtractedText("EDUCATION\nBachelor of Computer Science");
        when(resumeDocumentService.parseDocx(any())).thenReturn(List.of(
                new ResumeDocumentNode("p-0", "PARAGRAPH", "EDUCATION"),
                new ResumeDocumentNode("p-1", "PARAGRAPH", "Bachelor of Computer Science")
        ));

        ResumeAnalysisInput input = factory.resolve(resume, 7L);

        assertEquals(resume, input.uploadRootResume());
        assertNull(input.canonicalChildResume());
        assertNull(input.structuredResume());
        assertEquals(9L, input.evidenceRootId());
        assertEquals("p-1", input.matchingNodes().get(1).getNodeId());
        assertEquals(Map.of(
                "p-0", input.evidenceSourcesById().get("p-0"),
                "p-1", input.evidenceSourcesById().get("p-1")
        ).size(), input.evidenceSourcesById().size());
    }

    private Resume pdfParent(long id) throws Exception {
        Resume resume = new Resume();
        resume.setId(id);
        resume.setUserId(7L);
        resume.setFileType("pdf");
        resume.setOrigin("UPLOAD");
        resume.setExtractedText("Yaohang Tang\nBachelor of Computer Science");
        resume.setLayoutJson(objectMapper.writeValueAsString(sampleLayout()));
        ResumeStructure structure = ResumeStructure.empty();
        ResumeStructure.ResumeEducation education = new ResumeStructure.ResumeEducation();
        education.setSchool("York University");
        education.setDegree("Bachelor of Computer Science");
        education.setDates("Sept 2023 – Present");
        education.setSourceBlockIds(List.of("b-p1-0"));
        structure.getEducation().add(education);
        resume.setStructuredJson(objectMapper.writeValueAsString(structure));
        return resume;
    }

    private Resume templateChild(long id, long parentId) throws Exception {
        Path file = tempDir.resolve("child-" + id + ".docx");
        Files.writeString(file, "placeholder");
        Resume child = new Resume();
        child.setId(id);
        child.setUserId(7L);
        child.setFileType("docx");
        child.setOrigin("TEMPLATE");
        child.setParentResumeId(parentId);
        child.setReviewStatus("READY");
        child.setFilePath(file.toString());
        child.setExtractedText("canonical child");
        return child;
    }

    private static PdfLayout sampleLayout() {
        ResumeTextBlock block = new ResumeTextBlock();
        block.setId("b-p1-0");
        block.setPage(1);
        block.setText("York University Bachelor of Computer Science");
        block.normalize();
        return PdfLayout.ofBlocks(612, 792, List.of(block));
    }
}
