package aijob.config;

import aijob.security.JwtAuthenticationFilter;
import aijob.security.RestSecurityErrorHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * HTTP security for the API container. The collector container runs without a servlet
 * stack, so this configuration is limited to servlet applications; the rules themselves
 * are unchanged.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final List<String> allowedOrigins;
    private final RestSecurityErrorHandler securityErrorHandler;

    public SecurityConfig(
            JwtAuthenticationFilter jwtAuthenticationFilter,
            @Value("${aijob.cors.allowed-origins}") String allowedOriginsRaw,
            ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.allowedOrigins = parseAllowedOrigins(allowedOriginsRaw);
        this.securityErrorHandler = new RestSecurityErrorHandler(objectMapper);
    }

    static List<String> parseAllowedOrigins(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("aijob.cors.allowed-origins must not be empty");
        }
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String origin = part.trim();
            if (origin.isEmpty()) {
                continue;
            }
            if (origin.contains("*")) {
                throw new IllegalStateException(
                        "CORS origins must be exact values; wildcards are not allowed: " + origin);
            }
            origins.add(origin);
        }
        if (origins.isEmpty()) {
            throw new IllegalStateException("aijob.cors.allowed-origins must not be empty");
        }
        return List.copyOf(origins);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(new ArrayList<>(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setExposedHeaders(List.of("Content-Disposition"));
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(
                                SessionCreationPolicy.STATELESS
                        )
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",
                                "/webjars/**",
                                "/error"
                        ).permitAll()
                        // Public job browsing: search and one posting's detail need no
                        // account, so the site can show real listings before a visitor
                        // ever signs up. Only these two GET routes -- saving a job,
                        // confirming/revoking "applied", and every /api/v1/jobs/** route
                        // (the personal jobs list, Match, Tailor, Dashboard, résumés)
                        // stay behind the anyRequest().authenticated() rule below.
                        // "/api/v1/job-catalog/*" matches exactly one path segment (the
                        // catalogJobId), so it can never also match
                        // "/api/v1/job-catalog/{id}/save" or ".../applied" (two segments).
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/job-catalog/search",
                                "/api/v1/job-catalog/*"
                        ).permitAll()
                        // Site feedback's public summary -- average rating and reviewer
                        // count only (see FeedbackController/FeedbackSummaryResponse).
                        // Never the same route as /api/v1/feedback/me, which stays behind
                        // anyRequest().authenticated() below.
                        .requestMatchers(HttpMethod.GET, "/api/v1/feedback/summary").permitAll()
                        .anyRequest().authenticated()
                )
                // 401 for missing/invalid/expired credentials, 403 only for an
                // authenticated user who is not allowed -- see RestSecurityErrorHandler.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler)
                )
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .addFilterBefore(
                        jwtAuthenticationFilter,
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }
}
