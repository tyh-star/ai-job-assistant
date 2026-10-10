import { StyleSheet, Text, View } from 'react-native'
import { useTranslation } from 'react-i18next'
import { apiErrorKey, isNotFound } from '../api/errors'
import { DetailLayout, detailStyles } from '../components/DetailLayout'
import { Skeleton, StateView } from '../components/feedback'
import { displayFileName } from '../resumes/displayName'
import { useQualityScore, useResumeFile } from '../resumes/queries'
import { colors, radius } from '../theme'
import { Badges } from './CatalogJobDetailScreen'

type Props = {
  /** A file id from GET /resumes -- a draft id is never passed here. */
  resumeId: number | null
  onBack: () => void
}

export function QualityScoreScreen({ resumeId, onBack }: Props) {
  const { t } = useTranslation()
  const file = useResumeFile(resumeId)
  const query = useQualityScore(resumeId)

  if (resumeId === null) {
    return (
      <DetailLayout onBack={onBack}>
        <StateView tone="error" title={t('resumes.detail.notFoundTitle')} body={t('resumes.detail.notFoundBody')} />
      </DetailLayout>
    )
  }

  const heading = (
    <>
      <Text role="heading" style={detailStyles.title}>
        {t('quality.title')}
      </Text>
      {file.data ? <Text style={detailStyles.company}>{displayFileName(file.data.fileName)}</Text> : null}
    </>
  )

  if (query.isPending) {
    return (
      <DetailLayout onBack={onBack}>
        {heading}
        <View aria-busy style={{ gap: 10, marginTop: 16 }}>
          <Skeleton width="100%" height={120} style={{ borderRadius: radius.card }} />
          <Skeleton width="100%" height={200} style={{ borderRadius: radius.card }} />
        </View>
      </DetailLayout>
    )
  }

  if (query.isError) {
    const missing = isNotFound(query.error)
    return (
      <DetailLayout onBack={onBack}>
        {heading}
        <View style={{ marginTop: 16 }}>
          <StateView
            tone="error"
            title={missing ? t('resumes.detail.notFoundTitle') : t('quality.errorTitle')}
            body={missing ? t('resumes.detail.notFoundBody') : t(apiErrorKey(query.error))}
            actionLabel={missing ? t('resumes.detail.back') : t('resumes.retry')}
            onAction={missing ? onBack : () => void query.refetch()}
          />
        </View>
      </DetailLayout>
    )
  }

  const result = query.data
  // A result the backend could not score is its own state, never a zero.
  if (!result.scorable || result.score === null) {
    const reason = result.reasonCode
    return (
      <DetailLayout onBack={onBack}>
        {heading}
        <View style={{ marginTop: 16 }}>
          <StateView
            tone="empty"
            title={t('quality.unscorableTitle')}
            body={reason ? t(`quality.reason.${reason}`, { defaultValue: reason }) : undefined}
          />
        </View>
      </DetailLayout>
    )
  }

  const categories = result.categories ?? []
  const strengths = result.strengths ?? []
  const suggestions = result.suggestions ?? []

  return (
    <DetailLayout onBack={onBack}>
      {heading}

      <View style={[detailStyles.section, styles.scoreCard]}>
        <Text style={styles.score} aria-label={t('quality.scoreOutOf', { score: result.score })}>
          {t('quality.scoreOutOf', { score: result.score })}
        </Text>
        {result.level ? (
          <Badges labels={[{ label: t(`quality.level.${result.level}`, { defaultValue: result.level }), tone: 'saved' }]} />
        ) : null}
        {result.rubricVersion ? <Text style={styles.rubric}>{t('quality.rubric', { version: result.rubricVersion })}</Text> : null}
        <Text style={styles.disclaimer}>{t('quality.disclaimer')}</Text>
      </View>

      {categories.length > 0 ? (
        <View style={detailStyles.section}>
          <Text role="heading" style={detailStyles.sectionTitle}>
            {t('quality.categoriesTitle')}
          </Text>
          {categories.map((category) => {
            const fraction = category.maxScore > 0 ? Math.max(0, Math.min(1, category.score / category.maxScore)) : 0
            return (
              <View key={category.code} style={styles.category}>
                <View style={styles.categoryHead}>
                  <Text style={styles.categoryName}>{t(`quality.category.${category.code}`, { defaultValue: category.code })}</Text>
                  <Text style={styles.categoryScore}>
                    {t('quality.categoryScore', { score: category.score, max: category.maxScore })}
                  </Text>
                </View>
                <View style={styles.track}>
                  <View style={[styles.fill, { width: `${Math.round(fraction * 100)}%` }]} />
                </View>
              </View>
            )
          })}
        </View>
      ) : null}

      <View style={detailStyles.section}>
        <Text role="heading" style={detailStyles.sectionTitle}>
          {t('quality.strengthsTitle')}
        </Text>
        {strengths.length > 0 ? (
          strengths.map((code) => (
            <Text key={code} style={styles.listItem}>
              • {t(`quality.strength.${code}`, { defaultValue: code })}
            </Text>
          ))
        ) : (
          <Text style={detailStyles.muted}>{t('quality.noStrengths')}</Text>
        )}
      </View>

      <View style={detailStyles.section}>
        <Text role="heading" style={detailStyles.sectionTitle}>
          {t('quality.suggestionsTitle')}
        </Text>
        {suggestions.length > 0 ? (
          suggestions.map((code) => (
            <Text key={code} style={styles.listItem}>
              • {t(`quality.suggestion.${code}`, { defaultValue: code })}
            </Text>
          ))
        ) : (
          <Text style={detailStyles.muted}>{t('quality.noSuggestions')}</Text>
        )}
      </View>
    </DetailLayout>
  )
}

const styles = StyleSheet.create({
  scoreCard: { alignItems: 'flex-start' },
  score: { fontSize: 40, fontWeight: '700', color: colors.ink, letterSpacing: -1 },
  rubric: { marginTop: 10, fontSize: 12, color: colors.subtle },
  disclaimer: { marginTop: 8, fontSize: 13, lineHeight: 19, color: colors.muted },
  category: { paddingVertical: 8, gap: 6 },
  categoryHead: { flexDirection: 'row', justifyContent: 'space-between', gap: 12 },
  categoryName: { flex: 1, fontSize: 14, color: colors.ink },
  categoryScore: { fontSize: 14, fontWeight: '600', color: colors.blueStrong, fontVariant: ['tabular-nums'] },
  track: { height: 6, borderRadius: 3, backgroundColor: '#DCE8F7', overflow: 'hidden' },
  fill: { height: 6, borderRadius: 3, backgroundColor: colors.blue },
  listItem: { paddingVertical: 4, fontSize: 15, lineHeight: 22, color: colors.ink2 },
})
