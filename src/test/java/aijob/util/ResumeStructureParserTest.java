package aijob.util;

import aijob.dto.ResumeStructure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeStructureParserTest {

    private static final String SAMPLE = """
            Jane Doe
            jane.doe@example.com
            (555) 123-4567
            San Francisco, CA
            https://linkedin.com/in/janedoe

            SUMMARY
            Software engineer with 5 years of experience building APIs.

            SKILLS
            Java, Spring Boot, MySQL

            EXPERIENCE
            Acme Corp | Backend Engineer | 2020 - Present
            - Built payment APIs
            - Reduced latency

            EDUCATION
            MIT | B.S. Computer Science | 2016 - 2020

            PROJECTS
            Job Tracker
            - Parsed Greenhouse job descriptions
            """;

    private final ResumeStructureParser parser = new ResumeStructureParser();

    @Test
    void parse_sampleResume_fillsContactSkillsAndExperience() {
        ResumeStructure structure = parser.parse(SAMPLE);

        assertEquals("Jane Doe", structure.getContact().getFullName());
        assertEquals("jane.doe@example.com", structure.getContact().getEmail());
        assertTrue(structure.getContact().getPhone().contains("555"));
        assertEquals("San Francisco, CA", structure.getContact().getLocation());
        assertTrue(structure.getContact().getLinks().stream()
                .anyMatch(link -> link.contains("linkedin.com/in/janedoe")));

        assertTrue(structure.getSummary().contains("Software engineer"));
        assertTrue(hasSkill(structure, "Java"));
        assertTrue(hasSkill(structure, "Spring Boot"));
        assertTrue(hasSkill(structure, "MySQL"));

        assertEquals(1, structure.getExperience().size());
        assertEquals("Acme Corp", structure.getExperience().get(0).getCompany());
        assertEquals("Backend Engineer", structure.getExperience().get(0).getTitle());
        assertEquals("2020", structure.getExperience().get(0).getStartDate());
        assertEquals("Present", structure.getExperience().get(0).getEndDate());
        assertEquals(2, structure.getExperience().get(0).getBullets().size());
        assertEquals("Built payment APIs", structure.getExperience().get(0).getBullets().get(0));

        assertEquals("MIT", structure.getEducation().get(0).getSchool());
        assertEquals("B.S. Computer Science", structure.getEducation().get(0).getDegree());
        assertEquals("Job Tracker", structure.getProjects().get(0).getName());

        assertEquals(ResumeStructureParser.CONFIDENCE_MEDIUM,
                structure.getFieldConfidence().get("contact.fullName"));
        assertEquals(ResumeStructureParser.CONFIDENCE_HIGH,
                structure.getFieldConfidence().get("contact.email"));
        assertEquals(ResumeStructureParser.CONFIDENCE_HIGH,
                structure.getFieldConfidence().get("contact.phone"));
        assertEquals(ResumeStructureParser.CONFIDENCE_MEDIUM,
                structure.getFieldConfidence().get("experience"));
    }

    @Test
    void parse_blankText_returnsEmptyNormalizedDraft() {
        ResumeStructure structure = parser.parse("   ");

        assertEquals("", structure.getContact().getFullName());
        assertTrue(structure.getSkills().isEmpty());
        assertTrue(structure.getExperience().isEmpty());
        assertEquals(ResumeStructureParser.CONFIDENCE_LOW,
                structure.getFieldConfidence().get("contact.fullName"));
    }

    @Test
    void parse_doesNotInventSectionsWithoutHeadings() {
        ResumeStructure structure = parser.parse("Only a name line here");

        assertFalse(structure.getContact().getFullName().isBlank());
        assertTrue(structure.getSkills().isEmpty());
        assertTrue(structure.getExperience().isEmpty());
        assertEquals(ResumeStructureParser.CONFIDENCE_LOW,
                structure.getFieldConfidence().get("skills"));
    }

    private static boolean hasSkill(ResumeStructure structure, String name) {
        return structure.getSkills().stream().anyMatch(skill -> name.equals(skill.getName()));
    }
}
