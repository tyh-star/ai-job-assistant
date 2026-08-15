package aijob.service;

import aijob.dto.LoginRequest;
import aijob.dto.LoginResponse;
import aijob.dto.RegisterRequest;
import aijob.dto.UserResponse;
import aijob.entity.User;

public interface AuthService {

    UserResponse register(RegisterRequest request);

    LoginResponse login(LoginRequest request);

}
