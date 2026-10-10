package aijob.mapper;

import aijob.AiJobApplication;
import aijob.entity.TailorProposal;
import aijob.rewrite.ProResumeRewriteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The real updateRewriteSession SQL against H2: status-guarded, owner-only, PRO_REWRITE-only. */
@SpringBootTest(
        classes = AiJobApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:rewritesession;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=test-only-h2-password",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "jwt.secret=test-only-jwt-secret-key-must-be-32b!!",
                "openai.api-key=test-openai-key-not-real",
                "aijob.storage.root=target/aijob-test-uploads"
        }
)
@Sql(scripts = "classpath:schema/tailor-proposals-h2.sql")
class TailorProposalMapperRewriteSessionTest {

    @Autowired
    private TailorProposalMapper mapper;

    private TailorProposal insert(String mode, String status) {
        TailorProposal row = new TailorProposal();
        row.setUserId(7L);
        row.setResumeId(12L);
        row.setJobId(30L);
        row.setMode(mode);
        row.setStatus(status);
        row.setExpiresAt(LocalDateTime.now().plusHours(1));
        row.setChangesJson("{}");
        row.setRejectedJson("[]");
        mapper.insert(row);
        return row;
    }

    @Test
    void movesOnlyFromTheExpectedStatusForTheOwner() {
        TailorProposal row = insert(ProResumeRewriteService.MODE, "ANALYZED");

        assertEquals(0, mapper.updateRewriteSession(row.getId(), 99L, "ANALYZED", "ANSWERED", "{\"a\":1}", null, null),
                "another user's session is never updated");
        assertEquals(1, mapper.updateRewriteSession(row.getId(), 7L, "ANALYZED", "ANSWERED", "{\"a\":1}", null, null));
        assertEquals(0, mapper.updateRewriteSession(row.getId(), 7L, "ANALYZED", "ANSWERED", "{\"a\":2}", null, null),
                "a second request from the old status loses");
        assertEquals(1, mapper.updateRewriteSession(row.getId(), 7L, "ANSWERED", "COMPLETED", "{\"a\":3}", "f.docx", "/x/f.docx"));

        TailorProposal stored = mapper.findById(row.getId());
        assertEquals("COMPLETED", stored.getStatus());
        assertEquals("{\"a\":3}", stored.getChangesJson());
        assertEquals("f.docx", stored.getGeneratedFileName());
    }

    @Test
    void neverTouchesOrdinaryTailorProposals() {
        TailorProposal legacy = insert("AGGRESSIVE", "PENDING");
        assertEquals(0, mapper.updateRewriteSession(legacy.getId(), 7L, "PENDING", "COMPLETED", "{}", null, null));
        assertEquals("PENDING", mapper.findById(legacy.getId()).getStatus());
    }
}
