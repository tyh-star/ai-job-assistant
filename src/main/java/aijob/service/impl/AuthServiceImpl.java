package aijob.service.impl;

import aijob.dto.LoginRequest;
import aijob.dto.LoginResponse;
import aijob.dto.RegisterRequest;
import aijob.dto.UserResponse;
import aijob.entity.User;
import aijob.exception.EmailAlreadyExistsException;
import aijob.exception.InvalidCredentialsException;
import aijob.mapper.UserMapper;
import aijob.security.JwtUtil;
import aijob.security.PasswordStrength;
import aijob.service.AuthService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @Override
    public UserResponse register(RegisterRequest request) {

        String email = request.getEmail().trim().toLowerCase();

        User existingUser = userMapper.findByEmail(email);

        if (existingUser != null) {
            throw new EmailAlreadyExistsException("Email already registered");
        }

        // Raw input -- never trim. Login does not use this gate, so existing hashes
        // that would fail it still authenticate.
        PasswordStrength.requireStrong(request.getPassword());

        User user = new User();

        user.setName(request.getName().trim());
        user.setEmail(email);
        user.setPassword(
                passwordEncoder.encode(request.getPassword())
        );
        user.setRole("USER");

        userMapper.insert(user);
        // created_at is a DB-side DEFAULT CURRENT_TIMESTAMP -- insert() only backfills
        // the generated id, so re-fetch to get the real value rather than reporting a
        // null createdAt on the very first response about this user.
        User persisted = userMapper.findById(user.getId());
        User result = persisted != null ? persisted : user;

        return new UserResponse(
                result.getId(),
                result.getName(),
                result.getEmail(),
                result.getRole(),
                result.getCreatedAt()
        );
    }
    @Override
    public LoginResponse login(LoginRequest request) {

        String email = request.getEmail()
                .trim()
                .toLowerCase();

        // 1. 根据 email 查询用户
        User user = userMapper.findByEmail(email);

        if (user == null) {
            throw new InvalidCredentialsException(
                    "Invalid email or password"
            );
        }

        // 2. 验证 BCrypt 密码
        if (!passwordEncoder.matches(
                request.getPassword(),
                user.getPassword())) {

            throw new InvalidCredentialsException(
                    "Invalid email or password"
            );
        }

        // 3. 登录成功，生成 JWT
        String token = jwtUtil.generateToken(
                new JwtUtil.UserTokenInfo(
                        user.getId(),
                        user.getEmail(),
                        user.getRole(),
                        user.getAuthVersion()
                )
        );

        // 4. 返回前端
        return new LoginResponse(
                token,
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getRole()
        );
    }
}
