package aijob.service;

import aijob.dto.ChangePasswordRequest;
import aijob.dto.UpdateUserEmailRequest;
import aijob.dto.UpdateUserEmailResponse;
import aijob.dto.UpdateUserNameRequest;
import aijob.dto.UserResponse;
import aijob.entity.User;

import java.util.List;

public interface UserService {

    User findById(Long id);

    User findByEmail(String email);

    List<User> findAll();

    User addUser(User user);

    boolean updateUser(User user);

    boolean deleteById(Long id);

    UserResponse getCurrentUser(String email);

    /** Name only -- no password check, no uniqueness check. See {@link UpdateUserNameRequest}. */
    UserResponse updateName(
            String email,
            UpdateUserNameRequest request
    );

    /**
     * Email is the JWT subject -- verifies {@code currentPassword}, checks the new
     * email's uniqueness, and returns a FRESH token signed with the new email. See
     * {@link UpdateUserEmailRequest}'s javadoc for why this can never be a bare update.
     */
    UpdateUserEmailResponse updateEmail(
            String email,
            UpdateUserEmailRequest request
    );

    void changePassword(
            String email,
            ChangePasswordRequest request
    );

    void deleteCurrentUser(String email);
}
