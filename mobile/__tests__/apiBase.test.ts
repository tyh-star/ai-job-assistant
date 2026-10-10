import { resolveApiBase } from '../src/config/apiBase'

describe('resolveApiBase', () => {
  it('uses the configured URL and adds /api/v1 once', () => {
    expect(resolveApiBase('https://jobs.example.com/', undefined, false)).toBe('https://jobs.example.com/api/v1')
    expect(resolveApiBase('https://jobs.example.com/api/v1', undefined, false)).toBe('https://jobs.example.com/api/v1')
  })

  it('points Expo Go at the Metro host in development, never localhost on a phone', () => {
    expect(resolveApiBase(undefined, '192.168.1.20:8081', true)).toBe('http://192.168.1.20:8080/api/v1')
  })

  it('returns null for a release build with no configured URL', () => {
    expect(resolveApiBase(undefined, '192.168.1.20:8081', false)).toBeNull()
    expect(resolveApiBase('   ', undefined, false)).toBeNull()
  })
})
