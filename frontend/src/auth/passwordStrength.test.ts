import { describe, expect, it } from 'vitest'
import { evaluatePassword } from './passwordStrength'
import en from '../locales/en.json'
import zh from '../locales/zh-CN.json'

describe('passwordStrength', () => {
  it('accepts a password that meets every backend rule', () => {
    expect(evaluatePassword('Password123!').strong).toBe(true)
  })

  it('rejects missing classes, length, spaces, and oversize', () => {
    expect(evaluatePassword('password123!').upper).toBe(false)
    expect(evaluatePassword('PASSWORD123!').lower).toBe(false)
    expect(evaluatePassword('Password!!!!').digit).toBe(false)
    expect(evaluatePassword('Password1234').special).toBe(false)
    expect(evaluatePassword('Pass1!').length).toBe(false)
    expect(evaluatePassword(' Password123!').noSpaceOrControl).toBe(false)
    expect(evaluatePassword('Pass word123!').strong).toBe(false)
    expect(evaluatePassword(`Aa1!${'x'.repeat(69)}`).length).toBe(false)
    expect(evaluatePassword(`Aa1!${'x'.repeat(68)}`).strong).toBe(true)
  })

  it('matches backend length bounds and has zh/en rule copy', () => {
    expect(en.auth.passwordRule.length).toMatch(/8/)
    expect(en.auth.passwordRule.length).toMatch(/72/)
    expect(zh.auth.passwordRule.length).toMatch(/8/)
    expect(zh.auth.passwordRule.length).toMatch(/72/)
    expect(en.errors.weakPassword.length).toBeGreaterThan(0)
    expect(zh.errors.weakPassword.length).toBeGreaterThan(0)
    expect(en.auth.passwordRule.upper).toMatch(/uppercase/i)
    expect(zh.auth.passwordRule.upper).toMatch(/大写/)
  })
})
