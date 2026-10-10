import * as SecureStore from 'expo-secure-store'

/** Small identifier records for tailoring, in the same store as the language preference. */
export const tailorStore = {
  get: (key: string) => SecureStore.getItemAsync(key),
  set: (key: string, value: string) => SecureStore.setItemAsync(key, value),
  remove: (key: string) => SecureStore.deleteItemAsync(key),
}
