package aijob.config;

import aijob.freehire.FreeHireRateLimiter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

/**
 * The outbound client to FreeHire's own hosted API (freehire.me/api/v1) — a public,
 * unauthenticated, read-only job search this backend calls on the caller's behalf. The
 * browser never talks to freehire.me directly; every request here is one this Java
 * process makes for itself.
 *
 * <p>Mirrors {@link JSearchClientConfig}'s shape deliberately (same request-factory
 * style, same timeout idiom) so the two external-search integrations read the same way.
 * It adds one thing JSearch's client does not: a response-size cap, applied here at the
 * transport layer via {@link #freeHireSizeCapInterceptor(long)} rather than at each call
 * site, so no future caller of this client can forget it.
 */
@Configuration
public class FreeHireApiConfig {

    public static final String DEFAULT_BASE_URL = "https://freehire.me/api/v1";
    public static final String BASE_URL_PROPERTY = "FREEHIRE_API_BASE_URL";

    /**
     * Identifies this deployment to FreeHire's own logs, per the requirement that
     * outbound calls carry a User-Agent that names the project rather than posing as a
     * browser. The contact email is the operator's, not a user's — this header is sent
     * on every request regardless of who is searching.
     */
    static final String DEFAULT_USER_AGENT =
            "ai-job-assistant/1.0";

    @Bean
    public RestClient freeHireRestClient(RestClient.Builder builder, Environment environment) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                // A read-only GET client has no business following a redirect to a host
                // this configuration never validated; a changed base URL is a code change,
                // not something the response is allowed to steer.
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(connectTimeout(environment));
        factory.setReadTimeout(readTimeout(environment));

        String userAgent = environment.getProperty("aijob.freehire.user-agent", DEFAULT_USER_AGENT);

        return builder.clone()
                .baseUrl(baseUrl(environment))
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .requestInterceptor(freeHireSizeCapInterceptor(maxResponseBytes(environment)))
                .build();
    }

    private static Duration connectTimeout(Environment environment) {
        return Duration.ofSeconds(
                environment.getProperty("aijob.freehire.connect-timeout-seconds", Long.class, 5L));
    }

    private static Duration readTimeout(Environment environment) {
        return Duration.ofSeconds(
                environment.getProperty("aijob.freehire.read-timeout-seconds", Long.class, 10L));
    }

    /**
     * Per-minute request BUDGET for {@code GET /agent/jobs/search}, shared by every
     * caller of this backend process -- see {@link FreeHireRateLimiter}'s javadoc for
     * why this is a distinct mechanism from {@link FreeHireClient}'s concurrency
     * {@code Semaphore}. Default of 180/min is deliberately below FreeHire's own
     * confirmed IP-keyed limit of 300/min on this endpoint (see
     * {@link FreeHireClient}'s javadoc), leaving headroom rather than budgeting right up
     * to the documented ceiling. Reuses the same {@code jobSearchClock} bean every other
     * clock-driven component in this codebase is wired to, so a test that swaps that
     * bean for a controllable clock affects this limiter the same way.
     */
    @Bean
    public FreeHireRateLimiter freeHireSearchRateLimiter(
            @Value("${aijob.freehire.rate-limit.search-per-minute:180}") int budgetPerMinute,
            @Qualifier("jobSearchClock") Clock clock) {
        return new FreeHireRateLimiter(budgetPerMinute, clock);
    }

    /**
     * Per-minute request BUDGET for {@code GET /jobs/{slug}} (detail reads). FreeHire
     * has not published a separate documented number for this endpoint the way it has
     * for search's 300/min (confirmed live via {@code X-RateLimit-*} headers) -- this
     * default is a deliberately conservative, UNCONFIRMED guess, not a documented limit,
     * and is called out as such here rather than presented as verified. Configurable so
     * an operator can tighten or relax it without a code change once real traffic
     * patterns (or a published FreeHire number) are known.
     */
    @Bean
    public FreeHireRateLimiter freeHireReadRateLimiter(
            @Value("${aijob.freehire.rate-limit.read-per-minute:120}") int budgetPerMinute,
            @Qualifier("jobSearchClock") Clock clock) {
        return new FreeHireRateLimiter(budgetPerMinute, clock);
    }

    private static long maxResponseBytes(Environment environment) {
        // 5 MiB: a page of search results (agent search rehydrates full descriptions,
        // up to 100 per page per FreeHire's own docs) or one job detail, generously
        // bounded — never the multi-hundred-thousand-row catalog this deployment must
        // not try to mirror in one response.
        return environment.getProperty("aijob.freehire.max-response-bytes", Long.class, 5L * 1024 * 1024);
    }

    /**
     * Validates the configured base URL against the same rules
     * {@link JSearchClientConfig#baseUrl} applies: https only, a real host, no embedded
     * credentials. An invalid value falls back to the real FreeHire URL rather than
     * silently sending requests to an unvalidated host.
     *
     * <p>The one intentional relaxation: {@code http://} is accepted for loopback hosts
     * (127.0.0.1 / localhost), because every test that exercises this client points it at
     * a local fixture server, and this is the only seam that can reach it. No production
     * value can ever resolve to loopback by accident.
     */
    public static String baseUrl(Environment environment) {
        String value = environment.getProperty(BASE_URL_PROPERTY);
        if (value == null || value.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        try {
            URI uri = URI.create(value.strip());
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null) {
                return DEFAULT_BASE_URL;
            }
            String lowerHost = host.toLowerCase(Locale.ROOT);
            boolean loopback = lowerHost.equals("127.0.0.1") || lowerHost.equals("localhost");
            String scheme = uri.getScheme();
            boolean schemeOk = "https".equalsIgnoreCase(scheme)
                    || (loopback && "http".equalsIgnoreCase(scheme));
            if (!schemeOk) {
                return DEFAULT_BASE_URL;
            }
            if (!loopback && (!lowerHost.matches("[a-z0-9.-]+") || lowerHost.contains(".."))) {
                return DEFAULT_BASE_URL;
            }
            String path = uri.getPath();
            String authority = scheme + "://" + host + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
            if (path == null || path.isBlank() || "/".equals(path)) {
                return authority;
            }
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return authority + path;
        } catch (IllegalArgumentException ex) {
            return DEFAULT_BASE_URL;
        }
    }

    /**
     * Caps every response this client reads to {@code maxBytes}, regardless of what
     * {@code Content-Length} claims (a chunked or lying response has none, or a false
     * one). A response over the cap fails the read with an {@link IOException}, which
     * {@code RestClient} surfaces as a {@code ResourceAccessException} — the same
     * exception class a connect/read timeout produces, so one catch site in
     * {@code FreeHireClient} handles both without the cap needing its own exception type.
     */
    public static ClientHttpRequestInterceptor freeHireSizeCapInterceptor(long maxBytes) {
        return (HttpRequest request, byte[] body, ClientHttpRequestExecution execution) -> {
            ClientHttpResponse response = execution.execute(request, body);
            return new SizeCappedClientHttpResponse(response, maxBytes);
        };
    }

    /** Delegates everything except {@link #getBody()}, which it wraps with a hard cap. */
    private record SizeCappedClientHttpResponse(ClientHttpResponse delegate, long maxBytes)
            implements ClientHttpResponse {

        @Override
        public InputStream getBody() throws IOException {
            return new CappedInputStream(delegate.getBody(), maxBytes);
        }

        @Override
        public org.springframework.http.HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public void close() {
            delegate.close();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }
    }

    /** Throws once more than {@code maxBytes} has been read from the wrapped stream. */
    private static final class CappedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long read;

        CappedInputStream(InputStream in, long maxBytes) {
            super(in);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0 && ++read > maxBytes) {
                throw new IOException("FreeHire response exceeded " + maxBytes + " bytes");
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                read += n;
                if (read > maxBytes) {
                    throw new IOException("FreeHire response exceeded " + maxBytes + " bytes");
                }
            }
            return n;
        }
    }
}
