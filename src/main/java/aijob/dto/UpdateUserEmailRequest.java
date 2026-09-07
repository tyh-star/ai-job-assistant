package aijob.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Email is the JWT subject ({@code JwtUtil#generateToken} signs {@code sub = email}) --
 * changing it can never be a bare {@code UPDATE}. Requires the CURRENT password (this
 * is an identity change, not a cosmetic one) and, on success, the caller gets back a
 * freshly-signed token with the NEW email as subject (see
 * {@link UpdateUserEmailResponse}) -- the old token's subject would otherwise resolve
 * to a user that no longer exists under that email on the very next request.
 */
@Data
public class UpdateUserEmailRequest {

    @NotBlank(message = "Current password is required")
    private String currentPassword;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String newEmail;
}
