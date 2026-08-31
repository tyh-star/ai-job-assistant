package aijob.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

@Configuration
public class JSearchClientConfig {

    public static final String DEFAULT_BASE_URL = "https://api.openwebninja.com";
    public static final String BASE_URL_PROPERTY = "JSEARCH_API_BASE_URL";
    public static final String KEY_PROPERTY = "JSEARCH_API_KEY";
    public static final String PRIMARY_PROPERTY = "JOB_SEARCH_PRIMARY_PROVIDER";
    public static final String DEFAULT_PRIMARY = "JSEARCH";
    public static final String COUNTRY = "ca";
    public static final String LANGUAGE = "en";
    public static final String SEARCH_PATH = "/jsearch/search-v2";

    @Bean
    Clock jobSearchClock() {
        return Clock.systemUTC();
    }

    @Bean
    public RestClient jsearchRestClient(RestClient.Builder builder, Environment environment) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return builder.clone()
                .baseUrl(baseUrl(environment))
                .requestFactory(factory)
                .build();
    }

    public static String baseUrl(Environment environment) {
        String value = environment.getProperty(BASE_URL_PROPERTY);
        if (value == null || value.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        try {
            URI uri = URI.create(value.strip());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                return DEFAULT_BASE_URL;
            }
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (!host.matches("[a-z0-9.-]+") || host.contains("..")) {
                return DEFAULT_BASE_URL;
            }
            String path = uri.getPath();
            if (path == null || path.isBlank() || "/".equals(path)) {
                return "https://" + host;
            }
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return "https://" + host + path;
        } catch (IllegalArgumentException ex) {
            return DEFAULT_BASE_URL;
        }
    }
}
