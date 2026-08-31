package aijob.service.impl;

import aijob.dto.JobRequirementDraft;
import aijob.dto.JobRequirementsLlmResponse;
import aijob.exception.AiServiceException;
import aijob.service.JobRequirementExtractor;
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
public class OpenAiJobRequirementExtractor implements JobRequirementExtractor {

    private static final Logger log = LoggerFactory.getLogger(OpenAiJobRequirementExtractor.class);

    static final String INSTRUCTIONS = """
            Extract at most 20 distinct hiring requirements from this JOB DESCRIPTION.

            Return only requirements that are explicitly written in the job description.
            Do not add technologies, degrees, tools, or duties that are not in the JD.
            Do not invent Docker, Kubernetes, CI/CD, cloud, or metrics unless the JD says them.

            Atomize technical skills: one requirement per technology.
            Split "Java and Spring Boot" into Java and Spring Boot.
            Split "Docker and Kubernetes" into Docker and Kubernetes.
            Split "AWS and Azure" into AWS and Azure.
            Split "SQL and NoSQL" into SQL and NoSQL.
            Keep an inseparable combination as one item only when the JD states it as a single
            capability, for example "REST API design and implementation".
            Do not explode ordinary soft skills such as "communication and collaboration".
            Do not split duty lists such as "debug, test and document" into separate requirements.

            Category is a candidate only. The server will correct it. Still try:
            - QUALIFICATION: degree, diploma, certification, license, years-of-experience,
              work authorization, clearance, or location/travel/language eligibility.
              A JD heading named Qualifications does not make a tool, language, framework,
              database, CS concept, or code-quality duty a QUALIFICATION.
            - HARD_SKILL: languages, frameworks, databases, cloud, tools, technical methods,
              CS/technical concepts, and "experience with / knowledge of / proficiency in"
              a technical topic.
            - RESPONSIBILITY: build, design, develop, implement, debug, test, document,
              optimize, deploy, maintain, support, collaborate, review, operate.
              Keep a duty list as one requirement.
            - SOFT_SKILL: communication, teamwork, leadership, analytical, problem-solving.
            - OTHER_KEYWORD: only when none of the above apply. Never default to QUALIFICATION.

            For each requirement:
            - requirement: a short phrase copied or lightly compressed from the JD
            - category: HARD_SKILL, RESPONSIBILITY, QUALIFICATION, DOMAIN, SOFT_SKILL, or OTHER_KEYWORD
            - priority: MUST_HAVE only when the JD clearly marks it as required, must-have,
              or a core responsibility. Use PREFERRED for nice-to-have / plus / bonus.
              Use OPTIONAL for weakly mentioned extras. Do not treat the word
              "qualification" in a heading as MUST_HAVE by itself.
            - exactTerms: original technology words or key phrases copied from the JD
              (for example Java, Spring Boot, REST API, Maven, Docker). Keep original spelling.
              Do not use generic verbs such as design, implement, develop, or manage as exactTerms.

            Merge synonymous duplicates into one requirement.
            Do not score the resume. Do not guess whether the candidate has the skill.
            """;

    private final OpenAIClient openAIClient;
    private final String model;

    public OpenAiJobRequirementExtractor(
            OpenAIClient openAIClient,
            @Value("${openai.model}") String model) {
        this.openAIClient = openAIClient;
        this.model = model;
    }

    @Override
    public List<JobRequirementDraft> extract(String jobDescription) {
        if (jobDescription == null || jobDescription.isBlank()) {
            return List.of();
        }
        log.info("Calling OpenAI job requirement extractor model={}", model);
        JobRequirementsLlmResponse response = requestStructuredOutput(
                INSTRUCTIONS,
                buildInput(jobDescription),
                JobRequirementsLlmResponse.class
        );
        if (response == null || response.requirements == null) {
            return List.of();
        }
        return new ArrayList<>(response.requirements);
    }

    static String buildInput(String jobDescription) {
        return """
                JOB DESCRIPTION:
                %s
                """.formatted(jobDescription);
    }

    private <T> T requestStructuredOutput(
            String instructions,
            String input,
            Class<T> responseType) {

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
            log.error("OpenAI job requirement extraction failed: {}", e.getClass().getName());
            throw new AiServiceException(
                    "AI analysis is temporarily unavailable",
                    e
            );
        }
    }
}
