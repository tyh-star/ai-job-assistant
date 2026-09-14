package aijob.util;

import aijob.dto.builder.ResumeBuilderDraftContent;
import aijob.dto.builder.ResumeBuilderDraftContent.BasicInfo;
import aijob.dto.builder.ResumeBuilderDraftContent.ProfessionalLink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixtures: Alex Morgan -- entirely fictional. */
class ResumeBuilderProfessionalLinksTest {

    @Test
    void oldDraftJsonWithScalarLinksMigratesWithoutLoss() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String json = """
                {"schemaVersion":1,"basicInfo":{"fullName":"Alex Morgan","linkedin":"https://linkedin.com/in/alexmorgan","github":"https://github.com/alexmorgan","website":"https://alex.example"}}
                """;
        ResumeBuilderDraftContent content = mapper.readValue(json, ResumeBuilderDraftContent.class);
        assertNull(content.getBasicInfo().getProfessionalLinks());

        content.normalize();

        List<ProfessionalLink> links = content.getBasicInfo().getProfessionalLinks();
        assertEquals(3, links.size());
        assertEquals("https://linkedin.com/in/alexmorgan", content.getBasicInfo().getLinkedin());
        assertEquals("https://github.com/alexmorgan", content.getBasicInfo().getGithub());
        assertEquals("https://alex.example", content.getBasicInfo().getWebsite());
        assertTrue(links.stream().anyMatch(link ->
                ResumeBuilderProfessionalLinks.GITHUB.equals(link.getType())
                        && "https://github.com/alexmorgan".equals(link.getUrl())));
    }

    @Test
    void emptyGithubTypeRowIsNotEmittedInHeaderLines() {
        BasicInfo info = new BasicInfo();
        info.setProfessionalLinks(List.of(ResumeBuilderProfessionalLinks.empty(
                ResumeBuilderProfessionalLinks.GITHUB)));
        ResumeBuilderProfessionalLinks.migrateAndSync(info);

        List<String> lines = ResumeBuilderProfessionalLinks.headerLines(info);
        assertTrue(lines.isEmpty());
        assertFalse(String.join(" ", lines).toLowerCase().contains("github"));
        assertEquals("", info.getGithub());
    }

    @Test
    void filledGithubLinkIsMirroredBackOntoTheScalar() {
        BasicInfo info = new BasicInfo();
        ProfessionalLink github = ResumeBuilderProfessionalLinks.empty(ResumeBuilderProfessionalLinks.GITHUB);
        github.setUrl("https://github.com/alexmorgan");
        info.setProfessionalLinks(List.of(github));
        ResumeBuilderProfessionalLinks.migrateAndSync(info);

        assertEquals("https://github.com/alexmorgan", info.getGithub());
        assertEquals(List.of("https://github.com/alexmorgan"), ResumeBuilderProfessionalLinks.headerLines(info));
    }

    @Test
    void explicitEmptyListDoesNotRehydrateFromScalars() {
        BasicInfo info = new BasicInfo();
        info.setGithub("https://github.com/should-not-reappear");
        info.setProfessionalLinks(List.of());
        ResumeBuilderProfessionalLinks.migrateAndSync(info);

        assertTrue(info.getProfessionalLinks().isEmpty());
        assertEquals("", info.getGithub());
    }
}
