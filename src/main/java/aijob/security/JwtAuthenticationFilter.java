package aijob.security;

import aijob.exception.ErrorResponse;
import aijob.mapper.UserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Login/register never need Authentication and never receive a Bearer header from
     * this app's own frontend (see isPublicAuthEndpoint on the client) -- exempting
     * them from the auth_version check below means a stray/manual Authorization header
     * on these two routes can never lock a user out of logging back in.
     */
    private static final String AUTH_PATH_PREFIX = "/api/v1/auth/";

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserMapper userMapper, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {

        // 1. 获取 Authorization Header
        String authHeader =
                request.getHeader("Authorization");

        // 2. 没有 Token，继续往下走
        if (authHeader == null ||
                !authHeader.startsWith("Bearer ")) {

            filterChain.doFilter(request, response);
            return;
        }

        // 3. 去掉 "Bearer "
        String token = authHeader.substring(7);

        // 4. 验证 Token（签名 + 有效期）。一个自然过期/畸形的 token 在这里被当成"匿名"
        // 静默放行——公开接口应当继续可用（见 SecurityConfig 的 permitAll 列表与前端
        // 匿名浏览要求），受保护接口则会被下面 anyRequest().authenticated() 正常拒绝。
        if (jwtUtil.validateToken(token)) {

            String email = jwtUtil.getEmail(token);
            String role = jwtUtil.getRole(token);

            // 4a. 持久化的 token 版本号校验：签名和有效期都合法，不代表这个 token 仍然
            // "有效"——密码修改会把数据库里的 auth_version 加一，此后任何携带旧
            // auth_version 的 token 都必须立即失效，覆盖所有设备，而不只是发起修改的
            // 那一个浏览器。这必须在到达 controller 之前发生，且必须是确定性的 401，
            // 不能退化成"匿名放行、随后被 anyRequest().authenticated() 用不确定的
            // 401/403 拒绝"这种间接路径。/auth/** 本身不需要 Authentication，予以豁免，
            // 避免一个残留的旧 Authorization 头把用户挡在重新登录之外。
            if (!request.getRequestURI().startsWith(AUTH_PATH_PREFIX)) {
                Integer currentAuthVersion = userMapper.findAuthVersionByEmail(email);
                int tokenAuthVersion = jwtUtil.getAuthVersion(token);
                if (currentAuthVersion == null || currentAuthVersion != tokenAuthVersion) {
                    writeRevokedResponse(request, response);
                    return;
                }
            }

            // 5. 创建 Spring Security Authentication
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            email,
                            null,
                            List.of(
                                    new SimpleGrantedAuthority(
                                            "ROLE_" + role
                                    )
                            )
                    );

            // 6. 告诉 Spring Security：
            // 当前请求已经认证
            SecurityContextHolder
                    .getContext()
                    .setAuthentication(authentication);
        }

        // 7. 继续执行后面的 Filter / Controller
        filterChain.doFilter(request, response);
    }

    private void writeRevokedResponse(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        ErrorResponse error = new ErrorResponse(
                HttpServletResponse.SC_UNAUTHORIZED,
                "Unauthorized",
                "This token has been revoked. Please log in again.",
                request.getRequestURI(),
                LocalDateTime.now()
        );
        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
