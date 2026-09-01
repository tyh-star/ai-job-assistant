package aijob.collector;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.time.Duration;

/**
 * HTTP clients for collection.
 *
 * <p>Each client's base URL pins the provider's official host, and redirects are
 * disabled, so a board cannot bounce the collector to another address. The
 * User-Agent carries a contact address so board operators can reach us.
 */
@Configuration
@EnableConfigurationProperties(JobCollectorProperties.class)
public class JobCollectorClientConfig {

    public static final String GREENHOUSE_BASE_URL = "https://boards-api.greenhouse.io/v1/boards";
    public static final String LEVER_BASE_URL = "https://api.lever.co/v0/postings";

    @Bean("greenhouseCollectorClient")
    public RestClient greenhouseCollectorClient(RestClient.Builder builder,
                                                JobCollectorProperties properties) {
        return client(builder, properties, GREENHOUSE_BASE_URL);
    }

    @Bean("leverCollectorClient")
    public RestClient leverCollectorClient(RestClient.Builder builder,
                                           JobCollectorProperties properties) {
        return client(builder, properties, LEVER_BASE_URL);
    }

    private static RestClient client(RestClient.Builder builder,
                                     JobCollectorProperties properties,
                                     String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        Duration timeout = Duration.ofSeconds(properties.getRequestTimeoutSeconds());
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return builder.clone()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent(properties))
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }

    static String userAgent(JobCollectorProperties properties) {
        String contact = properties.getContactEmail();
        if (contact == null || contact.isBlank()) {
            return properties.getUserAgent();
        }
        return properties.getUserAgent() + " (+mailto:" + contact + ")";
    }
}
