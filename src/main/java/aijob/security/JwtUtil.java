package aijob.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expiration;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration}") long expiration) {

        this.key = Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8)
        );

        this.expiration = expiration;
    }

    // 生成 Token
    public String generateToken(UserTokenInfo user) {

        return Jwts.builder()
                .subject(user.email())
                .claim("userId", user.id())
                .claim("role", user.role())
                .issuedAt(new Date())
                .expiration(
                        new Date(System.currentTimeMillis() + expiration)
                )
                .signWith(key)
                .compact();
    }

    // 解析 Token
    public Claims parseToken(String token) {

        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // 获取 email
    public String getEmail(String token) {
        return parseToken(token).getSubject();
    }

    // 获取 userId
    public Long getUserId(String token) {
        Number userId = parseToken(token).get("userId", Number.class);
        return userId.longValue();
    }

    // 获取 role
    public String getRole(String token) {
        return parseToken(token).get("role", String.class);
    }

    // 验证 Token
    public boolean validateToken(String token) {
        try {
            Claims claims = parseToken(token);

            return claims.getExpiration()
                    .after(new Date());

        } catch (Exception e) {
            return false;
        }
    }

    public record UserTokenInfo(
            Long id,
            String email,
            String role
    ) {
    }

    
}