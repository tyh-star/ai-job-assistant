package aijob.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TermAliasNormalizerTest {

    @Test
    void mvcArchitectureMatchesMvcPattern() {
        assertTrue(TermAliasNormalizer.matches(
                "Design and implement the application using Java Spring Boot with an MVC pattern.",
                "MVC architecture"
        ));
        assertTrue(TermAliasNormalizer.matches("MVC pattern", "MVC"));
    }

    @Test
    void restfulApisMatchRestApis() {
        assertTrue(TermAliasNormalizer.matches(
                "Integrated RESTful APIs to handle user authentication",
                "REST APIs"
        ));
        assertTrue(TermAliasNormalizer.matches("RESTful API", "REST API"));
    }

    @Test
    void bachelorOfComputerScienceMatchesDegreeRequirement() {
        assertTrue(TermAliasNormalizer.matches(
                "Bachelor of Computer Science",
                "Pursuing or completed a degree in Computer Science or related field"
        ));
    }

    @Test
    void designApplicationDoesNotMatchMvc() {
        assertFalse(TermAliasNormalizer.matches(
                "Design and implement the application for local checkout.",
                "MVC architecture"
        ));
        assertFalse(TermAliasNormalizer.matches("architecture review", "MVC"));
    }

    @Test
    void skillIdentityKeepsDistinctToolsAndMergesAliases() {
        assertTrue(TermAliasNormalizer.sameTerm("REST", "RESTful API"));
        assertEquals("RESTful API", TermAliasNormalizer.displayName("REST APIs"));
        assertTrue(TermAliasNormalizer.sameTerm("OOP", "Object-Oriented Programming"));
        assertEquals("Javadoc", TermAliasNormalizer.displayName("java doc string"));
        assertFalse(TermAliasNormalizer.sameTerm("Java", "JavaScript"));
        assertFalse(TermAliasNormalizer.sameTerm("Spring", "Spring Boot"));
        assertFalse(TermAliasNormalizer.sameTerm("MySQL", "SQL"));
    }
}
