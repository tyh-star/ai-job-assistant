package aijob.util;

import aijob.dto.RejectedTailorChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EvidenceRelationValidatorTest {

    private static final String P17 =
            "Integrated RESTful APIs to handle user authentication, product updates and shopping cart managements";
    private static final String P15 =
            "Define project scope, clarify requirements and utilized UML Class Diagram for planning";
    private static final String P18 =
            "Successfully deployed the initial version of the application to AWS";
    private static final String REJECTED =
            "Integrated RESTful APIs for managing user authentication and product updates, "
                    + "using UML Class Diagram for planning cart functionalities.";
    private static final String ALLOWED_REST =
            "Integrated RESTful APIs for user authentication, product updates, "
                    + "and shopping cart operations.";
    private static final String PARALLEL_AWS =
            "Integrated RESTful APIs to handle user authentication, product updates and shopping cart management, "
                    + "and deployed the initial version to AWS.";

    @Test
    void umlCartNewRelationIsNotSupported() {
        assertEquals(
                RejectedTailorChange.RELATION_NOT_SUPPORTED,
                EvidenceRelationValidator.rejectReason(REJECTED, P17, List.of(P17, P15)));
    }

    @Test
    void restAuthProductCartRewritePasses() {
        assertNull(EvidenceRelationValidator.rejectReason(ALLOWED_REST, P17, List.of(P17, P15)));
    }

    @Test
    void sameNodeHandleToManagingIsAllowed() {
        assertNull(EvidenceRelationValidator.rejectReason(
                "Integrated RESTful APIs for managing user authentication, product updates, and shopping cart operations.",
                P17,
                List.of(P17, P15)));
    }

    @Test
    void sameEntryDoesNotAutomaticallyEstablishARelation() {
        assertEquals(
                RejectedTailorChange.RELATION_NOT_SUPPORTED,
                EvidenceRelationValidator.rejectReason(REJECTED, P17, List.of(P17, P15, P18)));
    }

    @Test
    void independentParallelAndClauseIsAllowed() {
        assertNull(EvidenceRelationValidator.rejectReason(PARALLEL_AWS, P17, List.of(P17, P15, P18)));
    }
}
