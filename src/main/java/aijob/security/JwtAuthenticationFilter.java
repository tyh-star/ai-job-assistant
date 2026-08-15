package aijob.security;

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
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
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

        // 4. 验证 Token
        if (jwtUtil.validateToken(token)) {

            String email = jwtUtil.getEmail(token);
            String role = jwtUtil.getRole(token);

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
}
