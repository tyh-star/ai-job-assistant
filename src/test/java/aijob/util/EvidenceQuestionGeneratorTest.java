package aijob.util;

import aijob.dto.CoverageStatus;
import aijob.dto.EntryOption;
import aijob.dto.EvidenceKind;
import aijob.dto.EvidenceQuestion;
import aijob.dto.QuestionType;
import aijob.dto.RequirementCategory;
import aijob.dto.RequirementPriority;
import aijob.dto.TailorAnalysisRequirement;
import aijob.entity.ResumeEvidenceItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceQuestionGeneratorTest {

    private final List<EntryOption> entries = List.of(ResumeEntryCatalog.generalOption());

    @Test
    void missingMustHaveDockerGeneratesSkillUsageFirst() {
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(List.of(
                req("req-6", "Docker", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        CoverageStatus.MISSING_EVIDENCE, null),
                req("req-7", "Kubernetes", RequirementCategory.HARD_SKILL, RequirementPriority.PREFERRED,
                        CoverageStatus.MISSING_EVIDENCE, null)
        ), List.of(), entries);

        assertEquals("Docker", questions.get(0).getTopic());
        assertEquals(QuestionType.SKILL_USAGE, questions.get(0).getQuestionType());
        assertEquals("Kubernetes", questions.get(1).getTopic());
    }

    @Test
    void missingPreferredKubernetesComesAfterMustHave() {
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(List.of(
                req("req-1", "Java", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        CoverageStatus.MISSING_EVIDENCE, null),
                req("req-7", "Kubernetes", RequirementCategory.HARD_SKILL, RequirementPriority.PREFERRED,
                        CoverageStatus.MISSING_EVIDENCE, null)
        ), List.of(), entries);

        assertEquals(List.of("Java", "Kubernetes"), questions.stream().map(EvidenceQuestion::getTopic).toList());
    }

    @Test
    void partiallySupportedResponsibilityGeneratesExampleQuestion() {
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(List.of(
                req("req-5", "backend services", RequirementCategory.RESPONSIBILITY, RequirementPriority.MUST_HAVE,
                        CoverageStatus.PARTIALLY_SUPPORTED, EvidenceKind.PROJECT_BULLET)
        ), List.of(), entries);

        assertEquals(QuestionType.RESPONSIBILITY_EXAMPLE, questions.get(0).getQuestionType());
        assertTrue(questions.get(0).getPromptZh().contains("backend services"));
    }

    @Test
    void skillsOnlyJavaGeneratesSkillApplication() {
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(List.of(
                req("req-1", "Java", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        CoverageStatus.SUPPORTED, EvidenceKind.SKILLS_ONLY)
        ), List.of(), entries);

        assertEquals(QuestionType.SKILL_APPLICATION, questions.get(0).getQuestionType());
    }

    @Test
    void returnsAtMostFiveQuestions() {
        List<TailorAnalysisRequirement> requirements = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            requirements.add(req("req-" + i, "Skill" + i, RequirementCategory.HARD_SKILL,
                    RequirementPriority.MUST_HAVE, CoverageStatus.MISSING_EVIDENCE, null));
        }
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(requirements, List.of(), entries);
        assertEquals(5, questions.size());
    }

    @Test
    void confirmedUsedTopicIsNotAskedAgain() {
        ResumeEvidenceItem item = new ResumeEvidenceItem();
        item.setTopicKey("docker");
        item.setTopicLabel("Docker");
        item.setCategory(RequirementCategory.HARD_SKILL);
        item.setAnswerStatus("CONFIRMED_USED");
        item.setActionText("Containerized the Spring Boot application with Docker");
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(List.of(
                req("req-6", "Docker", RequirementCategory.HARD_SKILL, RequirementPriority.PREFERRED,
                        CoverageStatus.MISSING_EVIDENCE, null),
                req("req-7", "Kubernetes", RequirementCategory.HARD_SKILL, RequirementPriority.PREFERRED,
                        CoverageStatus.MISSING_EVIDENCE, null)
        ), List.of(item), entries);

        assertEquals(List.of("Kubernetes"), questions.stream().map(EvidenceQuestion::getTopic).toList());
    }

    @Test
    void confirmedNotUsedTopicIsNotAskedAgain() {
        ResumeEvidenceItem item = new ResumeEvidenceItem();
        item.setTopicKey("kubernetes");
        item.setAnswerStatus("CONFIRMED_NOT_USED");
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(List.of(
                req("req-7", "Kubernetes", RequirementCategory.HARD_SKILL, RequirementPriority.PREFERRED,
                        CoverageStatus.MISSING_EVIDENCE, null)
        ), List.of(item), entries);

        assertTrue(questions.isEmpty());
    }

    @Test
    void supportedMvcWithProjectEvidenceIsNotAskedAgain() {
        TailorAnalysisRequirement mvc = req("req-mvc", "MVC architecture", RequirementCategory.HARD_SKILL,
                RequirementPriority.MUST_HAVE, CoverageStatus.SUPPORTED, EvidenceKind.PROJECT_BULLET);
        mvc.setEvidenceAvailability("IN_RESUME");
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(List.of(mvc), List.of(), entries);
        assertTrue(questions.isEmpty());
    }

    @Test
    void userConfirmedMvcIsNotAskedAgain() {
        ResumeEvidenceItem item = new ResumeEvidenceItem();
        item.setTopicKey("mvc architecture");
        item.setTopicLabel("MVC architecture");
        item.setCategory(RequirementCategory.HARD_SKILL);
        item.setAnswerStatus("CONFIRMED_USED");
        item.setActionText("Implemented features using an MVC pattern in Spring Boot");
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(List.of(
                req("req-mvc", "MVC architecture", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        CoverageStatus.MISSING_EVIDENCE, null)
        ), List.of(item), entries);
        assertTrue(questions.isEmpty());
    }

    @Test
    void qualificationDoesNotGenerateProjectQuestionWhenEducationExists() {
        TailorAnalysisRequirement cs = req(
                "req-cs",
                "degree",
                RequirementCategory.QUALIFICATION,
                RequirementPriority.PREFERRED,
                CoverageStatus.PARTIALLY_SUPPORTED,
                EvidenceKind.EDUCATION
        );
        cs.setRequirement("Pursuing or completed a degree in Computer Science or related field");
        cs.setExactTerms(List.of("degree", "Computer Science"));
        cs.setCoverageReason(QualificationMatcher.CURRENTLY_PURSUING);
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(List.of(cs), List.of(), entries);
        assertTrue(questions.isEmpty());
        assertTrue(questions.stream().noneMatch(item ->
                QuestionType.SKILL_USAGE.equals(item.getQuestionType())));
    }

    @Test
    void missingEducationGeneratesEducationConfirmationWithoutProjectEntries() {
        TailorAnalysisRequirement cs = req(
                "req-cs",
                "degree",
                RequirementCategory.QUALIFICATION,
                RequirementPriority.PREFERRED,
                CoverageStatus.MISSING_EVIDENCE,
                null
        );
        cs.setRequirement("Pursuing or completed a degree in Computer Science or related field");
        cs.setExactTerms(List.of("degree", "Computer Science"));
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(List.of(cs), List.of(), entries);
        assertEquals(1, questions.size());
        assertEquals(QuestionType.EDUCATION_CONFIRMATION, questions.get(0).getQuestionType());
        assertEquals("Computer Science", questions.get(0).getTopic());
        assertTrue(questions.get(0).getEntryOptions().isEmpty());
        assertTrue(questions.get(0).getPromptEn().toLowerCase().contains("school"));
        assertFalse(questions.get(0).getPromptZh().contains("项目中使用过"));
        assertFalse(questions.get(0).getPromptEn().toLowerCase().contains("select the relevant entry"));
    }

    @Test
    void skillsOnlySqlStillAsksForProjectUse() {
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(List.of(
                req("req-sql", "SQL", RequirementCategory.HARD_SKILL, RequirementPriority.PREFERRED,
                        CoverageStatus.SUPPORTED, EvidenceKind.SKILLS_ONLY)
        ), List.of(), entries);
        assertEquals(QuestionType.SKILL_APPLICATION, questions.get(0).getQuestionType());
        assertEquals("SQL", questions.get(0).getTopic());
    }

    @Test
    void missingAgileKeepsProjectQuestion() {
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(List.of(
                req("req-agile", "Agile", RequirementCategory.HARD_SKILL, RequirementPriority.PREFERRED,
                        CoverageStatus.MISSING_EVIDENCE, null)
        ), List.of(), entries);
        assertEquals(QuestionType.SKILL_USAGE, questions.get(0).getQuestionType());
        assertEquals("Agile", questions.get(0).getTopic());
        assertFalse(questions.get(0).getEntryOptions().isEmpty());
    }

    @Test
    void debugTestDocumentIsOneGroupedQuestion() {
        TailorAnalysisRequirement duty = req(
                "req-debug",
                "debug",
                RequirementCategory.RESPONSIBILITY,
                RequirementPriority.MUST_HAVE,
                CoverageStatus.PARTIALLY_SUPPORTED,
                EvidenceKind.PROJECT_BULLET
        );
        duty.setRequirement("Debug, test and document backend applications");
        duty.setExactTerms(List.of("debug", "test", "document"));
        duty.setEvidenceQuotes(List.of(
                "Delivered clean, well-documented (using Java doc string), and thoroughly tested code to ensure the quality"
        ));
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(List.of(duty), List.of(), entries);
        assertEquals(1, questions.size());
        assertEquals("Debug, test and document backend applications", questions.get(0).getTopic());
        assertTrue(questions.get(0).getMissingActions().contains("debug"));
        assertFalse(questions.get(0).getMissingActions().contains("test"));
        assertEquals(1, questions.stream().filter(item -> item.getTopic().toLowerCase().contains("debug")).count());
        assertTrue(questions.get(0).getPromptEn().toLowerCase().contains("debug"));
    }

    @Test
    void doesNotPadWithOptionalSoftSkills() {
        List<TailorAnalysisRequirement> requirements = List.of(
                req("req-d", "Docker", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        CoverageStatus.MISSING_EVIDENCE, null),
                req("req-soft", "communication", RequirementCategory.SOFT_SKILL, RequirementPriority.PREFERRED,
                        CoverageStatus.MISSING_EVIDENCE, null),
                req("req-opt", "nice to have", RequirementCategory.SOFT_SKILL, RequirementPriority.OPTIONAL,
                        CoverageStatus.MISSING_EVIDENCE, null)
        );
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(requirements, List.of(), entries);
        assertEquals(List.of("Docker"), questions.stream().map(EvidenceQuestion::getTopic).toList());
    }

    @Test
    void educationPresentDoesNotAskComputerScienceQuestion() {
        TailorAnalysisRequirement cs = req(
                "req-cs",
                "Computer Science",
                RequirementCategory.QUALIFICATION,
                RequirementPriority.MUST_HAVE,
                CoverageStatus.PARTIALLY_SUPPORTED,
                EvidenceKind.EDUCATION
        );
        cs.setRequirement("Currently pursuing or recently completed a degree in Computer Science");
        cs.setEvidenceNodeIds(List.of("edu-structure-0"));
        List<EvidenceQuestion> questions = EvidenceQuestionGenerator.generate(List.of(cs), List.of(), entries);
        assertTrue(questions.isEmpty());
    }

    private TailorAnalysisRequirement req(
            String id,
            String topic,
            String category,
            String priority,
            String status,
            String evidenceKind) {
        TailorAnalysisRequirement requirement = new TailorAnalysisRequirement();
        requirement.setId(id);
        requirement.setRequirement(topic);
        requirement.setExactTerms(List.of(topic));
        requirement.setCategory(category);
        requirement.setPriority(priority);
        requirement.setStatus(status);
        requirement.setEvidenceKind(evidenceKind);
        return requirement;
    }
}
