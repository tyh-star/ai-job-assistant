package aijob.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name cannot exceed 100 characters")
    private String name;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    /**
     * Strength is enforced by {@link aijob.security.PasswordStrength} on the raw value
     * -- this field is not {@code @Size}/regex-validated here, so the two write paths
     * cannot drift, and Bean Validation cannot {@code trim} a spaces-only password into
     * a blank-field error.
     */
    private String password;
}