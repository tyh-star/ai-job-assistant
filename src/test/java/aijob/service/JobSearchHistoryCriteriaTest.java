package aijob.service;

import aijob.dto.RecordJobSearchHistoryRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobSearchHistoryCriteriaTest {

    @Test
    void emptyAndDefaultSortAreNotRecorded() {
        assertTrue(JobSearchHistoryCriteria.normalize(new RecordJobSearchHistoryRequest(
                "", null, null, null, null, null, null, "RELEVANCE")).isEmpty());
        assertTrue(JobSearchHistoryCriteria.normalize(null).isEmpty());
    }

    @Test
    void leadingTrailingAndCollapsedWhitespaceAreNormalizedInKeywords() {
        JobSearchHistoryCriteria.Normalized a = JobSearchHistoryCriteria.normalize(
                new RecordJobSearchHistoryRequest("  Java   Backend ", null, null, null, null, null, null, null));
        JobSearchHistoryCriteria.Normalized b = JobSearchHistoryCriteria.normalize(
                new RecordJobSearchHistoryRequest("java backend", null, null, null, null, null, null, null));
        assertEquals("java backend", a.keywords());
        assertEquals(a.criteriaHash(), b.criteriaHash());
    }

    @Test
    void enumsAndCountryCodesAreUpperCased() {
        JobSearchHistoryCriteria.Normalized n = JobSearchHistoryCriteria.normalize(
                new RecordJobSearchHistoryRequest(
                        "java", "Toronto", "ca", "us", "remote", "full_time", 7, "date"));
        assertEquals("CA", n.cityCountry());
        assertEquals("US", n.country());
        assertEquals("REMOTE", n.workMode());
        assertEquals("FULL_TIME", n.employmentType());
        assertEquals("DATE", n.sortBy());
        assertEquals(
                JobSearchHistoryCriteria.normalize(new RecordJobSearchHistoryRequest(
                        "java", "Toronto", "CA", "US", "REMOTE", "FULL_TIME", 7, "DATE")).criteriaHash(),
                n.criteriaHash());
    }

    @Test
    void torontoAndTorontoAreTheSameSearch() {
        String upper = JobSearchHistoryCriteria.normalize(new RecordJobSearchHistoryRequest(
                "java", "Toronto", "CA", null, null, null, null, null)).criteriaHash();
        String lower = JobSearchHistoryCriteria.normalize(new RecordJobSearchHistoryRequest(
                "java", "toronto", "CA", null, null, null, null, null)).criteriaHash();
        assertEquals(upper, lower);
        assertEquals("toronto", JobSearchHistoryCriteria.normalize(new RecordJobSearchHistoryRequest(
                "java", "Toronto", "CA", null, null, null, null, null)).city());
    }

    @Test
    void pageNumberIsNotPartOfTheHashBecauseTheRequestHasNoPageField() {
        String firstPage = JobSearchHistoryCriteria.normalize(new RecordJobSearchHistoryRequest(
                "java", "Toronto", "CA", null, "REMOTE", null, null, null)).criteriaHash();
        String later = JobSearchHistoryCriteria.normalize(new RecordJobSearchHistoryRequest(
                "java", "Toronto", "CA", null, "REMOTE", null, null, null)).criteriaHash();
        assertEquals(firstPage, later);
        assertFalse(firstPage.isBlank());
    }

    @Test
    void differentCitiesStillProduceDifferentHashes() {
        String toronto = JobSearchHistoryCriteria.normalize(new RecordJobSearchHistoryRequest(
                "java", "Toronto", "CA", null, null, null, null, null)).criteriaHash();
        String ottawa = JobSearchHistoryCriteria.normalize(new RecordJobSearchHistoryRequest(
                "java", "Ottawa", "CA", null, null, null, null, null)).criteriaHash();
        assertNotEquals(toronto, ottawa);
    }

    @Test
    void repeatedNormalizedKeywordsShareAHash() {
        String a = JobSearchHistoryCriteria.normalize(new RecordJobSearchHistoryRequest(
                "  Java   Backend ", "Toronto", "ca", null, null, null, null, "RELEVANCE")).criteriaHash();
        String b = JobSearchHistoryCriteria.normalize(new RecordJobSearchHistoryRequest(
                "java backend", "Toronto", "CA", null, null, null, null, null)).criteriaHash();
        assertEquals(a, b);
        assertFalse(a.isBlank());
    }
}
