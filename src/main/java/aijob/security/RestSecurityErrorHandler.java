package aijob.security;

import aijob.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Keeps the two security failures apart for API clients:
 * <ul>
 *   <li>401 -- no usable credentials: no Bearer token, or one that is malformed, signed
 *   with another key or expired. {@link JwtAuthenticationFilter} lets such a request
 *   continue as anonymous so public routes keep working; on a protected route Spring
 *   Security ends up here. Without this entry point Spring answers 403, which clients
 *   cannot tell apart from a real permission problem.</li>
 *   <li>403 -- authenticated, but not allowed.</li>
 * </ul>
 * A revoked token (auth_version mismatch) is answered 401 by the filter itself.
 */
public class RestSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestSecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        write(request, response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized",
                "Authentication is required, or the token is invalid or expired. Please log in again.");
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException) throws IOException {
        write(request, response, HttpServletResponse.SC_FORBIDDEN, "Forbidden",
                "You do not have permission to perform this action.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response, int status, String error,
                       String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        ErrorResponse body = new ErrorResponse(status, error, message, request.getRequestURI(), LocalDateTime.now());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
