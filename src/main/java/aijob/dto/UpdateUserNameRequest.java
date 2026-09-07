package aijob.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Name-only change -- deliberately separate from email (see
 * {@link UpdateUserEmailRequest}), since a name change carries none of email's
 * identity/security concerns (it is not the JWT subject, is never unique-checked, and
 * needs no current-password confirmation).
 */
@Data
public class UpdateUserNameRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 100, message = "Name cannot exceed 100 characters")
    private String name;
}
