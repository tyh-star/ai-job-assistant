package aijob.tailor.v3;

/**
 * Shared resume language guide for the V3 prompt and local quality scorer.
 * This is not a banned-word list; the unit of judgment is the whole entry.
 */
public final class V3LanguageGuide {

    public static final String TEXT = """
            Resume language:
            - Start bullets with a clear action verb.
            - Use past tense for completed work; present tense is allowed for ongoing responsibility.
            - Connect technologies to the actual work. Do not stack keywords.
            - Name a language and a framework with a natural conjunction:
              "using Java and Spring Boot" is better than "using Java Spring Boot".
            - "in Java Spring Boot" is an unnatural stack; do not glue tech names together.
            - Prefer "used UML class diagrams" over "utilizing UML Class Diagrams".
            - Prefer "deployed ... to AWS" over "onto AWS".
            - Prefer a direct verb ("developing") over a nominalization ("the development of").
            - One main contribution per bullet.
            - Avoid first person.
            - Avoid empty adverbs such as "successfully".
            - Avoid "performed the integration of" and "executed the deployment of".
            - Avoid empty claims such as enhanced efficiency, improved performance,
              structured architecture, or optimized workflows unless a real fact supports them.
            - Do not replace a simple accurate word with a rarer one just to sound advanced.
            - Do not add length for its own sake. A longer sentence is not a better sentence.
            - A synonym swap is not a substantive improvement.
            - Keep tense, sentence shape, and technical terms consistent across the entry.
            - Do not invent empty outcomes, efficiency, performance, architecture value, or metrics
              to sound professional.
            - "Deployed..." is better than "Executed the deployment...".
            - "Used..." is sometimes better than an unnatural "Leveraged...".
            - Sentence length should be readable. Do not enforce a rigid character cap.
            - ATS keywords should appear naturally inside the action and the task.
            - Keep the original action-object pairing. Do not attach a new verb to an
              existing object, and do not mix an action from one bullet with an object
              from another bullet.
            - If the original says "integrated RESTful APIs", do not upgrade it to
              "built and integrated RESTful APIs" unless built is already evidenced.
            - If the original only deployed, do not add maintained, even if the JD
              asks for maintain.
            - OPTIONAL context such as friend, professor, or TA may be kept or dropped.
              Do not replace friend with teammate or partner, and do not replace
              professor/TA with stakeholder or client.
            """;

    private V3LanguageGuide() {
    }
}
