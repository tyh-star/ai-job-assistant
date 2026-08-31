package aijob.util;

import aijob.dto.JobRequirementDraft;
import aijob.dto.RequirementCategory;
import aijob.dto.RequirementPriority;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RequirementTaxonomySanitizerTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Bachelor’s degree in Computer Science|degree|QUALIFICATION",
            "Currently pursuing a degree in Computer Science|Computer Science|QUALIFICATION",
            "3+ years of backend experience|backend|QUALIFICATION",
            "AWS certification preferred|AWS|QUALIFICATION",
            "Authorized to work in Canada|Canada|QUALIFICATION",
            "Experience with Maven|Maven|HARD_SKILL",
            "Knowledge of data structures and algorithms|data structures|HARD_SKILL",
            "Proficiency in SQL and MySQL|SQL|HARD_SKILL",
            "Java and Spring Boot|Java|HARD_SKILL",
            "Docker and Kubernetes|Docker|HARD_SKILL",
            "Build and integrate RESTful APIs|REST|RESPONSIBILITY",
            "Debug, test and document backend applications|debug|RESPONSIBILITY",
            "Optimize application performance|optimize|RESPONSIBILITY",
            "Deploy and maintain applications on AWS|AWS|RESPONSIBILITY",
            "Collaborate through an Agile development process|Agile|RESPONSIBILITY",
            "Strong communication and teamwork skills|communication|SOFT_SKILL",
            "Analytical and problem-solving skills|analytical|SOFT_SKILL"
    })
    void tableDrivenCategories(String requirement, String term, String expected) {
        assertEquals(
                expected,
                RequirementTaxonomySanitizer.classify(
                        requirement,
                        List.of(term),
                        RequirementCategory.DOMAIN
                )
        );
    }

    @Test
    void wrongLlmCategoriesAreCorrected() {
        assertEquals(
                RequirementCategory.HARD_SKILL,
                RequirementTaxonomySanitizer.classify(
                        "Experience with Maven",
                        List.of("Maven"),
                        RequirementCategory.QUALIFICATION
                )
        );
        assertEquals(
                RequirementCategory.HARD_SKILL,
                RequirementTaxonomySanitizer.classify(
                        "Knowledge of data structures and algorithms",
                        List.of("data structures"),
                        RequirementCategory.QUALIFICATION
                )
        );
        assertEquals(
                RequirementCategory.RESPONSIBILITY,
                RequirementTaxonomySanitizer.classify(
                        "Deliver clean, well-documented and thoroughly tested code",
                        List.of("code"),
                        RequirementCategory.QUALIFICATION
                )
        );
        assertEquals(
                RequirementCategory.QUALIFICATION,
                RequirementTaxonomySanitizer.classify(
                        "Bachelor degree",
                        List.of("Bachelor"),
                        RequirementCategory.HARD_SKILL
                )
        );
        assertEquals(
                RequirementCategory.SOFT_SKILL,
                RequirementTaxonomySanitizer.classify(
                        "Strong communication skills",
                        List.of("communication"),
                        RequirementCategory.RESPONSIBILITY
                )
        );
    }

    @Test
    void uncertainTextIsOtherKeywordNotQualification() {
        assertEquals(
                RequirementCategory.OTHER_KEYWORD,
                RequirementTaxonomySanitizer.classify("Nice to have", List.of(), RequirementCategory.QUALIFICATION)
        );
        assertNotEquals(
                RequirementCategory.QUALIFICATION,
                RequirementTaxonomySanitizer.classify("Nice to have", List.of(), null)
        );
    }

    @Test
    void applyOverwritesDraftCategory() {
        JobRequirementDraft draft = new JobRequirementDraft();
        draft.setRequirement("Experience with Maven");
        draft.setCategory(RequirementCategory.QUALIFICATION);
        draft.setPriority(RequirementPriority.MUST_HAVE);
        draft.setExactTerms(List.of("Maven"));
        RequirementTaxonomySanitizer.apply(List.of(draft));
        assertEquals(RequirementCategory.HARD_SKILL, draft.getCategory());
    }
}
