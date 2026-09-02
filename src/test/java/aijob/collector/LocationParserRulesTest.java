package aijob.collector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The location rules, against the strings that actually came back from ten real boards.
 *
 * <p>Every case below is a location that was collected on 2026-09-02, not an invented
 * one. Where a rule declines to answer, the test asserts the decline: a parser that
 * guesses is worse than one that says nothing, because a wrong country hides a posting
 * from a filtered search without telling anyone.
 */
class LocationParserRulesTest {

    // ---------------------------------------------------------------------------
    // country
    // ---------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({
            // A trailing US state code. Countries in these boards are always spelled out,
            // so a two-letter trailing token is a state.
            "'Chicago, IL',                US",
            "'New York, NY',               US",
            "'Pittsburgh, PA',             US",
            "'Arlington, VA',              US",
            "'Austin, TX',                 US",
            // Spelled-out countries, including the sub-national names of the UK.
            "'London, England',            GB",
            "'London, United Kingdom',     GB",
            "'Berlin, Germany',            DE",
            "'Tokyo, Japan',               JP",
            // Three segments, country last -- and a Canadian province in the middle that
            // must not derail the scan.
            "'Toronto, ON, Canada',        CA",
            "'Remote - Canada',            CA",
            "'California, United States',  US",
            // Whole-string country names the first ten boards published and the
            // dictionary had no entry for.
            "'Greece',                     GR",
            "'Romania',                    RO",
            "'Saudi Arabia',               SA",
            "'Hungary',                    HU",
            "'Costa Rica',                 CR",
            "'The Netherlands',            NL",
            // A state spelled out is unambiguous where its code is not.
            "'Remote - California',        US",
            "'Remote - Illinois',          US",
            // "Remote <country>": the country is not in its own comma segment at all.
            "'Remote US',                  US",
            "'Remote UK',                  GB",
            "'Remote Belgium',             BE",
            "'Remote Canada',              CA",
            "'Remote Germany',             DE",
            "'Remote India',               IN",
            "'Remote Netherlands',         NL",
            "'Remote - United States',     US",
            // A parenthetical aside is commentary, not a place, and must not block the
            // state code in front of it.
            "'Palo Alto, CA (Open to US-based Remote)', ",
            // Nothing but a work arrangement names no country.
            "'Hybrid',                     ",
            "'In-Office',                  ",
            "'Remote',                     ",
            // A bare city is not a country.
            "'Austin',                     ",
            "'Berlin',                     ",
    })
    void countryCode(String location, String expected) {
        assertEquals(expected, LocationParser.countryCode(location),
                "country for: " + location);
    }

    @Test
    void caIsLeftUnknownBecauseTheCodeAloneCannotDecide() {
        // California or Canada. The city settles it and this parser has no gazetteer, so
        // it declines -- deliberately, and at a measured cost: 28 of 250 sampled postings.
        // Filed under the wrong country a posting is invisible to the right search and
        // wrongly present in another; left unknown it is merely not filterable yet.
        assertNull(LocationParser.countryCode("Palo Alto, CA"));
        assertNull(LocationParser.countryCode("Los Angeles, CA"));
        // Canada spelled out is not ambiguous at all.
        assertEquals("CA", LocationParser.countryCode("Toronto, Canada"));
        assertEquals("CA", LocationParser.countryCode("Remote Canada"));
    }

    @Test
    void georgiaIsLeftUnknownForTheSameReasonAsCa() {
        // A US state and a country (GE). The name alone does not say which, so it is out
        // of the state-name set even though every other state is in it.
        assertNull(LocationParser.countryCode("Georgia"));
        assertNull(LocationParser.countryCode("Remote - Georgia"));
        // Qualified, it resolves: the country name settles it.
        assertEquals("US", LocationParser.countryCode("Atlanta, GA"));
    }

    @Test
    void aLoneStateCodeNamesNoCountry() {
        // "NY" on its own could be anything the board meant; a state code only carries
        // its country when it follows a place.
        assertNull(LocationParser.countryCode("NY"));
    }

    // ---------------------------------------------------------------------------
    // city
    // ---------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({
            "'Chicago, IL',                                  Chicago",
            "'London, England',                              London",
            "'Palo Alto, CA',                                'Palo Alto'",
            // The work-arrangement prefix used to end up inside the city: six postings
            // were stored with the city "Hybrid - San Francisco".
            "'Hybrid - San Francisco, New York City, Austin','San Francisco'",
            "'Hybrid - London',                              ",
            // No comma, so no "City, Region" shape to read.
            "'Remote US',                                    ",
            "'Hybrid',                                       ",
            "'In-Office',                                    ",
            // A country in the first segment is not a city.
            "'Remote - United States',                       ",
    })
    void city(String location, String expected) {
        assertEquals(expected, LocationParser.city(location), "city for: " + location);
    }

    @Test
    void onlyTheFirstOfSeveralCitiesIsKept() {
        // A real limitation, asserted so it stays visible. The schema holds one city per
        // posting; duplicating the posting per city would invent jobs that do not exist,
        // so a search for the second or third city does not find this posting.
        String multi = "Hybrid - San Francisco, New York City, Austin";
        assertEquals("San Francisco", LocationParser.city(multi));
        assertEquals("New York City", LocationParser.region(multi));
        // And the raw text is kept whole elsewhere, so nothing is lost from the record.
    }

    @Test
    void aTwoSitePostingKeepsOnlyItsFirstSite() {
        // "New York, NY; Pittsburgh, PA" -- one posting, two offices.
        assertEquals("New York", LocationParser.city("New York, NY; Pittsburgh, PA"));
        assertEquals("US", LocationParser.countryCode("New York, NY; Pittsburgh, PA"));
    }

    // ---------------------------------------------------------------------------
    // the prefix stripper itself
    // ---------------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource({
            "'Hybrid - San Francisco', 'San Francisco'",
            "'Remote - United States', 'United States'",
            "'Remote US',              'US'",
            "'Onsite: Berlin',         'Berlin'",
            "'In-Office London',       'London'",
            "'San Francisco',          'San Francisco'",
            // Not a prefix: the word appears inside a place name rather than in front.
            "'Remoteville',            'Remoteville'",
    })
    void stripWorkMode(String input, String expected) {
        assertEquals(expected, LocationParser.stripWorkMode(input));
    }
}
