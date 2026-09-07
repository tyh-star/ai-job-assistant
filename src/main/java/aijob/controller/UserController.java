package aijob.controller;

import aijob.dto.ChangePasswordRequest;
import aijob.dto.UpdateUserEmailRequest;
import aijob.dto.UpdateUserEmailResponse;
import aijob.dto.UpdateUserNameRequest;
import aijob.dto.UserResponse;
import aijob.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * The "个人中心" (account/profile) endpoints. Every one of these resolves the current
 * user from {@code Authentication#getName()} (the JWT subject) -- there is no
 * {@code userId} path or query parameter anywhere here, so there is nothing for a
 * caller to guess: this API can only ever act on whichever account the caller's own
 * JWT names.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(
            Authentication authentication) {

        UserResponse user =
                userService.getCurrentUser(
                        authentication.getName()
                );

        return ResponseEntity.ok(user);
    }

    /** Name only -- no current-password check (see UpdateUserNameRequest's javadoc). */
    @PutMapping("/me/name")
    public ResponseEntity<UserResponse> updateName(
            Authentication authentication,
            @Valid @RequestBody UpdateUserNameRequest request) {

        UserResponse user =
                userService.updateName(
                        authentication.getName(),
                        request
                );

        return ResponseEntity.ok(user);
    }

    /**
     * Email is the JWT subject -- this always returns a freshly-signed token alongside
     * the updated profile (see UpdateUserEmailResponse's javadoc). The frontend must
     * replace its stored token with the one in this response.
     */
    @PutMapping("/me/email")
    public ResponseEntity<UpdateUserEmailResponse> updateEmail(
            Authentication authentication,
            @Valid @RequestBody UpdateUserEmailRequest request) {

        UpdateUserEmailResponse response =
                userService.updateEmail(
                        authentication.getName(),
                        request
                );

        return ResponseEntity.ok(response);
    }

    @PutMapping("/me/password")
    public ResponseEntity<Void> changePassword(
            Authentication authentication,
            @Valid @RequestBody ChangePasswordRequest request) {

        userService.changePassword(
                authentication.getName(),
                request
        );

        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteCurrentUser(
            Authentication authentication) {

        userService.deleteCurrentUser(
                authentication.getName()
        );

        return ResponseEntity.noContent().build();
    }
}
