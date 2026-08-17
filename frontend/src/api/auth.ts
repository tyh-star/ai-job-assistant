import { api } from './client'
import type { LoginRequest, LoginResponse, RegisterRequest, UserResponse } from './types'

export function login(body: LoginRequest) {
  return api.post<LoginResponse>('/auth/login', body)
}

export function register(body: RegisterRequest) {
  return api.post<UserResponse>('/auth/register', body)
}

export function getMe() {
  return api.get<UserResponse>('/users/me')
}
