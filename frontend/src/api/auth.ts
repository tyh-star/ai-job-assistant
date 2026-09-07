import { api } from './client'
import { endpoints } from './endpoints'
import type {
  ChangePasswordRequest,
  LoginRequest,
  LoginResponse,
  RegisterRequest,
  UpdateEmailRequest,
  UpdateEmailResponse,
  UpdateNameRequest,
  UserResponse,
} from './types'

export function login(body: LoginRequest) {
  return api.post<LoginResponse>(endpoints.login, body)
}

export function register(body: RegisterRequest) {
  return api.post<UserResponse>(endpoints.register, body)
}

export function getMe() {
  return api.get<UserResponse>(endpoints.me)
}

export function updateName(body: UpdateNameRequest) {
  return api.put<UserResponse>(endpoints.meName, body)
}

/** On success the frontend must replace its stored token -- see AuthContext's updateEmail. */
export function updateEmail(body: UpdateEmailRequest) {
  return api.put<UpdateEmailResponse>(endpoints.meEmail, body)
}

/**
 * On success there is no server-side way to invalidate tokens already issued to OTHER
 * devices/tabs (see JwtUtil's javadoc) -- the caller must clear this browser's own
 * session and require a fresh login here, without claiming other sessions were logged out.
 */
export function changePassword(body: ChangePasswordRequest) {
  return api.put<void>(endpoints.mePassword, body)
}
