import { StyleSheet, Text, View } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import { useTranslation } from 'react-i18next'
import { Button } from '../components/Button'
import { colors, radius } from '../theme'

type Props = { title: string; onBack?: () => void }

/** Stand-in for screens scheduled for a later batch; says so plainly. */
export function ComingSoonScreen({ title, onBack }: Props) {
  const { t } = useTranslation()
  return (
    <SafeAreaView style={styles.safe} edges={['top']}>
      <View style={styles.body}>
        <Text role="heading" style={styles.title}>
          {title}
        </Text>
        <View style={styles.card}>
          <Text style={styles.cardTitle}>{t('comingSoon.title')}</Text>
          <Text style={styles.cardBody}>{t('comingSoon.body')}</Text>
          {onBack ? (
            <Button variant="secondary" label={t('comingSoon.back')} onPress={onBack} style={styles.button} />
          ) : null}
        </View>
      </View>
    </SafeAreaView>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  body: { paddingHorizontal: 18, paddingTop: 10 },
  title: { fontSize: 28, fontWeight: '700', color: colors.ink, letterSpacing: -0.6 },
  card: {
    marginTop: 16,
    padding: 18,
    borderRadius: radius.card,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.card,
  },
  cardTitle: { fontSize: 16, fontWeight: '700', color: colors.ink },
  cardBody: { marginTop: 6, fontSize: 14, lineHeight: 20, color: colors.muted },
  button: { marginTop: 14, alignSelf: 'flex-start' },
})
