package aijob.service;

import aijob.dto.ChangePasswordRequest;
import aijob.dto.UpdateUserRequest;
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

    UserResponse updateCurrentUser(
            String email,
            UpdateUserRequest request
    );

    void changePassword(
            String email,
            ChangePasswordRequest request
    );

    void deleteCurrentUser(String email);
}
