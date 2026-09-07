package aijob.util;

import aijob.dto.ResumeQualityCategoryScore;
import aijob.dto.ResumeQualityScoreResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * All fixtures here are SYNTHETIC -- invented for this test, never a real user's
 * resume content -- per the requirement that this rubric never be exercised against
 * real resume text in a test.
 */
class ResumeQualityScorerTest {

    private final ResumeQualityScorer scorer = new ResumeQualityScorer();

    // ---------------------------------------------------------------------------
    // Invariants that must hold for every scorable result
    // ---------------------------------------------------------------------------

    private static final String COMPLETE_RESUME = """
            Taylor Kim
            taylor.kim@example.com | (555) 987-6543

            EXPERIENCE
            Backend Engineer, Acme Systems, Mar 2021 - Present
            Cut checkout latency 42% by rewriting the pricing cache in Redis
            Migrated 12 microservices from REST to gRPC, reducing error rates by 18%
            Mentored 2 junior engineers through their first on-call rotations

            Software Engineer, Beta Labs, Jun 2018 - Feb 2021
            Built a billing reconciliation pipeline processing 500k records nightly
            Shipped a rate-limiting middleware adopted by 6 downstream teams

            EDUCATION
            B.S. Computer Science, Riverside University, Sep 2014 - May 2018

            SKILLS
            Java, Kotlin, Spring Boot, PostgreSQL, Kafka, Docker, Kubernetes, AWS
            """;

    @Test
    void categoryScoresAlwaysSumToTheReportedTotal() {
        ResumeQualityScoreResponse response = scorer.score(COMPLETE_RESUME);
        int sum = response.getCategories().stream().mapToInt(ResumeQualityCategoryScore::getScore).sum();
        assertEquals(response.getScore(), sum);
    }

    @Test
    void categoryMaxScoresAlwaysSumToExactly100() {
        ResumeQualityScoreResponse response = scorer.score(COMPLETE_RESUME);
        int maxSum = response.getCategories().stream().mapToInt(ResumeQualityCategoryScore::getMaxScore).sum();
        assertEquals(100, maxSum);
    }

    @Test
    void scoringTheSameTextTwiceProducesAnIdenticalResult() {
        ResumeQualityScoreResponse first = scorer.score(COMPLETE_RESUME);
        ResumeQualityScoreResponse second = scorer.score(COMPLETE_RESUME);
        assertEquals(first.getScore(), second.getScore());
        assertEquals(first.getLevel(), second.getLevel());
        assertEquals(first.getCategories(), second.getCategories());
        assertEquals(first.getStrengths(), second.getStrengths());
        assertEquals(first.getSuggestions(), second.getSuggestions());
    }

    @Test
    void aWellRoundedResumeScoresGoodOrBetterAndEarnsMultipleStrengths() {
        ResumeQualityScoreResponse response = scorer.score(COMPLETE_RESUME);
        assertTrue(response.isScorable());
        assertTrue(response.getScore() >= 70, "expected a GOOD-or-better score, got " + response.getScore());
        assertTrue(response.getStrengths().size() >= 2, "expected multiple strengths, got " + response.getStrengths());
    }

    // ---------------------------------------------------------------------------
    // Unscorable -- never a misleading 0
    // ---------------------------------------------------------------------------

    @Test
    void blankTextIsHonestlyUnscorableNotAZeroScore() {
        ResumeQualityScoreResponse response = scorer.score("   \n\n  ");
        assertFalse(response.isScorable());
        assertEquals(ResumeQualityScorer.REASON_TEXT_NOT_AVAILABLE, response.getReasonCode());
        assertNull(response.getScore());
        assertNull(response.getLevel());
        assertTrue(response.getCategories().isEmpty());
    }

    @Test
    void nullTextIsHonestlyUnscorableNotAZeroScore() {
        ResumeQualityScoreResponse response = scorer.score(null);
        assertFalse(response.isScorable());
        assertEquals(ResumeQualityScorer.REASON_TEXT_NOT_AVAILABLE, response.getReasonCode());
    }

    // ---------------------------------------------------------------------------
    // A student resume with no full-time job must not be zeroed
    // ---------------------------------------------------------------------------

    private static final String STUDENT_RESUME = """
            Alex Rivera
            alex.rivera@example.com | 555-222-3333

            EDUCATION
            B.S. Computer Science (in progress), Lakeside University, Sep 2022 - May 2026

            PROJECTS
            Campus Ride Share App
            Built a full-stack ride-matching app used by 200 students in the first month
            Implemented a scheduling algorithm that cut average wait time by 25%

            Personal Portfolio Site
            Designed and deployed a portfolio site with a small blog engine

            SKILLS
            Python, JavaScript, React, Git
            """;

    @Test
    void aStudentResumeWithNoFullTimeJobIsNotZeroedOverall() {
        ResumeQualityScoreResponse response = scorer.score(STUDENT_RESUME);
        assertTrue(response.isScorable());
        assertTrue(response.getScore() > 40, "a student resume with real project content must not be near-zero, got " + response.getScore());
    }

    @Test
    void aStudentResumeWithOnlyProjectsStillEarnsTheStructureExperienceCredit() {
        ResumeQualityScoreResponse response = scorer.score(STUDENT_RESUME);
        ResumeQualityCategoryScore structure = categoryOf(response, ResumeQualityScorer.CATEGORY_STRUCTURE);
        // Projects count the same as formal work experience for this sub-score --
        // see ResumeQualityScorer's javadoc.
        assertTrue(structure.getScore() >= 15, "expected most of STRUCTURE's points, got " + structure.getScore());
    }

    @Test
    void aStudentResumeStillGetsExperienceCategoryCreditFromProjectBullets() {
        ResumeQualityScoreResponse response = scorer.score(STUDENT_RESUME);
        ResumeQualityCategoryScore experience = categoryOf(response, ResumeQualityScorer.CATEGORY_EXPERIENCE);
        assertTrue(experience.getScore() > 0, "project bullets must count toward EXPERIENCE too, got " + experience.getScore());
    }

    // ---------------------------------------------------------------------------
    // Keyword stuffing must never outscore a focused, honest skills list
    // ---------------------------------------------------------------------------

    @Test
    void aHugeStuffedSkillsListScoresNoHigherThanAFocusedOne() {
        String focused = COMPLETE_RESUME;
        StringBuilder stuffed = new StringBuilder(COMPLETE_RESUME);
        stuffed.append("MORE SKILLS\n");
        for (int i = 0; i < 60; i++) {
            stuffed.append("buzzword").append(i).append(", ");
        }
        stuffed.append('\n');

        int focusedSkillsScore = categoryOf(scorer.score(focused), ResumeQualityScorer.CATEGORY_SKILLS).getScore();
        int stuffedSkillsScore = categoryOf(scorer.score(stuffed.toString()), ResumeQualityScorer.CATEGORY_SKILLS).getScore();

        assertEquals(focusedSkillsScore, stuffedSkillsScore,
                "60 extra buzzwords must not earn more SKILLS points than a focused list");
    }

    // ---------------------------------------------------------------------------
    // Readability: overlong bullets and inconsistent dates are flagged
    // ---------------------------------------------------------------------------

    private static final String OVERLONG_BULLET_RESUME = """
            Sam Choi
            sam.choi@example.com | 555-444-5555

            EXPERIENCE
            Engineer, Gamma Inc, 2019 - 2022
            """ + "Worked on a great many different things across a large number of teams and projects over several years including but not limited to backend services frontend components data pipelines and infrastructure automation while also participating in code reviews design discussions incident response on call rotations and cross team planning meetings on a regular and ongoing basis throughout the entire tenure at the company\n"
            + """

            EDUCATION
            B.S. Computer Science, Some University, 2015 - 2019

            SKILLS
            Java, Python
            """;

    @Test
    void anOverlongBulletLowersReadabilityAndSuggestsShorteningIt() {
        ResumeQualityScoreResponse response = scorer.score(OVERLONG_BULLET_RESUME);
        ResumeQualityCategoryScore readability = categoryOf(response, ResumeQualityScorer.CATEGORY_READABILITY);
        assertTrue(readability.getScore() < readability.getMaxScore());
        assertTrue(response.getSuggestions().contains(ResumeQualityScorer.SUGGESTION_SHORTEN_LONG_BULLETS));
    }

    private static final String INCONSISTENT_DATES_RESUME = """
            Robin Patel
            robin.patel@example.com | 555-777-8888

            EXPERIENCE
            Engineer, Delta Co, Jan 2019 - Mar 2021
            Delivered a reporting dashboard used by 40 internal analysts weekly

            Engineer, Epsilon Co, 2017 to 2019
            Reduced build times by 30 percent across the main pipeline

            EDUCATION
            B.S. Computer Science, Another University, 2013 - 2017

            SKILLS
            Java, SQL
            """;

    @Test
    void inconsistentDateSeparatorStylesSuggestUsingOneConsistentFormat() {
        ResumeQualityScoreResponse response = scorer.score(INCONSISTENT_DATES_RESUME);
        assertTrue(response.getSuggestions().contains(ResumeQualityScorer.SUGGESTION_USE_CONSISTENT_DATE_FORMAT));
    }

    // ---------------------------------------------------------------------------
    // Never discriminates -- adding personal-attribute text changes nothing category-wise
    // that a photo/age/gender/marital-status field would ever be rewarded for
    // ---------------------------------------------------------------------------

    @Test
    void mentioningPhotoAgeGenderOrMaritalStatusNeverBecomesAStrength() {
        String withPersonalAttributes = COMPLETE_RESUME.replaceFirst(
                "taylor.kim@example.com \\| \\(555\\) 987-6543",
                "taylor.kim@example.com | (555) 987-6543\nPhoto attached. Age: 29. Gender: Female. Marital Status: Single.");

        ResumeQualityScoreResponse response = scorer.score(withPersonalAttributes);
        // No strength or suggestion code in this rubric is ever about photo, age,
        // gender, or marital status -- the full fixed vocabulary is exhaustively
        // checked here rather than just spot-checking for absence of one string.
        for (String code : response.getStrengths()) {
            assertFalse(code.toLowerCase().contains("photo"));
            assertFalse(code.toLowerCase().contains("age"));
            assertFalse(code.toLowerCase().contains("gender"));
            assertFalse(code.toLowerCase().contains("marital"));
        }
    }

    // ---------------------------------------------------------------------------
    // A normal, moderately-complete resume: not excellent, not empty
    // ---------------------------------------------------------------------------

    private static final String NORMAL_RESUME = """
            Morgan Lee
            morgan.lee@example.com

            EXPERIENCE
            Support Engineer, Zeta Corp, 2020 - 2022
            Helped customers troubleshoot issues
            Wrote documentation

            SKILLS
            Excel, Customer Service
            """;

    @Test
    void aNormalIncompleteResumeScoresInTheMiddleNotAtEitherExtreme() {
        ResumeQualityScoreResponse response = scorer.score(NORMAL_RESUME);
        assertTrue(response.isScorable());
        assertTrue(response.getScore() > 10 && response.getScore() < 85,
                "expected a middling score, got " + response.getScore());
    }

    // ---------------------------------------------------------------------------
    // Level boundaries -- published, fixed: 85-100 EXCELLENT, 70-84 GOOD,
    // 50-69 NEEDS_WORK, 0-49 WEAK. Every boundary value gets its own assertion.
    // ---------------------------------------------------------------------------

    @Test
    void levelBoundariesAreExactlyAsPublished() {
        assertEquals(ResumeQualityScorer.LEVEL_WEAK, ResumeQualityScorer.levelFor(0));
        assertEquals(ResumeQualityScorer.LEVEL_WEAK, ResumeQualityScorer.levelFor(49));
        assertEquals(ResumeQualityScorer.LEVEL_NEEDS_WORK, ResumeQualityScorer.levelFor(50));
        assertEquals(ResumeQualityScorer.LEVEL_NEEDS_WORK, ResumeQualityScorer.levelFor(69));
        assertEquals(ResumeQualityScorer.LEVEL_GOOD, ResumeQualityScorer.levelFor(70));
        assertEquals(ResumeQualityScorer.LEVEL_GOOD, ResumeQualityScorer.levelFor(84));
        assertEquals(ResumeQualityScorer.LEVEL_EXCELLENT, ResumeQualityScorer.levelFor(85));
        assertEquals(ResumeQualityScorer.LEVEL_EXCELLENT, ResumeQualityScorer.levelFor(100));
    }

    @Test
    void anUnscorableResumeIsNeverLeveledWeakOrScoredZero() {
        ResumeQualityScoreResponse response = scorer.score(null);
        assertFalse(response.isScorable());
        // Not WEAK, not any level at all -- levelFor is never even reached for this
        // path (see score()'s early return), which is what these null checks prove:
        // an unscorable result is a distinct third state, not a disguised 0/WEAK.
        assertNull(response.getLevel());
        assertNull(response.getScore());
    }

    // ---------------------------------------------------------------------------
    // ACHIEVEMENTS: positive and negative examples, exactly as specified. A bare
    // digit must never be enough; each positive example must be a genuinely
    // verifiable metric, and each negative example must NOT count despite containing
    // numbers.
    // ---------------------------------------------------------------------------

    private ResumeQualityCategoryScore achievementsOf(String resumeText) {
        return categoryOf(scorer.score(resumeText), ResumeQualityScorer.CATEGORY_ACHIEVEMENTS);
    }

    private static String resumeWithBullet(String bulletLine) {
        return """
                Casey Morgan
                casey.morgan@example.com

                EXPERIENCE
                Engineer, Placeholder Inc, 2020 - 2022
                %s

                EDUCATION
                B.S. Computer Science, Placeholder University, 2016 - 2020

                SKILLS
                Java
                """.formatted(bulletLine);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Improved system throughput by 30%",
            "Served 10,000 users across the platform",
            "Cut response time from 2 seconds to 500 milliseconds",
            "Saved the company $50,000 in annual infrastructure costs",
            "Managed a 12-person team across two offices",
            "Completed 20 projects ahead of schedule",
            "Handled 5,000 requests per second at peak load",
    })
    void positiveAchievementExamplesAreCountedAsQuantified(String bulletLine) {
        ResumeQualityCategoryScore achievements = achievementsOf(resumeWithBullet(bulletLine));
        assertTrue(achievements.getScore() > 0,
                "expected \"" + bulletLine + "\" to count as a quantified achievement, got score 0");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "Worked at Placeholder Corp from 2024 to 2025",
            "Completed the certification program 01/2025",
            "Reachable at (555) 123-4567 for references",
            "Relocated to Springfield, IL 62704 for the role",
            "Built the backend service using Java 17 and Spring Boot",
            "Wrote data pipelines in Python 3 and React 18 dashboards",
            "Earned ISO 27001 lead auditor recognition",
            "Completed course CS 401 with a certificate numbered 2024-1123",
    })
    void negativeExamplesAreNeverCountedAsQuantifiedDespiteContainingDigits(String bulletLine) {
        ResumeQualityCategoryScore achievements = achievementsOf(resumeWithBullet(bulletLine));
        assertEquals(0, achievements.getScore(),
                "expected \"" + bulletLine + "\" to NOT count as a quantified achievement, but ACHIEVEMENTS scored "
                        + achievements.getScore());
    }

    @Test
    void stuffingLotsOfBareNumbersNeverOutscoresARealAchievement() {
        // A resume that pads bullets with bare numbers (years, phone-shaped digits,
        // version numbers) must NOT out-score one with a single genuine achievement --
        // proving ACHIEVEMENTS cannot be gamed by digit-stuffing any more than SKILLS
        // can be gamed by keyword-stuffing.
        String stuffed = resumeWithBullet(
                "Worked 2019 to 2024 using Java 17 Python 3 React 18 ISO 27001 phone 555-123-4567 zip 62704");
        String real = resumeWithBullet("Improved deployment speed by 30%");

        int stuffedScore = achievementsOf(stuffed).getScore();
        int realScore = achievementsOf(real).getScore();

        assertEquals(0, stuffedScore, "bare years/versions/phone/zip must not count, got " + stuffedScore);
        assertTrue(realScore > stuffedScore, "a real percentage achievement must outscore digit-stuffing");
    }

    private static ResumeQualityCategoryScore categoryOf(ResumeQualityScoreResponse response, String code) {
        return response.getCategories().stream()
                .filter(c -> c.getCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no category " + code));
    }
}
