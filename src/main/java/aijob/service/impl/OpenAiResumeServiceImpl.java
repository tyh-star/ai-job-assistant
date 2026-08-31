package aijob.service.impl;

import aijob.dto.ConservativeCandidateRanking;
import aijob.dto.ConservativeRewriteCandidate;
import aijob.dto.ConservativeRewriteCandidatesResponse;
import aijob.dto.ConservativeRewritePlan;
import aijob.dto.ConservativeRewriteRepair;
import aijob.dto.JobMatchResponse;
import aijob.dto.ResumeAnalysisResponse;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.dto.TailorChangesResponse;
import aijob.dto.RejectedTailorChange;
import aijob.enums.TailorMode;
import aijob.exception.AiServiceException;
import aijob.service.AiResumeService;
import com.openai.client.OpenAIClient;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.StructuredResponseCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class OpenAiResumeServiceImpl implements AiResumeService {

    private static final Logger log =
            LoggerFactory.getLogger(OpenAiResumeServiceImpl.class);

    private static final String ANALYZE_INSTRUCTIONS = """
            You are a professional technical recruiter and resume reviewer.

            Analyze the resume for a software engineering / technical job search.
            There is NO job description and NO specific target role.
            Do not invent requirements for a particular job title, company, or posting.

            Score and review only what is in the resume:
            - overallScore: integer from 0 to 100
            - summary: concise overview of the candidate's profile and resume quality
            - strengths: evidence-based strengths
            - weaknesses: evidence-based weaknesses or gaps
            - missingSkills: technical skills that are clearly missing or underrepresented
              for a typical software engineering / technical job-seeker resume
            - suggestions: specific, actionable improvements

            Be concrete. Do not fabricate experience, employers, or skills.
            """;

    private static final String MATCH_INSTRUCTIONS = """
            You are a technical recruiter and ATS resume reviewer.

            Compare the candidate's RESUME against this specific JOB DESCRIPTION.
            Score only the fit between these two documents.

            Rules:
            - matchScore: integer 0-100 for how well this resume matches this job description.
            - matchedSkills: only skills the job description requires AND the resume
              has evidence for. Do not list skills that appear only in the resume.
            - missingSkills: only skills the job description clearly requires or
              strongly emphasizes, but the resume has no supporting evidence.
              Do not add generic software-engineering skills that are not in this JD.
            - strengths: advantages relevant to this specific role.
            - gaps: gaps between this resume and this specific job.
            - recommendations: how the user should tailor the resume to this JD.

            Never invent experience, skills, education, certificates, or achievements
            to raise the match score.

            If the JD requires Docker and the resume has no Docker evidence:
            - include "Docker" in missingSkills
            - do NOT claim the candidate knows Docker
            - recommendations may say: "If you have Docker experience, add it to the resume."
            - recommendations must NOT say: "Add Docker experience to the resume."
              unless the resume already shows Docker evidence.

            The same rule applies to every skill, tool, degree, and achievement.
            """;

    private static final String TAILOR_SHARED_RULES = """
            You are a technical recruiter rewriting selected resume paragraphs
            for one specific job description.

            You do NOT generate a full resume.
            Return only the nodes that should change.

            Copy originalText exactly from the provided node text.
            Keep nodeId unchanged.

            Length: rewritten bullets should stay close to the original.
            Target at most originalText length * 1.25.
            Stay concise. Prefer: concrete action + technology + existing result.

            Never write empty filler such as:
            "showcasing ability to..."
            "demonstrating expertise in..."
            "highlighting strong capability..."

            Never invent:
            companies, job titles, degrees, schools, certifications,
            employment dates, years of experience, team size, revenue,
            user counts, performance percentages, awards, security clearance,
            or work authorization.

            Prefer editing bullets under Summary, Technical Proficiencies,
            Internship/Experience, and Projects.
            Do not rewrite name, contact, headings, company/date lines,
            project titles/dates, URLs, or Education.
            """;

    private static final String TAILOR_CONSERVATIVE_RULES = """
            MODE: CONSERVATIVE

            Use only evidence already present in the resume nodes.

            You MAY:
            - improve wording and action verbs
            - do ATS keyword optimization using skills already in the resume
            - recombine facts from the same job or project
            - emphasize skills the resume already has that the JD also wants
            - delete weak, duplicate, or vague phrasing

            You MUST NOT add skills, duties, or metrics that are not evidenced.

            Every VERIFIED change MUST include:
            evidenceType = VERIFIED
            inferredSkill = empty
            requiresConfirmation = false
            evidenceNodeIds = one or more node ids whose current text supports the rewrite
            evidenceQuote = a short quote copied from those nodes' original text

            evidenceQuote must appear in the referenced nodes.
            Do not cite a node that does not contain the quote.

            You MUST NOT add skills, architecture, or outcome claims that are
            not already written in the resume nodes. Do not add:
            high availability, scalability, resilient infrastructure,
            Docker, or Kubernetes unless that exact capability already
            appears in the resume node text.

            You MAY tighten grammar and wording of facts that are already present.
            """;

    private static final String TAILOR_CONSTRAINED_RULES = """
            MODE: CONSERVATIVE CONSTRAINED REWRITE

            Rewrite ONE resume node only.
            Return exactly three candidates with clearly different wording and the same facts.
            If a fact-safe rewrite is impossible, return fewer rather than inventing facts.
            Do not fill evidenceType, evidenceNodeIds, evidenceQuote, or applied.
            The server overwrites those fields.

            Constraints you MUST obey:
            - Use EVIDENCE_PACKET_JSON as the only allowed fact source besides originalText
            - Every candidate MUST keep every requiredFact (except role:*, which may change tense, not seniority)
            - supportingFacts are optional and may be used only from supportingNodeIds
            - usedFactNodeIds must list every fact node you actually used
            - nodeId must equal targetNodeId
            - usedSupportingNodeIds must be a subset of supportingNodeIds
            - improvementIntent is a hint only; the server reclassifies it
            - claimedFacts lists facts you believe newText contains; the server re-checks them
            - Do not use facts from any other project or job
            - Do not use Skills or Summary as proof that a project used a tool
            - Do not add tools, skills, scale, metrics, duties, or outcomes
              that are not already written in originalText or the evidence packet
            - Do not add enhanced/improved/increased/reduced/optimized/efficiency/performance/scalable/reliable
              unless that exact outcome is already written in originalText or supporting facts
            - "optimal components" is not an outcome metric and must not become "optimized for performance"
            - Do not add purpose, result, or condition phrases such as
              "based on user/business needs", "to meet business needs", or
              "ensuring improved outcomes" unless that wording already appears in
              originalText or supporting facts
            - manage user authentication / shopping cart / product data is not leadership
            - Obey forbiddenClaims, including unfounded leadership and education-status upgrades
            - Each newText MUST be <= maxCharacters
            - Do not invent companies, titles, degrees, dates, or numbers
            - Do not make a synonym-only, plurality-only, or word-order-only change
            - Do not replace designed/implemented with a vague verb such as created
            - Do not write in job-description voice
            - Do not only insert job keywords
            - Do not pack the whole project into one bullet unless newText keeps every
              material fact of each supporting bullet you are replacing
              (action, technologies, objects/tasks, constraints such as budget,
              and user/value context such as helping users select)
            - List a supporting node in usedSupportingNodeIds only if newText uses a
              concrete fact from that node. Generic words such as project, application,
              or system do not count as a supporting hit
            - A partial borrow of another bullet must not be treated as replacing it
            - Prefer finite verbs. Write "and deployed ... to AWS", not
              ", successfully deploying ...". Do not add empty adverbs such as
              successfully unless they already appear in originalText
            - When supportingNodeIds is not empty, return three structurally different candidates:
              (A) SAME_ENTRY_EVIDENCE_ENRICHMENT: newText MUST use at least one supporting fact
                  and usedSupportingNodeIds MUST list that node; for a Spring Boot/MVC target,
                  try the REST/auth/product/cart supporting node when it is in the packet
              (B) CLARITY_IMPROVEMENT: clearer professional wording of originalText only
              (C) GRAMMAR_FIX or a shorter concise rewrite of originalText only
              Do not return three synonym variants of the same sentence.
            - Return up to three candidates. If a fact-safe rewrite is impossible, return fewer.
              Never invent facts or relax these rules to fill a quota of three.

            Follow rewriteGoal and rewriteIntent for this node.
            Keep every fact from originalText.
            """;

    private static final String TAILOR_AGGRESSIVE_RULES = """
            MODE: AGGRESSIVE

            Maximize match to this JD.

            VERIFIED changes:
            Rewrite using evidence already in the resume.
            evidenceType = VERIFIED
            inferredSkill = empty
            requiresConfirmation = false
            evidenceNodeIds = node ids that support the rewrite
            evidenceQuote = a short quote copied from those nodes' original text
            Do not add high availability, scalability, resilient infrastructure,
            Docker, or Kubernetes unless those words already appear in the resume.

            INFERRED changes:
            You MAY propose plausible adjacent skills that are highly related
            to the candidate's existing stack, even if they are not written
            in the resume. Example: Java + Spring Boot + AWS + Git may make
            Docker or CI/CD a reasonable adjacent claim.

            For INFERRED:
            evidenceType = INFERRED
            inferredSkill = the unconfirmed skill
            requiresConfirmation = true
            Put the inferred skill in newText only as a candidate claim.

            Do not silently treat inferred skills as verified facts.
            Still never invent employers, titles, degrees, dates, or metrics.
            AGGRESSIVE may be slightly longer than CONSERVATIVE, but stay tight.
            """;

    private final OpenAIClient openAIClient;
    private final String model;

    public OpenAiResumeServiceImpl(
            OpenAIClient openAIClient,
            @Value("${openai.model}") String model) {
        this.openAIClient = openAIClient;
        this.model = model;
    }

    @Override
    public ResumeAnalysisResponse analyzeResume(String resumeText) {
        return requestStructuredOutput(
                ANALYZE_INSTRUCTIONS,
                resumeText,
                ResumeAnalysisResponse.class,
                "resume analysis"
        );
    }

    @Override
    public JobMatchResponse matchResumeToJob(
            String resumeText,
            String jobDescription) {

        String input = """
                RESUME:
                %s

                JOB DESCRIPTION:
                %s
                """.formatted(resumeText, jobDescription);

        return requestStructuredOutput(
                MATCH_INSTRUCTIONS,
                input,
                JobMatchResponse.class,
                "job match"
        );
    }

    @Override
    public List<TailorChange> generateTailorChanges(
            List<ResumeDocumentNode> nodes,
            String jobDescription,
            TailorMode mode) {

        TailorMode resolved = mode == null ? TailorMode.CONSERVATIVE : mode;

        StringBuilder nodeBlock = new StringBuilder();
        for (ResumeDocumentNode node : nodes) {
            nodeBlock.append('[')
                    .append(node.getNodeId())
                    .append("] ")
                    .append(node.getText())
                    .append('\n');
        }

        String input = """
                RESUME DOCUMENT NODES:
                %s

                JOB DESCRIPTION:
                %s
                """.formatted(nodeBlock, jobDescription);

        String instructions = TAILOR_SHARED_RULES
                + System.lineSeparator()
                + (resolved == TailorMode.AGGRESSIVE
                ? TAILOR_AGGRESSIVE_RULES
                : TAILOR_CONSERVATIVE_RULES);

        TailorChangesResponse response = requestStructuredOutput(
                instructions,
                input,
                TailorChangesResponse.class,
                "resume tailor"
        );

        if (response == null || response.changes == null) {
            return new ArrayList<>();
        }

        return response.changes;
    }

    @Override
    public ConservativeRewriteCandidatesResponse generateConstrainedTailorCandidates(
            ResumeDocumentNode node,
            ConservativeRewritePlan plan,
            String jobDescription,
            ConservativeRewriteRepair repair) {

        if (node == null || plan == null) {
            return emptyCandidates(node, plan);
        }

        ConservativeRewriteCandidatesResponse response = requestStructuredOutput(
                buildConstrainedInstructions(plan, repair),
                buildConstrainedInput(node, plan, jobDescription),
                ConservativeRewriteCandidatesResponse.class,
                "resume tailor constrained candidates"
        );
        if (response == null || response.getCandidates() == null || response.getCandidates().isEmpty()) {
            return emptyCandidates(node, plan);
        }
        response.setNodeId(plan.nodeId());
        return response;
    }

    @Override
    public ConservativeCandidateRanking rankConservativeCandidates(
            String originalText,
            String rewriteGoal,
            List<String> relevantJobTerms,
            List<ConservativeRewriteCandidate> candidates) {

        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        try {
            StringBuilder input = new StringBuilder();
            input.append("originalText: ").append(originalText == null ? "" : originalText).append('\n');
            input.append("rewriteGoal: ").append(rewriteGoal == null ? "" : rewriteGoal).append('\n');
            input.append("relevantJobTerms: ")
                    .append(relevantJobTerms == null ? "" : String.join(", ", relevantJobTerms))
                    .append('\n');
            int index = 0;
            for (ConservativeRewriteCandidate candidate : candidates) {
                input.append("candidate[").append(index).append("]: ")
                        .append(candidate == null ? "" : candidate.getNewText())
                        .append('\n');
                index++;
            }
            ConservativeCandidateRanking ranking = requestStructuredOutput(
                    """
                            Rank resume rewrite candidates. Do not write new text.
                            Score only the given candidates.
                            Check grammaticality, natural resume English, awkward wording,
                            semantic precision, and whether the candidate is actually better than original.
                            Reject or score below 60 for awkward phrases such as
                            "utilizing Java Spring Boot with an MVC architecture",
                            "updates to products", "shopping cart processes",
                            or "suggest dynamic component selections".
                            factualPreservationScore below 60 means the candidate must not be selected.
                            selectedIndex must point to a candidate that preserves every original fact.
                            Do not invent replacement text and do not restore previously rejected rewrites.
                            """,
                    input.toString(),
                    ConservativeCandidateRanking.class,
                    "resume tailor candidate ranking"
            );
            if (ranking == null
                    || ranking.getSelectedIndex() == null
                    || ranking.getSelectedIndex() < 0
                    || ranking.getSelectedIndex() >= candidates.size()) {
                return null;
            }
            return ranking;
        } catch (RuntimeException ex) {
            log.warn("Conservative ranking judge failed: {}", ex.getClass().getSimpleName());
            return null;
        }
    }

    static String buildConstrainedInstructions(
            ConservativeRewritePlan plan,
            ConservativeRewriteRepair repair) {

        StringBuilder instructions = new StringBuilder();
        instructions.append(TAILOR_SHARED_RULES)
                .append(System.lineSeparator())
                .append(TAILOR_CONSTRAINED_RULES)
                .append(System.lineSeparator())
                .append("nodeId=").append(plan.nodeId()).append('\n')
                .append("nodeRole=").append(plan.nodeRole() == null ? "" : plan.nodeRole()).append('\n')
                .append("maxCharacters=").append(plan.maxCharacters()).append('\n')
                .append("sameEntryEvidenceAllowed=true").append('\n')
                .append("crossEntryEvidenceAllowed=false").append('\n')
                .append("rewriteIntent=").append(plan.rewriteIntent() == null ? "" : plan.rewriteIntent()).append('\n')
                .append("rewriteGoal=").append(plan.rewriteGoal()).append('\n');
        if (repair != null && repair.reasonCode() != null) {
            instructions.append(System.lineSeparator())
                    .append(repairInstructions(plan, repair));
        }
        return instructions.toString();
    }

    static String buildConstrainedInput(
            ResumeDocumentNode node,
            ConservativeRewritePlan plan,
            String jobDescription) {

        String original = node.getText() == null ? "" : node.getText();
        String terms = plan.relevantJobTerms() == null
                ? ""
                : String.join(", ", plan.relevantJobTerms());
        String packetJson = plan.allowedFactsText() == null || plan.allowedFactsText().isBlank()
                ? original
                : plan.allowedFactsText();
        String required = plan.requiredFacts() == null ? "" : String.join(" | ", plan.requiredFacts());
        String supportingIds = plan.supportingNodeIds() == null ? "" : String.join(", ", plan.supportingNodeIds());
        String forbidden = plan.forbiddenClaims() == null ? "" : String.join(" | ", plan.forbiddenClaims());
        return """
                nodeId: %s
                targetNodeId: %s
                entryKey: %s
                nodeRole: %s
                rewriteIntent: %s
                originalText: %s
                requiredFacts: %s
                supportingNodeIds: %s
                forbiddenClaims: %s
                EVIDENCE_PACKET_JSON:
                %s
                allowedSameEntryFacts:
                %s
                relevantJobTerms: %s
                matchedRequirementIds: %s
                maxCharacters: %d
                sameEntryEvidenceAllowed: true
                crossEntryEvidenceAllowed: false

                JOB DESCRIPTION:
                %s
                """.formatted(
                plan.nodeId(),
                plan.targetNodeId() == null ? plan.nodeId() : plan.targetNodeId(),
                plan.entryKey() == null ? "" : plan.entryKey(),
                plan.nodeRole() == null ? "" : plan.nodeRole(),
                plan.rewriteIntent() == null ? "" : plan.rewriteIntent(),
                original,
                required,
                supportingIds,
                forbidden,
                packetJson,
                packetJson,
                terms,
                plan.matchedRequirementIds() == null ? "" : String.join(", ", plan.matchedRequirementIds()),
                plan.maxCharacters(),
                jobDescription == null ? "" : jobDescription
        );
    }

    static String repairInstructions(
            ConservativeRewritePlan plan,
            ConservativeRewriteRepair repair) {

        String previous = repair.previousNewText() == null ? "" : repair.previousNewText();
        String reason = repair.reasonCode();
        if (RejectedTailorChange.TEXT_TOO_LONG.equals(reason)) {
            return """
                    REPAIR: TEXT_TOO_LONG
                    Previous newText was too long.
                    maxCharacters=%d
                    Shorten newText to at most %d characters.
                    Keep only facts from originalText.
                    previousNewText: %s
                    """.formatted(plan.maxCharacters(), plan.maxCharacters(), previous);
        }
        if (RejectedTailorChange.TEXT_UNCHANGED.equals(reason)) {
            return """
                    REPAIR: TEXT_UNCHANGED
                    Previous newText did not change the node.
                    Keep every fact from originalText.
                    Produce a materially clearer professional resume bullet.
                    previousNewText: %s
                    """.formatted(previous);
        }
        if (RejectedTailorChange.ROLE_SEMANTIC_ESCALATION.equals(reason)) {
            return """
                    REPAIR: ROLE_SEMANTIC_ESCALATION
                    Do not upgrade individual-contributor verbs (developed/implemented/built)
                    to leadership (led/directed/managed/oversaw/owned).
                    Do not write "directed the creation".
                    Keep the original role and every requiredFact.
                    previousNewText: %s
                    """.formatted(previous);
        }
        if (RejectedTailorChange.INFORMATION_LOSS.equals(reason)) {
            return """
                    REPAIR: INFORMATION_LOSS
                    Previous newText dropped requiredFacts such as design, implement, Java, Maven, Spring Boot, or MVC.
                    Keep every requiredFact from originalText. Do not replace designed/implemented with created.
                    previousNewText: %s
                    """.formatted(previous);
        }
        if (RejectedTailorChange.LOW_VALUE_REWRITE.equals(reason)) {
            return """
                    REPAIR: LOW_VALUE_REWRITE
                    Previous candidates only changed plurality, articles, punctuation, or synonyms.
                    Do not rewrite "that dynamically suggests" into "to dynamically suggest".
                    Do not rewrite "(CPU, GPU & RAM)" into "such as CPU, GPU, and RAM".
                    Do not swap application/project or helps users select/enables users to choose.
                    Produce a materially clearer professional resume bullet while preserving every fact.
                    Start with a strong action verb. Prefer past tense for completed work.
                    If the entry dates include Present, keep present tense for ongoing duties.
                    previousNewText: %s
                    """.formatted(previous);
        }
        if (RejectedTailorChange.FACT_CARDINALITY_CHANGED.equals(reason)) {
            return """
                    REPAIR: FACT_CARDINALITY_CHANGED
                    Do not change singular facts into plurals (application→applications, API→APIs, project→projects)
                    unless originalText or same-entry evidence already uses the plural.
                    Keep the original count. Preserve every fact from originalText.
                    previousNewText: %s
                    """.formatted(previous);
        }
        if (RejectedTailorChange.OPPORTUNITY_NOT_RESOLVED.equals(reason)) {
            return """
                    REPAIR: OPPORTUNITY_NOT_RESOLVED
                    Fix the marked opportunity without changing facts.
                    If originalText contains "managements", change it to management or operations.
                    If the entry dates include Present, keep present tense for ongoing duties.
                    Use past tense only for completed outcomes or ended entries.
                    previousNewText: %s
                    """.formatted(previous);
        }
        if (RejectedTailorChange.SOURCE_FACT_WOULD_DUPLICATE.equals(reason)) {
            return """
                    REPAIR: SOURCE_FACT_WOULD_DUPLICATE
                    Do not copy material facts from other bullets (OOP, AWS, REST, CPU/GPU/RAM).
                    Supporting bullets are context only. Rewrite only the current originalText.
                    previousNewText: %s
                    """.formatted(previous);
        }
        if (RejectedTailorChange.UNSUPPORTED_ACTION.equals(reason)) {
            return """
                    REPAIR: UNSUPPORTED_ACTION
                    Do not add actions missing from originalText.
                    Do not change Implemented into Designed and implemented.
                    Keep the original action family.
                    previousNewText: %s
                    """.formatted(previous);
        }
        if (RejectedTailorChange.GRAMMAR_QUALITY_FAILED.equals(reason)) {
            return """
                    REPAIR: GRAMMAR_QUALITY_FAILED
                    Previous newText had grammar problems.
                    Fix grammar while preserving every fact from originalText.
                    previousNewText: %s
                    """.formatted(previous);
        }
        if (RejectedTailorChange.EVIDENCE_QUOTE_MISMATCH.equals(reason)) {
            return """
                    REPAIR: EVIDENCE_QUOTE_MISMATCH
                    Ignore model-invented evidenceQuote.
                    evidenceQuote must be copied from originalText.
                    evidenceNodeIds must be exactly [%s].
                    previousNewText: %s
                    """.formatted(plan.nodeId(), previous);
        }
        if (RejectedTailorChange.CROSS_NODE_EVIDENCE_NOT_ALLOWED.equals(reason)
                || RejectedTailorChange.CROSS_ENTRY_EVIDENCE_NOT_ALLOWED.equals(reason)) {
            return """
                    REPAIR: CROSS_ENTRY_EVIDENCE_NOT_ALLOWED
                    Use only originalText and ALLOWED SAME-ENTRY FACTS.
                    Do not use facts from any other project or job.
                    Do not use Skills or Summary as project evidence.
                    previousNewText: %s
                    """.formatted(previous);
        }
        return "REPAIR: retry once without adding facts.";
    }

    private static ConservativeRewriteCandidatesResponse emptyCandidates(
            ResumeDocumentNode node,
            ConservativeRewritePlan plan) {

        ConservativeRewriteCandidatesResponse response = new ConservativeRewriteCandidatesResponse();
        if (plan != null) {
            response.setNodeId(plan.nodeId());
        } else if (node != null) {
            response.setNodeId(node.getNodeId());
        }
        response.setCandidates(new ArrayList<>());
        return response;
    }

    private <T> T requestStructuredOutput(
            String instructions,
            String input,
            Class<T> responseType,
            String operation) {

        StructuredResponseCreateParams<T> params =
                ResponseCreateParams.builder()
                        .model(model)
                        .instructions(instructions)
                        .input(input)
                        .text(responseType)
                        .build();

        try {
            return openAIClient.responses()
                    .create(params)
                    .output()
                    .stream()
                    .flatMap(item -> item.message().stream())
                    .flatMap(message -> message.content().stream())
                    .flatMap(content -> content.outputText().stream())
                    .findFirst()
                    .orElseThrow(() -> new AiServiceException(
                            "AI analysis is temporarily unavailable"
                    ));
        } catch (AiServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error(
                    "OpenAI {} failed: {}",
                    operation,
                    e.getClass().getName()
            );
            throw new AiServiceException(
                    "AI analysis is temporarily unavailable",
                    e
            );
        }
    }
}
