package aijob.tailor.v3;

import aijob.dto.RejectedTailorChange;
import aijob.dto.ResumeDocumentNode;
import aijob.util.ClaimLedger;
import aijob.util.SourceFactDuplicationGate;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TailorV2PathFreezeTest {

    @Test
    void jobServiceStillCallsRunConservativeTailorWithoutRouter() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/aijob/service/impl/JobServiceImpl.java"),
                StandardCharsets.UTF_8);
        assertTrue(source.contains("runConservativeTailor("));
        assertTrue(source.contains("useV3()"));
        assertFalse(source.contains("V3EntryRewriteValidator"));
    }

    @Test
    void sourceFactDuplicationGateStillRejectsSameEntryCopy() {
        List<ResumeDocumentNode> nodes = List.of(
                node("p-0", "RELEVANT PROJECTS"),
                node("p-1", "Online Store | Jan 2024"),
                node("p-2", "Design and implement the application using Java Spring Boot with an MVC pattern."),
                node("p-3", "Integrated RESTful APIs to handle user authentication.")
        );
        ClaimLedger ledger = ClaimLedger.from(nodes);
        String reason = SourceFactDuplicationGate.rejectReason(
                "p-3",
                nodes.get(3).getText(),
                "Integrated RESTful APIs and Java Spring Boot to handle user authentication.",
                ledger
        );
        assertEquals(RejectedTailorChange.SOURCE_FACT_WOULD_DUPLICATE, reason);
    }

    private static ResumeDocumentNode node(String id, String text) {
        return new ResumeDocumentNode(id, "PARAGRAPH", text);
    }
}
