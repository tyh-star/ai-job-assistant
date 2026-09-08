package aijob.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChangePasswordRequest {

    @NotBlank(message = "Current password is required")
    private String currentPassword;

    /**
     * Strength is enforced by {@link aijob.security.PasswordStrength} on the raw value.
     * Do not add a second regex or {@code @Size} here -- see {@link RegisterRequest}.
     */
    private String newPassword;
}
