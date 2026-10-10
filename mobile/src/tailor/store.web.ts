/** Web is a development preview only; see session.web.ts. */
export const tailorStore = {
  get: async (key: string) => globalThis.localStorage?.getItem(key) ?? null,
  set: async (key: string, value: string) => {
    globalThis.localStorage?.setItem(key, value)
  },
  remove: async (key: string) => {
    globalThis.localStorage?.removeItem(key)
  },
}
