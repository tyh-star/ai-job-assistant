package aijob.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class User {

    private Long id;

    private String email;

    private String password;

    private String name;

    private String role;

    // Bumped by 1 on every password change (see UserMapper#changePasswordAndBumpAuthVersion).
    // JwtAuthenticationFilter rejects any token whose authVersion claim no longer
    // matches this column -- see that filter's javadoc for why.
    private int authVersion;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}