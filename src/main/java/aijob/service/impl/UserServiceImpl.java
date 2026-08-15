package aijob.service.impl;

import aijob.dto.ChangePasswordRequest;
import aijob.dto.UpdateUserRequest;
import aijob.dto.UserResponse;
import aijob.entity.User;
import aijob.exception.EmailAlreadyExistsException;
import aijob.exception.IncorrectPasswordException;
import aijob.exception.UserNotFoundException;
import aijob.mapper.UserMapper;
import aijob.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public User findById(Long id) {
        return userMapper.findById(id);
    }

    @Override
    public User findByEmail(String email) {
        return userMapper.findByEmail(email);
    }

    @Override
    public List<User> findAll() {
        return userMapper.findAll();
    }

    @Override
    public User addUser(User user) {
        userMapper.insert(user);
        return user;
    }

    @Override
    public boolean updateUser(User user) {
        return userMapper.update(user) > 0;
    }

    @Override
    public boolean deleteById(Long id) {
        return userMapper.deleteById(id) > 0;
    }

    @Override
    public UserResponse getCurrentUser(String email) {
        User user = getUserByEmail(email);
        return toUserResponse(user);
    }

    @Override
    public UserResponse updateCurrentUser(
            String email,
            UpdateUserRequest request) {

        User user = getUserByEmail(email);

        String newEmail = request.getEmail().trim().toLowerCase();

        if (!user.getEmail().equalsIgnoreCase(newEmail)) {
            User existingUser = userMapper.findByEmail(newEmail);

            if (existingUser != null) {
                throw new EmailAlreadyExistsException(
                        "Email already registered"
                );
            }
        }

        user.setName(request.getName().trim());
        user.setEmail(newEmail);

        userMapper.update(user);

        return toUserResponse(user);
    }

    @Override
    public void changePassword(
            String email,
            ChangePasswordRequest request) {

        User user = getUserByEmail(email);

        if (!passwordEncoder.matches(
                request.getCurrentPassword(),
                user.getPassword())) {

            throw new IncorrectPasswordException(
                    "Current password is incorrect"
            );
        }

        String encodedPassword =
                passwordEncoder.encode(
                        request.getNewPassword()
                );

        user.setPassword(encodedPassword);

        userMapper.updatePassword(user);
    }

    @Override
    public void deleteCurrentUser(String email) {

        User user = getUserByEmail(email);

        userMapper.deleteById(user.getId());
    }

    private User getUserByEmail(String email) {

        User user = userMapper.findByEmail(email);

        if (user == null) {
            throw new UserNotFoundException(
                    "User not found"
            );
        }

        return user;
    }

    private UserResponse toUserResponse(User user) {

        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole()
        );
    }
}
