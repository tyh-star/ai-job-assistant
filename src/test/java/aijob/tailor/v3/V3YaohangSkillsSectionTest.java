package aijob.tailor.v3;

import aijob.dto.ResumeDocumentNode;
import aijob.service.impl.ResumeDocumentServiceImpl;
import aijob.util.ResumeTailorNodeGuard;
import aijob.util.YaohangCanonicalDocxFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3YaohangSkillsSectionTest {

    @TempDir
    Path tempDir;

    @Test
    void canonicalDocxSkillsAreExistingWritableParagraphsNotANewSection() throws Exception {
        Path canonical = YaohangCanonicalDocxFixture.writeCanonicalDocx(
                YaohangCanonicalDocxFixture.pdf(), tempDir.resolve("child.docx"));
        List<ResumeDocumentNode> nodes = new ResumeDocumentServiceImpl().parseDocx(canonical);
        Set<String> allowed = ResumeTailorNodeGuard.allowedNodeIds(nodes);

        int headingIndex = -1;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).getText() != null
                    && nodes.get(i).getText().toUpperCase().contains("TECHNICAL SKILLS")) {
                headingIndex = i;
                break;
            }
        }
        assertTrue(headingIndex >= 0, "TECHNICAL SKILLS heading missing");
        ResumeDocumentNode heading = nodes.get(headingIndex);
        assertFalse(allowed.contains(heading.getNodeId()), heading.getNodeId());

        List<ResumeDocumentNode> skillBodies = new ArrayList<>();
        for (int i = headingIndex + 1; i < nodes.size(); i++) {
            String text = nodes.get(i).getText() == null ? "" : nodes.get(i).getText().strip();
            if (text.equalsIgnoreCase("PROJECTS")
                    || text.equalsIgnoreCase("RELEVANT PROJECTS")
                    || text.equalsIgnoreCase("EXPERIENCE")
                    || text.equalsIgnoreCase("EDUCATION")
                    || text.equalsIgnoreCase("SUMMARY")) {
                break;
            }
            if (!text.isBlank()) {
                skillBodies.add(nodes.get(i));
            }
        }
        assertFalse(skillBodies.isEmpty(), "Skills body paragraphs missing");
        for (ResumeDocumentNode body : skillBodies) {
            assertTrue(allowed.contains(body.getNodeId()), body.getNodeId() + " " + body.getText());
            assertTrue(body.getNodeId().startsWith("p-"), body.getNodeId());
            assertTrue(body.getText().contains(",") || body.getText().contains(":"), body.getText());
        }
        assertEquals("p-10", heading.getNodeId());
        assertEquals("TECHNICAL SKILLS", heading.getText());
        assertEquals(1, skillBodies.size());
        assertEquals("p-11", skillBodies.get(0).getNodeId());
        assertEquals(
                "Java, Python, C/C++, SQL, HTML, Vue, Javascript, CSS, MySQL",
                skillBodies.get(0).getText());
    }
}
