import type { ReactNode } from 'react'
import { StyleSheet, Text, View } from 'react-native'
import type { TFunction } from 'i18next'
import { Notice } from '../components/AuthShell'
import { colors, radius } from '../theme'
import { whyBetter } from './model'
import type { TailorChange, TailoredResumeResponse } from './types'

export function ResultView({ result, language, t, children }: { result: TailoredResumeResponse; language: string; t: TFunction; children?: ReactNode }) {
  const changes = result.changes ?? []
  return (
    <View style={styles.panel}>
      <Text role="heading" style={styles.panelTitle}>
        {t('tailor.result.title')}
      </Text>
      {children}
      <View style={styles.badges}>
        <Badge text={t('tailor.result.fileReady')} tone="good" />
        <Badge text={t('tailor.result.appliedCount', { count: result.appliedChangeCount })} />
        <Badge text={t('tailor.result.rejectedCount', { count: result.rejectedChangeCount })} />
        {result.status ? <Badge text={t(`tailor.status.${result.status}`, { defaultValue: result.status })} /> : null}
      </View>
      {result.unchanged ? <Notice tone="info">{t('tailor.result.unchanged')}</Notice> : null}
      {result.pdfExportStatus === 'FAILED' ? <Text style={styles.hint}>{t('tailor.result.pdfFailed')}</Text> : null}
      {changes.length === 0 ? <Text style={styles.panelBody}>{t('tailor.change.noChanges')}</Text> : null}
      {changes.map((change) => (
        <ChangeCard key={change.nodeId} change={change} language={language} t={t} showApplied />
      ))}
      <RejectedList result={result} t={t} />
    </View>
  )
}

export function ChangeCard({ change, language, t, showApplied }: { change: TailorChange; language: string; t: TFunction; showApplied: boolean }) {
  const why = whyBetter(change, language)
  const evidence = evidenceLabel(change, t)
  return (
    <View style={styles.change}>
      {showApplied ? (
        <Badge text={change.applied ? t('tailor.change.applied') : t('tailor.change.notApplied')} tone={change.applied ? 'good' : 'muted'} />
      ) : null}
      {why ? (
        <Text style={styles.panelBody}>
          <Text style={styles.strong}>{t('tailor.change.whyBetter')}：</Text>
          {why}
        </Text>
      ) : null}
      <Text style={styles.label}>{t('tailor.change.original')}</Text>
      <Text style={styles.quote}>{change.originalText}</Text>
      <Text style={styles.label}>{t('tailor.change.rewritten')}</Text>
      <Text style={[styles.quote, styles.rewritten]}>{change.newText}</Text>
      {evidence ? <Text style={[styles.hint, change.evidenceType === 'INFERRED' && { color: colors.danger }]}>{evidence}</Text> : null}
      {change.evidenceQuote?.trim() ? <Text style={styles.hint}>{t('tailor.change.quote', { quote: change.evidenceQuote.trim() })}</Text> : null}
    </View>
  )
}

function evidenceLabel(change: TailorChange, t: TFunction): string | null {
  switch (change.evidenceType) {
    case 'VERIFIED':
      return t('tailor.change.evidence.VERIFIED')
    case 'USER_EDITED':
      return t('tailor.change.evidence.USER_EDITED')
    case 'INFERRED':
      return change.inferredSkill?.trim()
        ? t('tailor.change.evidence.INFERRED', { skill: change.inferredSkill.trim() })
        : t('tailor.change.evidence.INFERRED_NO_SKILL')
    default:
      return null
  }
}

export function RejectedList({ result, t }: { result: Pick<TailoredResumeResponse, 'rejectedChanges'>; t: TFunction }) {
  const rejected = result.rejectedChanges ?? []
  if (rejected.length === 0) {
    return null
  }
  return (
    <View style={styles.rejected}>
      <Text style={styles.label}>{t('tailor.rejected.title', { count: rejected.length })}</Text>
      <Text style={styles.hint}>{t('tailor.rejected.summary')}</Text>
      {rejected.map((item, index) => (
        <Text key={`${item.nodeId ?? 'none'}-${item.reasonCode}-${index}`} style={styles.hint}>
          · {t(`tailor.reasonCode.${item.reasonCode}`, { defaultValue: t('tailor.rejected.unknownCode', { code: item.reasonCode }) })}
        </Text>
      ))}
    </View>
  )
}

export function Badge({ text, tone = 'muted' }: { text: string; tone?: 'good' | 'muted' }) {
  return (
    <View style={[styles.badge, tone === 'good' ? styles.badgeGood : styles.badgeMuted]}>
      <Text style={[styles.badgeText, tone === 'good' ? { color: '#067647' } : { color: colors.ink2 }]}>{text}</Text>
    </View>
  )
}

export const tailorStyles = StyleSheet.create({
  panel: { gap: 10, marginTop: 16, padding: 16, borderRadius: radius.card, borderWidth: 1, borderColor: colors.border, backgroundColor: colors.card },
  panelTitle: { fontSize: 16, fontWeight: '700', color: colors.ink },
  panelBody: { fontSize: 14, lineHeight: 20, color: colors.ink2 },
  hint: { fontSize: 13, lineHeight: 18, color: colors.muted },
  label: { fontSize: 12, fontWeight: '700', color: colors.muted },
  quote: { fontSize: 14, lineHeight: 20, color: colors.ink, padding: 10, borderRadius: radius.chip, backgroundColor: colors.surface },
  rewritten: { backgroundColor: '#EAF3FF' },
  card: { gap: 8, padding: 12, borderRadius: radius.tile, borderWidth: 1, borderColor: colors.hairline },
  block: { gap: 10, marginTop: 16 },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
})

const styles = StyleSheet.create({
  panel: tailorStyles.panel,
  panelTitle: tailorStyles.panelTitle,
  panelBody: tailorStyles.panelBody,
  strong: { fontWeight: '700' },
  hint: tailorStyles.hint,
  label: tailorStyles.label,
  quote: tailorStyles.quote,
  rewritten: tailorStyles.rewritten,
  change: { gap: 6, padding: 12, borderRadius: radius.tile, borderWidth: 1, borderColor: colors.hairline },
  rejected: { gap: 4, paddingTop: 6 },
  badges: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
  badge: { alignSelf: 'flex-start', paddingHorizontal: 10, paddingVertical: 4, borderRadius: radius.pill },
  badgeGood: { backgroundColor: '#E3F6EC' },
  badgeMuted: { backgroundColor: '#EEF1F6' },
  badgeText: { fontSize: 12, fontWeight: '600' },
})
