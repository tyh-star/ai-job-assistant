package aijob.rewrite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewriteFactGuardTest {

    private static List<String> free(String original, String revised) {
        return RewriteFactGuard.check(original, revised, "", List.of());
    }

    @Test
    void acceptsAPureWordingImprovement() {
        assertEquals(List.of(), free(
                "responsible for build of internal dashboards using React and Java for 3 teams",
                "Built internal dashboards with React and Java for 3 teams"));
    }

    @Test
    void rejectsChangedOrNewNumbers() {
        assertFalse(free("reduced release time by 40%", "Reduced release time by 60%").isEmpty());
        assertFalse(free("Improved the reporting process for the team", "Improved reporting for 5 teams").isEmpty());
    }

    @Test
    void rejectsRemovedOrNewNamesCompaniesAndSkills() {
        assertFalse(free("Built dashboards at Example Corp with React", "Built dashboards with React").isEmpty(),
                "company removed");
        assertFalse(free("Built dashboards with React for the team", "Built dashboards with React and Kubernetes for the team").isEmpty(),
                "new skill added");
        assertFalse(free("Worked as Software Developer on internal tools", "Worked as Senior Software Architect on internal tools").isEmpty(),
                "title changed");
    }

    @Test
    void rejectsChangedDatesAndPersonalDetailPlaceholders() {
        assertFalse(free("Joined in March 2021 as analyst", "Joined in 2022 as analyst").isEmpty());
        assertFalse(free("Contact [[PII_1]] for references and more", "Contact me for references and more").isEmpty());
    }

    @Test
    void proMayUseTermsFromEvidenceButNeverUnsupportedTerms() {
        String evidence = "Used Docker for local builds\nConfirmed: I deployed services with Docker Compose";
        assertTrue(RewriteFactGuard.check("Maintained build scripts for services", "Maintained Docker build scripts for services",
                evidence, List.of("Kubernetes")).isEmpty());
        List<String> unsupported = RewriteFactGuard.check("Maintained build scripts for services",
                "Maintained Kubernetes build scripts for services", evidence, List.of("Kubernetes"));
        assertTrue(unsupported.stream().anyMatch(v -> v.contains("Kubernetes")));
    }

    @Test
    void chineseTextKeepsNumbers() {
        assertTrue(free("负责内部报表系统开发，服务3个团队，缩短发布时间40%", "主导开发内部报表系统，服务3个团队，将发布时间缩短40%").isEmpty());
        assertFalse(free("负责内部报表系统开发，服务3个团队", "负责内部报表系统开发，服务5个团队").isEmpty());
    }
}
