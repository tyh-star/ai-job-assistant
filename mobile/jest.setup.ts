// expo-secure-store has no native module under Jest; an in-memory stand-in keeps
// session tests honest about what was written and cleared.
jest.mock('expo-secure-store', () => {
  const store = new Map<string, string>()
  return {
    getItemAsync: jest.fn(async (key: string) => store.get(key) ?? null),
    setItemAsync: jest.fn(async (key: string, value: string) => {
      store.set(key, value)
    }),
    deleteItemAsync: jest.fn(async (key: string) => {
      store.delete(key)
    }),
    __reset: () => store.clear(),
  }
})

jest.mock('expo-localization', () => ({
  getLocales: () => [{ languageTag: 'zh-CN', languageCode: 'zh' }],
}))

// Native pickers, sharing and the file system only exist on a device. These stand-ins
// record what the app asked for so tests can assert on it.
jest.mock('expo-document-picker', () => ({ getDocumentAsync: jest.fn() }))

jest.mock('expo-sharing', () => ({
  isAvailableAsync: jest.fn(async () => true),
  shareAsync: jest.fn(async () => undefined),
}))

jest.mock('expo-file-system', () => {
  const written = new Map<string, Uint8Array>()
  const join = (parts: unknown[]) =>
    parts.map((part) => (typeof part === 'string' ? part : (part as { uri: string }).uri)).join('/')
  class Directory {
    uri: string
    constructor(...parts: unknown[]) {
      this.uri = join(parts)
    }
    create() {}
  }
  class File {
    uri: string
    constructor(...parts: unknown[]) {
      this.uri = join(parts)
    }
    get exists() {
      return written.has(this.uri)
    }
    get size() {
      return written.get(this.uri)?.length ?? null
    }
    create() {
      written.set(this.uri, new Uint8Array())
    }
    delete() {
      written.delete(this.uri)
    }
    write(content: string | Uint8Array) {
      written.set(this.uri, typeof content === 'string' ? new TextEncoder().encode(content) : content)
    }
  }
  return { File, Directory, Paths: { cache: new Directory('file:///cache') }, __written: written }
})
