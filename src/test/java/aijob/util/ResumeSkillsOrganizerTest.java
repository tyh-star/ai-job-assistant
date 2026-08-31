package aijob.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumeSkillsOrganizerTest {

    private static final String ORIGINAL = """
            Languages: C/C++, Java, Python, Shell Script, SQL, HTML, Vue, Javascript, CSS
            Tools: IntelliJ, Eclipse, PyCharm, GitHub, DBeaver, VScode
            Databases/OS: MySQL, Windows, Unix/Linux, Mac OS
            """;

    @Test
    void keepsEveryOriginalSkillAndCanPromoteJdMatches() {
        ResumeSkillsOrganizer.Result result = ResumeSkillsOrganizer.organize(
                ORIGINAL,
                "Java backend role using SQL, MySQL, and GitHub."
        );

        assertTrue(result.reliable());
        String text = result.text();
        assertTrue(text.contains("C/C++"));
        assertTrue(text.contains("Python"));
        assertTrue(text.contains("Vue"));
        assertTrue(text.toLowerCase().contains("javascript"));
        assertTrue(text.contains("Java"));
        assertTrue(text.contains("SQL"));
        assertTrue(text.contains("MySQL"));
        assertTrue(text.contains("GitHub"));
        int javaAt = text.indexOf("Java");
        int pythonAt = text.indexOf("Python");
        assertTrue(javaAt >= 0 && pythonAt >= 0 && javaAt < pythonAt);
        assertFalse(text.contains("Docker"));
        assertFalse(text.contains("Kubernetes"));
    }

    @Test
    void keepsOriginalWhenUnparseable() {
        String original = "Hands-on builder who enjoys shipping products.";
        ResumeSkillsOrganizer.Result result = ResumeSkillsOrganizer.organize(
                original,
                "Java Spring Boot"
        );
        assertEquals(original, result.text());
        assertFalse(result.changed());
    }

    @Test
    void doesNotAddSkills() {
        ResumeSkillsOrganizer.Result result = ResumeSkillsOrganizer.organize(
                "Java, SQL, GitHub",
                "Need Java, SQL, GitHub, Docker, Kubernetes."
        );
        assertFalse(result.text().toLowerCase().contains("docker"));
        assertTrue(result.text().contains("Java"));
        assertTrue(result.text().contains("SQL"));
        assertTrue(result.text().contains("GitHub"));
    }
}
