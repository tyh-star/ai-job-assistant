package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * {@code token} is a FRESH JWT signed with the new email as subject -- the frontend
 * must replace its stored token with this one immediately (see
 * {@code AuthContext#updateEmail}), or every subsequent request authenticates as an
 * email that no longer exists.
 */
@Data
@AllArgsConstructor
public class UpdateUserEmailResponse {
    private String token;
    private UserResponse user;
}
