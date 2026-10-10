import { ScrollView, StyleSheet, Text, View } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import { LinearGradient } from 'expo-linear-gradient'
import { useTranslation } from 'react-i18next'
import { Button } from '../components/Button'
import { BrandGlyph, BriefcaseIcon, ClockIcon, FileIcon, SearchIcon, UserIcon } from '../components/Icons'
import { colors, gradients, radius, space } from '../theme'

type Feature = {
  key: 'jobs' | 'resumes' | 'builder' | 'tailor' | 'track'
  /** Where to continue after logging in; null when no account is needed. */
  returnTo: string | null
  icon: React.ReactElement
}

const FEATURES: Feature[] = [
  { key: 'jobs', returnTo: null, icon: <SearchIcon size={18} /> },
  { key: 'resumes', returnTo: '/resumes', icon: <FileIcon size={18} /> },
  { key: 'builder', returnTo: '/resumes?segment=drafts', icon: <UserIcon size={18} /> },
  { key: 'tailor', returnTo: '/tailor', icon: <BriefcaseIcon size={18} /> },
  { key: 'track', returnTo: '/jobs?tab=saved', icon: <ClockIcon size={18} /> },
]

type Props = {
  onBrowseJobs: () => void
  /** Opens login; `returnTo` is the signed-in screen to continue to. */
  onLogin: (returnTo?: string) => void
  onRegister: () => void
}

/**
 * The first screen for anyone not signed in. It makes no request at all: no personal
 * data exists yet, and the examples below are fixed, labelled sample content.
 */
export function WelcomeScreen({ onBrowseJobs, onLogin, onRegister }: Props) {
  const { t } = useTranslation()
  return (
    <SafeAreaView style={styles.safe} edges={['top', 'bottom']}>
      <ScrollView contentContainerStyle={styles.content}>
        <LinearGradient colors={gradients.brand} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={styles.hero}>
          <View style={styles.brandRow}>
            <BrandGlyph size={20} />
            <Text style={styles.brand}>{t('brand')}</Text>
          </View>
          <Text role="heading" style={styles.heroTitle}>
            {t('welcome.title')}
          </Text>
          <Text style={styles.heroBody}>{t('welcome.subtitle')}</Text>
          <View style={styles.heroActions}>
            <Button label={t('welcome.browseJobs')} variant="secondary" onPress={onBrowseJobs} style={styles.flex} />
            <Button label={t('welcome.login')} variant="secondary" onPress={() => onLogin()} style={styles.flex} />
          </View>
          <Button label={t('welcome.register')} variant="secondary" size="sm" onPress={onRegister} />
        </LinearGradient>

        <Text role="heading" style={styles.sectionTitle}>
          {t('welcome.featuresTitle')}
        </Text>
        <View style={styles.features}>
          {FEATURES.map((feature) => (
            <View key={feature.key} style={styles.feature}>
              <View style={styles.featureTop}>
                <View style={styles.tile}>{feature.icon}</View>
                <View style={styles.flex}>
                  <Text style={styles.featureTitle}>{t(`welcome.features.${feature.key}.title`)}</Text>
                  <Text style={[styles.pill, feature.returnTo ? styles.pillLogin : styles.pillPublic]}>
                    {t(feature.returnTo ? 'welcome.loginBadge' : 'welcome.publicBadge')}
                  </Text>
                </View>
              </View>
              <Text style={styles.featureBody}>{t(`welcome.features.${feature.key}.body`)}</Text>
              <Button
                size="sm"
                variant={feature.returnTo ? 'secondary' : 'primary'}
                label={t(`welcome.features.${feature.key}.action`)}
                onPress={() => (feature.returnTo ? onLogin(feature.returnTo) : onBrowseJobs())}
              />
            </View>
          ))}
        </View>

        <View style={styles.examplesHead}>
          <Text role="heading" style={styles.sectionTitle}>
            {t('welcome.examplesTitle')}
          </Text>
          <Text style={styles.exampleBadge}>{t('welcome.examplesBadge')}</Text>
        </View>
        <Text style={styles.note}>{t('welcome.examplesNote')}</Text>

        <View style={styles.example} aria-label={t('welcome.tailorExample.title')}>
          <Text style={styles.exampleTitle}>{t('welcome.tailorExample.title')}</Text>
          <Text style={styles.label}>{t('welcome.tailorExample.original')}</Text>
          <Text style={styles.quote}>{t('welcome.tailorExample.originalText')}</Text>
          <Text style={styles.label}>{t('welcome.tailorExample.rewritten')}</Text>
          <Text style={[styles.quote, styles.rewritten]}>{t('welcome.tailorExample.rewrittenText')}</Text>
          <Text style={styles.note}>{t('welcome.tailorExample.note')}</Text>
        </View>

        <View style={styles.example} aria-label={t('welcome.qualityExample.title')}>
          <Text style={styles.exampleTitle}>{t('welcome.qualityExample.title')}</Text>
          <Text style={styles.score}>{t('welcome.qualityExample.score')}</Text>
          <View style={styles.row}>
            <Text style={styles.metric}>{t('welcome.qualityExample.structure')}</Text>
            <Text style={styles.metric}>{t('welcome.qualityExample.achievements')}</Text>
          </View>
          <Text style={styles.note}>{t('welcome.qualityExample.note')}</Text>
        </View>

        <View style={styles.footer}>
          <Button label={t('welcome.login')} onPress={() => onLogin()} />
          <Button label={t('welcome.register')} variant="secondary" onPress={onRegister} />
        </View>
      </ScrollView>
    </SafeAreaView>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { padding: space.screenX, paddingBottom: 32, gap: 14 },
  flex: { flex: 1 },
  hero: { borderRadius: radius.card, padding: 20, gap: 12 },
  brandRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  brand: { fontSize: 15, fontWeight: '700', color: '#FFFFFF' },
  heroTitle: { fontSize: 26, lineHeight: 34, fontWeight: '700', color: '#FFFFFF', letterSpacing: -0.4 },
  heroBody: { fontSize: 15, lineHeight: 22, color: '#E4EEFF' },
  heroActions: { flexDirection: 'row', gap: 10, marginTop: 4 },
  sectionTitle: { marginTop: 8, fontSize: 18, fontWeight: '700', color: colors.ink },
  features: { gap: 10 },
  feature: { gap: 10, padding: 16, borderRadius: radius.card, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.card },
  featureTop: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  tile: { width: 38, height: 38, borderRadius: radius.tile, backgroundColor: colors.tile, alignItems: 'center', justifyContent: 'center' },
  featureTitle: { fontSize: 16, fontWeight: '700', color: colors.ink },
  featureBody: { fontSize: 14, lineHeight: 20, color: colors.ink2 },
  pill: { alignSelf: 'flex-start', marginTop: 4, paddingHorizontal: 8, paddingVertical: 2, borderRadius: radius.pill, fontSize: 12, fontWeight: '600', overflow: 'hidden' },
  pillPublic: { backgroundColor: '#E3F6EC', color: '#067647' },
  pillLogin: { backgroundColor: '#EEF1F6', color: colors.ink2 },
  examplesHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8, flexWrap: 'wrap' },
  exampleBadge: {
    marginTop: 8,
    paddingHorizontal: 10,
    paddingVertical: 3,
    borderRadius: radius.pill,
    backgroundColor: '#FFF4E0',
    color: '#8A5300',
    fontSize: 12,
    fontWeight: '700',
    overflow: 'hidden',
  },
  example: { gap: 6, padding: 16, borderRadius: radius.card, borderWidth: 1, borderStyle: 'dashed', borderColor: colors.inputBorder, backgroundColor: colors.surface },
  exampleTitle: { fontSize: 15, fontWeight: '700', color: colors.ink },
  label: { fontSize: 12, fontWeight: '700', color: colors.muted },
  quote: { fontSize: 14, lineHeight: 20, color: colors.ink, padding: 10, borderRadius: radius.chip, backgroundColor: colors.card },
  rewritten: { backgroundColor: '#EAF3FF' },
  score: { fontSize: 24, fontWeight: '700', color: colors.blueStrong, fontVariant: ['tabular-nums'] },
  row: { flexDirection: 'row', gap: 16 },
  metric: { fontSize: 14, color: colors.ink2, fontVariant: ['tabular-nums'] },
  note: { fontSize: 13, lineHeight: 18, color: colors.muted },
  footer: { gap: 10, marginTop: 6 },
})
