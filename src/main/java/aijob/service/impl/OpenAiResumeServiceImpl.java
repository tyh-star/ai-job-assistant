package aijob.service.impl;

import aijob.dto.JobMatchResponse;
import aijob.dto.ResumeAnalysisResponse;
import aijob.dto.ResumeDocumentNode;
import aijob.dto.TailorChange;
import aijob.dto.TailorChangesResponse;
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

            Every change must have:
            evidenceType = VERIFIED
            inferredSkill = empty
            requiresConfirmation = false
            """;

    private static final String TAILOR_AGGRESSIVE_RULES = """
            MODE: AGGRESSIVE

            Maximize match to this JD.

            VERIFIED changes:
            Rewrite using evidence already in the resume.
            evidenceType = VERIFIED
            inferredSkill = empty
            requiresConfirmation = false

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
