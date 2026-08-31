package aijob.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class JobSearchProperties {

    public static final String MAX_PAGES_PROPERTY = "JSEARCH_MAX_PAGES_PER_SEARCH";
    public static final int DEFAULT_MAX_PAGES = 10;

    private final int maxPagesPerSearch;

    @Autowired
    public JobSearchProperties(Environment environment) {
        this(parse(environment == null ? null : environment.getProperty(MAX_PAGES_PROPERTY)));
    }

    public JobSearchProperties(int maxPagesPerSearch) {
        this.maxPagesPerSearch = maxPagesPerSearch < 1 ? DEFAULT_MAX_PAGES : maxPagesPerSearch;
    }

    public static JobSearchProperties defaults() {
        return new JobSearchProperties(DEFAULT_MAX_PAGES);
    }

    public int maxPagesPerSearch() {
        return maxPagesPerSearch;
    }

    private static int parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MAX_PAGES;
        }
        try {
            return Integer.parseInt(raw.strip());
        } catch (NumberFormatException ex) {
            return DEFAULT_MAX_PAGES;
        }
    }
}
