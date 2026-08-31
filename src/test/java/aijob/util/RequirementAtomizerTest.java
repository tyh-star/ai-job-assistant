package aijob.util;

import aijob.dto.JobRequirementDraft;
import aijob.dto.RequirementCategory;
import aijob.dto.RequirementPriority;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementAtomizerTest {

    @Test
    void dockerAndKubernetesBecomeTwoRequirements() {
        List<JobRequirementDraft> result = RequirementAtomizer.atomize(List.of(
                draft("Docker and Kubernetes", RequirementCategory.HARD_SKILL,
                        RequirementPriority.PREFERRED, List.of("Docker", "Kubernetes"))
        ));

        assertEquals(List.of("Docker", "Kubernetes"), names(result));
        assertEquals(List.of("Docker"), result.get(0).getExactTerms());
        assertEquals(List.of("Kubernetes"), result.get(1).getExactTerms());
    }

    @Test
    void javaAndSpringBootBecomeTwoRequirements() {
        List<JobRequirementDraft> result = RequirementAtomizer.atomize(List.of(
                draft("Java and Spring Boot", RequirementCategory.HARD_SKILL,
                        RequirementPriority.MUST_HAVE, List.of("Java", "Spring Boot"))
        ));

        assertEquals(List.of("Java", "Spring Boot"), names(result));
    }

    @Test
    void awsAndAzureAndSqlAndNoSqlAreSplit() {
        List<JobRequirementDraft> result = RequirementAtomizer.atomize(List.of(
                draft("AWS and Azure", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("AWS", "Azure")),
                draft("SQL and NoSQL", RequirementCategory.HARD_SKILL, RequirementPriority.PREFERRED,
                        List.of("SQL", "NoSQL"))
        ));

        assertEquals(List.of("AWS", "Azure", "SQL", "NoSQL"), names(result));
    }

    @Test
    void restApiDesignAndImplementationStaysOneRequirement() {
        List<JobRequirementDraft> result = RequirementAtomizer.atomize(List.of(
                draft("REST API design and implementation", RequirementCategory.RESPONSIBILITY,
                        RequirementPriority.MUST_HAVE, List.of("REST API", "design", "implementation"))
        ));

        assertEquals(1, result.size());
        assertEquals("REST API design and implementation", result.get(0).getRequirement());
        assertEquals(List.of("REST API"), result.get(0).getExactTerms());
    }

    @Test
    void communicationAndCollaborationIsNotExploded() {
        List<JobRequirementDraft> result = RequirementAtomizer.atomize(List.of(
                draft("communication and collaboration", RequirementCategory.SOFT_SKILL,
                        RequirementPriority.PREFERRED, List.of("communication", "collaboration"))
        ));

        assertEquals(1, result.size());
        assertEquals("communication and collaboration", result.get(0).getRequirement());
    }

    @Test
    void sqlAndMysqlAreSplit() {
        List<JobRequirementDraft> result = RequirementAtomizer.atomize(List.of(
                draft("Proficiency in SQL and MySQL", RequirementCategory.HARD_SKILL,
                        RequirementPriority.MUST_HAVE, List.of("SQL", "MySQL"))
        ));

        assertEquals(List.of("SQL", "MySQL"), names(result));
        assertTrue(result.stream().allMatch(item -> RequirementCategory.HARD_SKILL.equals(item.getCategory())));
    }

    @Test
    void debugTestDocumentStayOneResponsibility() {
        List<JobRequirementDraft> result = RequirementAtomizer.atomize(List.of(
                draft(
                        "Debug, test and document backend applications",
                        RequirementCategory.RESPONSIBILITY,
                        RequirementPriority.MUST_HAVE,
                        List.of("debug", "test", "document")
                )
        ));

        assertEquals(1, result.size());
        assertEquals("Debug, test and document backend applications", result.get(0).getRequirement());
        assertEquals(RequirementCategory.RESPONSIBILITY, result.get(0).getCategory());
    }

    @Test
    void bachelorDegreeInCsIsNotSplit() {
        List<JobRequirementDraft> result = RequirementAtomizer.atomize(List.of(
                draft(
                        "Bachelor's degree in CS or related field",
                        RequirementCategory.QUALIFICATION,
                        RequirementPriority.MUST_HAVE,
                        List.of("degree", "Computer Science")
                )
        ));

        assertEquals(1, result.size());
        assertEquals("Bachelor's degree in CS or related field", result.get(0).getRequirement());
        assertEquals(RequirementCategory.QUALIFICATION, result.get(0).getCategory());
    }

    @Test
    void deployAndMaintainStayOneResponsibility() {
        List<JobRequirementDraft> result = RequirementAtomizer.atomize(List.of(
                draft(
                        "Deploy and maintain applications on AWS",
                        RequirementCategory.RESPONSIBILITY,
                        RequirementPriority.MUST_HAVE,
                        List.of("deploy", "maintain", "AWS")
                )
        ));

        assertEquals(1, result.size());
        assertEquals("Deploy and maintain applications on AWS", result.get(0).getRequirement());
    }

    @Test
    void overlappingJavaRequirementsKeepHighestPriority() {
        List<JobRequirementDraft> result = RequirementAtomizer.atomize(List.of(
                draft("Java", RequirementCategory.HARD_SKILL, RequirementPriority.PREFERRED, List.of("Java")),
                draft("Java and Spring Boot", RequirementCategory.HARD_SKILL, RequirementPriority.MUST_HAVE,
                        List.of("Java", "Spring Boot"))
        ));

        assertEquals(List.of("Java", "Spring Boot"), names(result));
        assertEquals(RequirementPriority.MUST_HAVE, result.get(0).getPriority());
        assertTrue(result.stream().noneMatch(item -> item.getRequirement() == null));
    }

    @Test
    void agileKnowledgeAndCollaborationAreNotMerged() {
        List<JobRequirementDraft> result = RequirementAtomizer.atomize(List.of(
                draft(
                        "Knowledge of Agile development",
                        RequirementCategory.HARD_SKILL,
                        RequirementPriority.PREFERRED,
                        List.of("Agile")
                ),
                draft(
                        "Collaborate through an Agile development process",
                        RequirementCategory.RESPONSIBILITY,
                        RequirementPriority.MUST_HAVE,
                        List.of("Agile")
                )
        ));

        assertEquals(2, result.size());
        assertEquals("Knowledge of Agile development", result.get(0).getRequirement());
        assertEquals("Collaborate through an Agile development process", result.get(1).getRequirement());
        assertEquals(RequirementCategory.HARD_SKILL, result.get(0).getCategory());
        assertEquals(RequirementCategory.RESPONSIBILITY, result.get(1).getCategory());
    }

    private List<String> names(List<JobRequirementDraft> drafts) {
        return drafts.stream().map(JobRequirementDraft::getRequirement).toList();
    }

    private JobRequirementDraft draft(String requirement, String category, String priority, List<String> terms) {
        JobRequirementDraft draft = new JobRequirementDraft();
        draft.setRequirement(requirement);
        draft.setCategory(category);
        draft.setPriority(priority);
        draft.setExactTerms(terms);
        return draft;
    }
}
