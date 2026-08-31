import { api } from './client'
import { endpoints } from './endpoints'
import type { LoginRequest, LoginResponse, RegisterRequest, UserResponse } from './types'

export function login(body: LoginRequest) {
  return api.post<LoginResponse>(endpoints.login, body)
}

export function register(body: RegisterRequest) {
  return api.post<UserResponse>(endpoints.register, body)
}

export function getMe() {
  return api.get<UserResponse>(endpoints.me)
}
