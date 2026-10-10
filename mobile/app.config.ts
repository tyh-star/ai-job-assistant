import type { ExpoConfig } from 'expo/config'

const config: ExpoConfig = {
  name: 'AI Job Assistant',
  slug: 'ai-job-assistant',
  scheme: 'aijobassistant',
  version: '1.0.0',
  orientation: 'portrait',
  icon: './assets/icon.png',
  userInterfaceStyle: 'light',
  ios: {
    bundleIdentifier: 'com.tyhstar.aijobassistant',
    supportsTablet: false,
  },
  android: {
    package: 'com.tyhstar.aijobassistant',
    adaptiveIcon: {
      backgroundColor: '#E6F4FE',
      foregroundImage: './assets/android-icon-foreground.png',
      backgroundImage: './assets/android-icon-background.png',
      monochromeImage: './assets/android-icon-monochrome.png',
    },
    predictiveBackGestureEnabled: false,
  },
  web: {
    favicon: './assets/favicon.png',
  },
  plugins: ['expo-router', 'expo-secure-store', 'expo-localization'],
  owner: 'tangyaohang',
  extra: {
    eas: {
      // @tangyaohang/ai-job-assistant on expo.dev, created by `eas init`.
      projectId: 'cbcc33de-f3b2-4fd4-9034-486a836a04d8',
    },
  },
}

export default config
