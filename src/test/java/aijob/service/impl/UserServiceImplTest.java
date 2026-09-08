package aijob.service.impl;

import aijob.dto.ChangePasswordRequest;
import aijob.dto.UpdateUserEmailRequest;
import aijob.dto.UpdateUserEmailResponse;
import aijob.dto.UpdateUserNameRequest;
import aijob.dto.UserResponse;
import aijob.entity.User;
import aijob.exception.EmailAlreadyExistsException;
import aijob.exception.IncorrectPasswordException;
import aijob.exception.WeakPasswordException;
import aijob.exception.UserNotFoundException;
import aijob.mapper.UserMapper;
import aijob.security.JwtUtil;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "个人中心" (profile) -- name, email, password. Every operation resolves the target
 * user purely from the {@code email} param (which the controller always sources from
 * {@code Authentication#getName()}, never a caller-supplied id), so isolation is
 * structural: there is no id for a caller to guess. See
 * {@link aijob.controller.UserControllerSecurityTest} for the same guarantees proven
 * through the real Spring Security filter chain.
 */
class UserServiceImplTest {

    private static final String EMAIL = "alice@example.com";
    private static final String OTHER_EMAIL = "bob@example.com";

    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private UserServiceImpl service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        // A REAL encoder, not a mock -- passwordEncoder.matches must reflect actual
        // BCrypt behavior for these tests to mean anything.
        passwordEncoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jwtUtil = new JwtUtil("test-only-jwt-secret-key-must-be-32b!!", 86400000L);
        service = new UserServiceImpl(userMapper, passwordEncoder, jwtUtil);
    }

    private User user(Long id, String email, String rawPassword, String name) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode(rawPassword));
        u.setName(name);
        u.setRole("USER");
        u.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return u;
    }

    // ---------------------------------------------------------------------------
    // getCurrentUser
    // ---------------------------------------------------------------------------

    @Test
    void getCurrentUserIncludesCreatedAt() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(user(1L, EMAIL, "Password123", "Alice"));

        UserResponse response = service.getCurrentUser(EMAIL);

        assertEquals("Alice", response.getName());
        assertEquals(EMAIL, response.getEmail());
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), response.getCreatedAt());
    }

    @Test
    void getCurrentUserForAnUnknownEmailIsNotFound() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(null);
        assertThrows(UserNotFoundException.class, () -> service.getCurrentUser(EMAIL));
    }

    // ---------------------------------------------------------------------------
    // updateName -- no password required, no uniqueness check
    // ---------------------------------------------------------------------------

    @Test
    void updateNameTrimsSurroundingWhitespace() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(user(1L, EMAIL, "Password123", "Alice"));
        UpdateUserNameRequest request = new UpdateUserNameRequest();
        request.setName("  Alice Smith  ");

        UserResponse response = service.updateName(EMAIL, request);

        assertEquals("Alice Smith", response.getName());
        verify(userMapper).updateName(1L, "Alice Smith");
    }

    @Test
    void updateNameNeverTouchesEmailOrPassword() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(user(1L, EMAIL, "Password123", "Alice"));
        UpdateUserNameRequest request = new UpdateUserNameRequest();
        request.setName("New Name");

        service.updateName(EMAIL, request);

        verify(userMapper, never()).updateEmail(any(), any());
        verify(userMapper, never()).changePasswordAndBumpAuthVersion(any(), any());
    }

    // ---------------------------------------------------------------------------
    // updateEmail -- requires current password, checks uniqueness, re-issues JWT
    // ---------------------------------------------------------------------------

    @Test
    void updateEmailRejectsAnIncorrectCurrentPassword() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(user(1L, EMAIL, "Password123", "Alice"));
        UpdateUserEmailRequest request = new UpdateUserEmailRequest();
        request.setCurrentPassword("wrong-password");
        request.setNewEmail("alice-new@example.com");

        assertThrows(IncorrectPasswordException.class, () -> service.updateEmail(EMAIL, request));
        verify(userMapper, never()).updateEmail(any(), any());
    }

    @Test
    void updateEmailRejectsAnAlreadyRegisteredNewEmail() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(user(1L, EMAIL, "Password123", "Alice"));
        when(userMapper.findByEmail("taken@example.com")).thenReturn(user(2L, "taken@example.com", "x", "Someone Else"));
        UpdateUserEmailRequest request = new UpdateUserEmailRequest();
        request.setCurrentPassword("Password123");
        request.setNewEmail("taken@example.com");

        assertThrows(EmailAlreadyExistsException.class, () -> service.updateEmail(EMAIL, request));
        verify(userMapper, never()).updateEmail(any(), any());
    }

    @Test
    void updateEmailSucceedsAndReturnsAFreshTokenNamingTheNewEmail() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(user(1L, EMAIL, "Password123", "Alice"));
        when(userMapper.findByEmail("alice-new@example.com")).thenReturn(null);
        UpdateUserEmailRequest request = new UpdateUserEmailRequest();
        request.setCurrentPassword("Password123");
        request.setNewEmail("Alice-New@Example.com"); // mixed case, normalized on write

        UpdateUserEmailResponse response = service.updateEmail(EMAIL, request);

        verify(userMapper).updateEmail(1L, "alice-new@example.com");
        assertEquals("alice-new@example.com", response.getUser().getEmail());

        // The fresh token actually names the NEW email as its subject -- not the old
        // one, and not a token that merely looks different.
        Claims claims = jwtUtil.parseToken(response.getToken());
        assertEquals("alice-new@example.com", claims.getSubject());
        assertEquals(1L, ((Number) claims.get("userId")).longValue());
    }

    @Test
    void changingEmailToTheSameAddressJustNormalizedIsNotTreatedAsAConflict() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(user(1L, EMAIL, "Password123", "Alice"));
        UpdateUserEmailRequest request = new UpdateUserEmailRequest();
        request.setCurrentPassword("Password123");
        request.setNewEmail(EMAIL.toUpperCase());

        UpdateUserEmailResponse response = service.updateEmail(EMAIL, request);

        assertEquals(EMAIL, response.getUser().getEmail());
        // Only the initial "who is this" lookup happened -- the uniqueness check must
        // be skipped entirely once the normalized new email equals the current one, so
        // findByEmail is never called a second time as if checking against a stranger.
        verify(userMapper, org.mockito.Mockito.times(1)).findByEmail(any());
    }

    // ---------------------------------------------------------------------------
    // changePassword
    // ---------------------------------------------------------------------------

    @Test
    void changePasswordRejectsAnIncorrectCurrentPassword() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(user(1L, EMAIL, "Password123", "Alice"));
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword("wrong");
        request.setNewPassword("NewPassword456!");

        assertThrows(IncorrectPasswordException.class, () -> service.changePassword(EMAIL, request));
        verify(userMapper, never()).changePasswordAndBumpAuthVersion(any(), any());
    }

    @Test
    void changePasswordRejectsAWeakNewPasswordWithoutWriting() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(user(1L, EMAIL, "Password123", "Alice"));
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword("Password123");
        request.setNewPassword("Password1234");

        assertThrows(WeakPasswordException.class, () -> service.changePassword(EMAIL, request));
        verify(userMapper, never()).changePasswordAndBumpAuthVersion(any(), any());
    }

    @Test
    void changePasswordEncodesTheNewPasswordBeforeStoringAndBumpsAuthVersionInTheSameCall() {
        User existing = user(1L, EMAIL, "Password123", "Alice");
        when(userMapper.findByEmail(EMAIL)).thenReturn(existing);
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword("Password123");
        request.setNewPassword("NewPassword456!");

        service.changePassword(EMAIL, request);

        org.mockito.ArgumentCaptor<String> passwordCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        // The auth-version bump is not a separate call to verify -- it happens inside
        // the SAME mapper statement as the password write (see
        // UserMapper#changePasswordAndBumpAuthVersion's javadoc), so the only thing to
        // assert here is that this one call happened with the id and a properly
        // encoded password.
        verify(userMapper).changePasswordAndBumpAuthVersion(eq(1L), passwordCaptor.capture());
        String stored = passwordCaptor.getValue();
        assertNotEquals("NewPassword456!", stored, "must never store the raw new password");
        assertTrue(passwordEncoder.matches("NewPassword456!", stored));
    }

    // ---------------------------------------------------------------------------
    // isolation: acting as Alice must never touch Bob's row
    // ---------------------------------------------------------------------------

    @Test
    void updateNameForOneUserNeverAffectsAnother() {
        when(userMapper.findByEmail(EMAIL)).thenReturn(user(1L, EMAIL, "Password123", "Alice"));
        when(userMapper.findByEmail(OTHER_EMAIL)).thenReturn(user(2L, OTHER_EMAIL, "Password123", "Bob"));
        UpdateUserNameRequest request = new UpdateUserNameRequest();
        request.setName("Alice Renamed");

        service.updateName(EMAIL, request);

        verify(userMapper).updateName(eq(1L), any());
        verify(userMapper, never()).updateName(eq(2L), any());
    }
}
