package aijob.service.impl;

import aijob.dto.ChangePasswordRequest;
import aijob.dto.UpdateUserEmailRequest;
import aijob.dto.UpdateUserEmailResponse;
import aijob.dto.UpdateUserNameRequest;
import aijob.dto.UserResponse;
import aijob.entity.User;
import aijob.exception.EmailAlreadyExistsException;
import aijob.exception.IncorrectPasswordException;
import aijob.exception.UserNotFoundException;
import aijob.mapper.UserMapper;
import aijob.security.JwtUtil;
import aijob.service.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public UserServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
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
    public UserResponse updateName(
            String email,
            UpdateUserNameRequest request) {

        User user = getUserByEmail(email);
        String newName = request.getName().trim();

        user.setName(newName);
        userMapper.updateName(user.getId(), newName);

        return toUserResponse(user);
    }

    @Override
    public UpdateUserEmailResponse updateEmail(
            String email,
            UpdateUserEmailRequest request) {

        User user = getUserByEmail(email);

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new IncorrectPasswordException("Current password is incorrect");
        }

        String newEmail = request.getNewEmail().trim().toLowerCase();

        if (!user.getEmail().equalsIgnoreCase(newEmail)) {
            User existingUser = userMapper.findByEmail(newEmail);

            if (existingUser != null) {
                throw new EmailAlreadyExistsException("Email already registered");
            }
        }

        user.setEmail(newEmail);
        userMapper.updateEmail(user.getId(), newEmail);

        // The JWT subject IS the email (see JwtUtil#generateToken) -- the caller's OLD
        // token now names an email that resolves to nobody, so this MUST hand back a
        // freshly-signed token, never leave the caller holding a token that breaks on
        // its very next use.
        String freshToken = jwtUtil.generateToken(
                new JwtUtil.UserTokenInfo(user.getId(), user.getEmail(), user.getRole(), user.getAuthVersion()));

        return new UpdateUserEmailResponse(freshToken, toUserResponse(user));
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

        // Single atomic UPDATE -- see UserMapper#changePasswordAndBumpAuthVersion's
        // javadoc for why the auth_version bump can never be a separate statement.
        // This is what makes every previously-issued token for this user (on every
        // device, not just this browser) fail JwtAuthenticationFilter's check from
        // this point on.
        userMapper.changePasswordAndBumpAuthVersion(user.getId(), encodedPassword);
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
                user.getRole(),
                user.getCreatedAt()
        );
    }
}
