import { Tabs } from 'expo-router'
import { useTranslation } from 'react-i18next'
import { BriefcaseIcon, FileIcon, HomeIcon, TailorIcon, UserIcon } from '../../../src/components/Icons'
import { colors } from '../../../src/theme'

export default function TabsLayout() {
  const { t } = useTranslation()
  return (
    <Tabs
      screenOptions={{
        headerShown: false,
        tabBarActiveTintColor: colors.blue,
        tabBarInactiveTintColor: colors.subtle,
        tabBarStyle: { backgroundColor: '#FFFFFF', borderTopColor: colors.hairline },
        tabBarLabelStyle: { fontSize: 11, fontWeight: '600' },
        sceneStyle: { backgroundColor: colors.background },
      }}
    >
      <Tabs.Screen
        name="dashboard"
        options={{ title: t('tabs.dashboard'), tabBarIcon: ({ color }) => <HomeIcon color={String(color)} /> }}
      />
      <Tabs.Screen
        name="jobs"
        options={{ title: t('tabs.jobs'), tabBarIcon: ({ color }) => <BriefcaseIcon size={22} color={String(color)} /> }}
      />
      <Tabs.Screen
        name="resumes"
        options={{ title: t('tabs.resumes'), tabBarIcon: ({ color }) => <FileIcon size={22} color={String(color)} /> }}
      />
      <Tabs.Screen
        name="tailor"
        options={{ title: t('tabs.tailor'), tabBarIcon: ({ color }) => <TailorIcon color={String(color)} /> }}
      />
      <Tabs.Screen
        name="profile"
        options={{ title: t('tabs.profile'), tabBarIcon: ({ color }) => <UserIcon color={String(color)} /> }}
      />
    </Tabs>
  )
}
