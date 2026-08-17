package aijob.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlTextExtractorTest {

    /**
     * Exact Greenhouse Board API content prefix from Airbnb job 7995153
     * (Acquisition Manager). Tags are entity-encoded; there are no raw '&lt;'.
     */
    private static final String AIRBNB_ENCODED_PREFIX = """
            &lt;div class=&quot;content-intro&quot;&gt;&lt;p&gt;&lt;span style=&quot;font-family: helvetica, arial, sans-serif; font-size: 12pt;&quot;&gt;Airbnb was born in 2007 when two hosts welcomed three guests to their San Francisco home, and has since grown to over 5 million hosts who have welcomed over 2 billion guest arrivals in almost every country across the globe.&lt;/span&gt;&lt;/p&gt;&lt;/div&gt;&lt;p&gt;&lt;span style=&quot;font-family: arial, helvetica, sans-serif; color: #000000;&quot;&gt;&lt;strong&gt;The Community You Will Join:&lt;/strong&gt;&lt;/span&gt;&lt;/p&gt;
            &lt;p&gt;The Homes team plays a key role in shaping the Airbnb stays experience . As a multidisciplinary team, we partner with Hosts to conceptualize and develop high-quality, repeatable, and commercially viable offerings that align with our brand values.&amp;nbsp;&lt;/p&gt;
            """;

    private static final String AIRBNB_ENCODED_LIST = """
            &lt;p&gt;&lt;span style=&quot;font-family: arial, helvetica, sans-serif; color: #000000;&quot;&gt;&lt;strong&gt;A Typical Day:&amp;nbsp;&lt;/strong&gt;&lt;/span&gt;&lt;/p&gt;
            &lt;ul&gt;
            &lt;li&gt;Prospect and onboard new, high quality professional hosts in your assigned territory, continuously building and managing a strong pipeline&lt;/li&gt;
            &lt;li&gt;Create and deploy scaled strategies to effectively acquire Professional Hosts in your region&lt;/li&gt;
            &lt;li&gt;Represent Airbnb in external meetings with Professional Hosts, showing up as a credible and knowledgeable market expert&lt;/li&gt;
            &lt;/ul&gt;
            """;

    @Test
    void toPlainText_realAirbnbEncodedHtml_stripsTagsKeepsParagraphsAndLists() {
        String text = HtmlTextExtractor.toPlainText(AIRBNB_ENCODED_PREFIX + AIRBNB_ENCODED_LIST);

        assertNoMarkup(text);
        assertTrue(text.contains("Airbnb was born in 2007"), text);
        assertTrue(text.contains("The Community You Will Join:"), text);
        assertTrue(text.contains("The Homes team plays a key role"), text);
        assertTrue(text.contains("A Typical Day:"), text);
        assertTrue(text.contains("Prospect and onboard new, high quality professional hosts"), text);
        assertTrue(text.contains("Create and deploy scaled strategies"), text);
        assertTrue(text.contains("Represent Airbnb in external meetings"), text);

        int intro = text.indexOf("Airbnb was born in 2007");
        int community = text.indexOf("The Community You Will Join:");
        int typicalDay = text.indexOf("A Typical Day:");
        int firstBullet = text.indexOf("Prospect and onboard");
        int secondBullet = text.indexOf("Create and deploy scaled strategies");
        assertTrue(community > intro);
        assertTrue(text.substring(intro, community).contains("\n"));
        assertTrue(typicalDay > community);
        assertTrue(firstBullet > typicalDay);
        assertTrue(text.substring(typicalDay, firstBullet).contains("\n"));
        assertTrue(secondBullet > firstBullet);
        assertTrue(text.substring(firstBullet, secondBullet).contains("\n"));
    }

    @Test
    void toPlainText_realAirbnbFullJobFixture_isPlainText() throws IOException {
        String encoded = readFixture("/greenhouse/airbnb-job-7995153.encoded.html");
        assertTrue(encoded.startsWith("&lt;div"), encoded.substring(0, 40));
        assertTrue(encoded.contains("&lt;li&gt;"), encoded);
        assertFalse(encoded.contains("<div"), "fixture should stay entity-encoded");

        String text = HtmlTextExtractor.toPlainText(encoded);

        assertNoMarkup(text);
        assertTrue(text.contains("Airbnb was born in 2007"), text);
        assertTrue(text.contains("Prospect and onboard new, high quality professional hosts"), text);
        assertTrue(text.contains("\n"), text);
    }

    @Test
    void toPlainText_doubleEncodedGreenhouseHtml_stripsTags() {
        String encoded =
                "&amp;lt;div class=&amp;quot;job&amp;quot;&amp;gt;"
                        + "&amp;lt;p&amp;gt;Build reliable APIs&amp;lt;/p&amp;gt;"
                        + "&amp;lt;ul&amp;gt;&amp;lt;li&amp;gt;Own the job import pipeline&amp;lt;/li&amp;gt;&amp;lt;/ul&amp;gt;"
                        + "&amp;lt;/div&amp;gt;";

        String text = HtmlTextExtractor.toPlainText(encoded);

        assertNoMarkup(text);
        assertTrue(text.contains("Build reliable APIs"), text);
        assertTrue(text.contains("Own the job import pipeline"), text);
        assertTrue(text.contains("\n"), text);
    }

    @Test
    void toPlainText_rawHtml_stillStripsTags() {
        String text = HtmlTextExtractor.toPlainText(
                "<p>Build things</p><ul><li>Item one</li></ul>"
        );

        assertNoMarkup(text);
        assertTrue(text.contains("Build things"), text);
        assertTrue(text.contains("Item one"), text);
        assertTrue(text.contains("\n"), text);
        assertTrue(text.indexOf("Item one") > text.indexOf("Build things"));
    }

    private static void assertNoMarkup(String text) {
        assertFalse(text.contains("<"), text);
        assertFalse(text.contains(">"), text);
        assertFalse(text.contains("</"), text);
        assertFalse(text.contains("class="), text);
        assertFalse(text.contains("style="), text);
        assertFalse(text.contains("&lt;"), text);
        assertFalse(text.contains("&gt;"), text);
        assertFalse(text.contains("&quot;"), text);
        assertFalse(text.contains("&amp;"), text);
    }

    private static String readFixture(String classpath) throws IOException {
        try (InputStream in = HtmlTextExtractorTest.class.getResourceAsStream(classpath)) {
            assertNotNull(in, "missing fixture " + classpath);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
