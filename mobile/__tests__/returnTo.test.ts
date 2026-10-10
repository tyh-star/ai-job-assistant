import { afterLoginTarget, loginHref, sanitizeReturnTo, signedOutRedirect } from '../src/auth/returnTo'

it('accepts only paths inside the app', () => {
  expect(sanitizeReturnTo('/tailor?jobId=9')).toBe('/tailor?jobId=9')
  expect(sanitizeReturnTo('/catalog/freehire%3Aanalyst')).toBe('/catalog/freehire%3Aanalyst')
  expect(sanitizeReturnTo(['/resumes'])).toBe('/resumes')
  for (const bad of ['https://evil.test', '//evil.test', '/\\evil.test', '/http://evil.test', '/javascript:alert(1)', '%2F%2Fevil.test', '/%2F%2Fevil.test', 'resumes', '']) {
    expect(sanitizeReturnTo(bad)).toBeNull()
  }
  expect(sanitizeReturnTo(`/resumes${String.fromCharCode(10)}x`)).toBeNull()
  expect(sanitizeReturnTo('/login?returnTo=/tailor')).toBeNull()
  expect(sanitizeReturnTo('/register')).toBeNull()
  expect(sanitizeReturnTo('/welcome')).toBeNull()
  expect(sanitizeReturnTo(undefined)).toBeNull()
})

it('builds the login link and the place to continue after logging in', () => {
  expect(loginHref('/tailor?jobId=9')).toBe('/login?returnTo=%2Ftailor%3FjobId%3D9')
  expect(loginHref('https://evil.test')).toBe('/login')
  expect(loginHref()).toBe('/login')
  expect(afterLoginTarget('/resumes?segment=drafts')).toBe('/resumes?segment=drafts')
  expect(afterLoginTarget('//evil.test')).toBe('/dashboard')
  expect(afterLoginTarget(undefined)).toBe('/dashboard')
})

it('returns an expired session to the same screen, other sign-outs to login, and visitors to the welcome page', () => {
  expect(signedOutRedirect('expired', '/draft/12/edit')).toBe('/login?returnTo=%2Fdraft%2F12%2Fedit')
  expect(signedOutRedirect('passwordChanged', '/profile')).toBe('/login')
  expect(signedOutRedirect('accountDeleted', '/profile')).toBe('/login')
  expect(signedOutRedirect(null, '/profile')).toBe('/welcome')
})
