package aijob.controller;

import aijob.dto.ChangePasswordRequest;
import aijob.dto.UpdateUserRequest;
import aijob.dto.UserResponse;
import aijob.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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

    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateCurrentUser(
            Authentication authentication,
            @Valid @RequestBody UpdateUserRequest request) {

        UserResponse user =
                userService.updateCurrentUser(
                        authentication.getName(),
                        request
                );

        return ResponseEntity.ok(user);
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
