package aijob.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class AdzunaClientConfig {

    public static final String BASE_URL = "https://api.adzuna.com";

    @Bean
    public RestClient adzunaRestClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return builder.clone()
                .baseUrl(BASE_URL)
                .requestFactory(factory)
                .build();
    }
}
