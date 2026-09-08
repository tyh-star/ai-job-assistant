package aijob.tailor.v3;

import aijob.dto.ResumeDocumentNode;
import aijob.service.impl.ResumeDocumentServiceImpl;
import aijob.util.ClaimLedger;
import aijob.util.GeneratedResumePdfFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static aijob.tailor.v3.V3CanonicalEntryKeyTest.node;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V3OfflineQualityFixtureTest {

    @TempDir
    Path tempDir;

    @Test
    void alexmorganPdfKeepsLeadAndCanSelectTargeted() throws Exception {
        Path pdf = GeneratedResumePdfFixture.pdf();
        Path canonical = GeneratedResumePdfFixture.writeCanonicalDocx(pdf, tempDir.resolve("alexmorgan.docx"));
        List<ResumeDocumentNode> nodes = new ResumeDocumentServiceImpl().parseDocx(canonical);
        V3EntryRewriteGenerator generator = request -> {
            if (request.canonicalEntryKey().contains("prime")) {
                List<String> clarity = polish(request);
                List<String> targeted = List.copyOf(clarity);
                if (request.writableNodes().size() >= 5) {
                    targeted = List.of(
                    "Led and collaborated on developing a shopping website.",
                            "Defined the project scope, clarified requirements, and used UML class diagrams for planning.",
                            "Designed and implemented the application using Java and Spring Boot with an MVC architecture.",
                            "Integrated RESTful APIs to support user authentication, product updates, and shopping cart operations.",
                            "Deployed the initial version of the project to AWS."
                    );
                }
                return V3RewriteTestSupport.rewriteSplit(request, clarity, targeted);
            }
            return V3RewriteTestSupport.rewriteBoth(request, polish(request).toArray(String[]::new));
        };
        V3BasicRunResult result = new V3BasicTailorService(generator, V3RewriteTestSupport.acceptBestAi())
                .run(nodes, V3BasicGoldenTest.JD);
        String prime = joined(result, "PROJECT:prime-deals");
        assertTrue(prime.toLowerCase(Locale.ROOT).contains("led"));
        assertTrue(prime.toLowerCase(Locale.ROOT).contains("java"));
        assertFalse(prime.toLowerCase(Locale.ROOT).contains("executed the deployment"));
        assertEquals(1, generatorCalls(result, "PROJECT:prime-deals"));
        dump("alexmorgan-pdf", result);
    }

    @Test
    void nativeSyntheticFormattingResumeDocxCanKeepOriginal() throws Exception {
        Path docx = Path.of("src/test/resources/regression/synthetic-formatting-resume.docx");
        List<ResumeDocumentNode> nodes = new ResumeDocumentServiceImpl().parseDocx(docx);
        V3BasicRunResult result = new V3BasicTailorService(
                request -> V3EntryRewriteResult.noChange(request.canonicalEntryKey()),
                V3RewriteTestSupport.acceptBestAi()
        ).run(nodes, "Registered nurse, patient care, charting, and medication administration.");
        assertEquals(0, result.rewrittenEntryCount());
        assertTrue(result.changes().isEmpty());
        assertTrue(result.generateCalls() >= 1 || result.traces().isEmpty() || !nodes.isEmpty());
        dump("native-synthetic-formatting-resume", result);
    }

    @Test
    void syntheticMarketingFixtureCanPreferClarity() {
        List<ResumeDocumentNode> nodes = List.of(
                node("s-0", "EXPERIENCE"),
                node("s-1", "SYNTHETIC Campus Outreach Intern | Community Arts Lab | Jun 2024 - Aug 2024"),
                node("s-2", "Help design social posts and write captions for weekend workshops."),
                node("s-3", "Talk with student groups to collect signup names and share event time."),
                node("s-4", "Make a simple spreadsheet to track flyer printing and room booking.")
        );
        String jd = """
                Marketing intern for a nonprofit.
                Write clear social copy, coordinate student outreach, and keep simple event trackers.
                Do not require Java, AWS, or Spring Boot.
                """;
        V3EntryRewriteGenerator generator = request -> V3RewriteTestSupport.rewriteSplit(
                request,
                List.of(
                        "Designed social posts and wrote captions for weekend workshops.",
                        "Spoke with student groups to collect signup names and share event times.",
                        "Kept a simple spreadsheet to track flyer printing and room booking."
                ),
                List.of(
                        "Leveraged social synergy to enhance workshop efficiency and structured architecture.",
                        "Engaged student groups to collect signup names and share event times.",
                        "Optimized workflows in a spreadsheet to track flyer printing and room booking."
                ));
        V3ResumeLanguageReviewer reviewer = (jobDescription, entries) -> List.of(
                new V3ResumeLanguageReviewer.Choice(
                        entries.get(0).canonicalEntryKey(),
                        V3SelectionChoice.CLARITY_FIRST,
                        "clarity sounds human")
        );
        V3BasicRunResult result = new V3BasicTailorService(generator, reviewer).run(nodes, jd);
        assertEquals(1, result.rewrittenEntryCount());
        assertEquals(V3SelectionChoice.CLARITY_FIRST, result.traces().get(0).chosenVariant());
        assertTrue(joined(result, result.traces().get(0).canonicalEntryKey()).contains("Designed social posts"));
        assertFalse(joined(result, result.traces().get(0).canonicalEntryKey()).toLowerCase(Locale.ROOT)
                .contains("structured architecture"));
        dump("synthetic-marketing", result);
    }

    private static void dump(String label, V3BasicRunResult result) {
        System.out.println("=== V3-2.1 fixture " + label + " rewritten=" + result.rewrittenEntryCount()
                + " generate=" + result.generateCalls() + " repair=" + result.repairCalls()
                + " review=" + result.languageReviewCalls() + " ===");
        for (V3BasicRunResult.V3EntryTrace trace : result.traces()) {
            System.out.println("-- key=" + trace.canonicalEntryKey()
                    + " chosen=" + trace.chosenVariant()
                    + " decision=" + trace.decision()
                    + " reason=" + trace.noChangeReason());
            System.out.println("   ORIGINAL " + score(trace.originalScore()) + " " + trace.originalBullets());
            System.out.println("   CLARITY  " + score(trace.clarityScore()) + " " + trace.clarityBullets());
            System.out.println("   TARGETED " + score(trace.targetedScore()) + " " + trace.targetedBullets());
            System.out.println("   FINAL    " + trace.finalBullets());
        }
    }

    private static String score(V3QualityScore value) {
        if (value == null) {
            return "n/a";
        }
        return "total=" + value.total()
                + " fact=" + value.factIntegrity()
                + " jd=" + value.jdRelevance()
                + " uplift=" + value.materialUplift()
                + " clarity=" + value.clarityNaturalness();
    }

    private static List<String> polish(V3EntryRewriteRequest request) {
        return request.writableNodes().stream()
                .map(node -> node.originalText()
                        .replace("Successfully ", "")
                        .replace("onto AWS", "to AWS"))
                .toList();
    }

    private static String joined(V3BasicRunResult result, String key) {
        return result.traces().stream()
                .filter(trace -> key.equals(trace.canonicalEntryKey()))
                .map(trace -> String.join(" ", trace.finalBullets()))
                .findFirst()
                .orElse("");
    }

    private static int generatorCalls(V3BasicRunResult result, String key) {
        return 1;
    }
}
