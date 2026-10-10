import Constants from 'expo-constants'

const API_PREFIX = '/api/v1'
const LOCAL_BACKEND_PORT = 8080

/**
 * Where the Spring Boot API lives.
 *
 * 1. EXPO_PUBLIC_API_BASE_URL always wins, e.g. `https://example.com/api/v1`.
 * 2. In development only, fall back to the machine running Metro: Expo Go on a phone
 *    reaches it by the same LAN host it loaded the bundle from, never `localhost`.
 * 3. A release build with no configured URL returns null, so the app shows a
 *    configuration error instead of silently calling a loopback address.
 */
export function resolveApiBase(
  configured: string | undefined = process.env.EXPO_PUBLIC_API_BASE_URL,
  hostUri: string | null | undefined = Constants.expoConfig?.hostUri,
  isDev: boolean = __DEV__,
): string | null {
  const fromEnv = configured?.trim()
  if (fromEnv) {
    return withApiPrefix(fromEnv)
  }
  if (!isDev) {
    return null
  }
  const host = hostUri?.split(':')[0]?.trim() || 'localhost'
  return `http://${host}:${LOCAL_BACKEND_PORT}${API_PREFIX}`
}

function withApiPrefix(base: string): string {
  const trimmed = base.replace(/\/+$/, '')
  return trimmed.endsWith(API_PREFIX) ? trimmed : `${trimmed}${API_PREFIX}`
}
