import { useRef, useState } from 'react'
import { Text, View } from 'react-native'
import { useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { draftsApi } from '../api/endpoints'
import { builderErrorKey, isNotFound, mayBeMissingOnServer, requestDiagnostic } from '../api/errors'
import { Notice } from '../components/AuthShell'
import { Button } from '../components/Button'
import { DetailLayout, detailStyles } from '../components/DetailLayout'
import { afterDraftWritten } from '../builder/cache'
import { ChoiceChips, FormInput } from '../builder/fields'
import { LIMITS } from '../builder/model'
import { useSessionGuard } from '../builder/sessionGuard'
import { RESUME_LOCALES, TEMPLATE_TYPES, type ResumeLocale, type TemplateType } from '../builder/types'

type Props = {
  onBack: () => void
  onCreated: (draftId: number) => void
}

/** POST /resume-builder/drafts: a title, a template and the resume language. Nothing else is filled in. */
export function CreateDraftScreen({ onBack, onCreated }: Props) {
  const { t, i18n } = useTranslation()
  const queryClient = useQueryClient()
  const guard = useSessionGuard()
  const [title, setTitle] = useState('')
  const [templateType, setTemplateType] = useState<TemplateType>('GENERIC_ATS')
  const [locale, setLocale] = useState<ResumeLocale>(i18n.language.startsWith('zh') ? 'zh-CN' : 'en')
  const [submitting, setSubmitting] = useState(false)
  const [failure, setFailure] = useState<string | null>(null)
  const inFlight = useRef(false)

  const tooLong = title.trim().length > LIMITS.title

  async function submit() {
    if (inFlight.current || tooLong) {
      return
    }
    inFlight.current = true
    const ticket = guard.capture()
    setSubmitting(true)
    setFailure(null)
    try {
      const created = await draftsApi.create({ title: title.trim() || t('builder.untitled'), templateType, locale })
      if (!ticket.isCurrent()) {
        return
      }
      await afterDraftWritten(queryClient, ticket.userId, created)
      if (ticket.isCurrent()) {
        onCreated(created.id)
      }
    } catch (error) {
      if (ticket.isCurrent()) {
        // There is no draft yet, so a 404 here means the route itself is missing, never "draft gone".
        const reason = isNotFound(error) ? t('builder.errors.unavailable') : t(builderErrorKey(error))
        const hint = mayBeMissingOnServer(error) ? t('resumes.drafts.serverHint') : null
        setFailure([t('builder.create.failed', { reason }), hint, requestDiagnostic(error)].filter(Boolean).join('\n'))
      }
    } finally {
      inFlight.current = false
      if (ticket.isCurrent()) {
        setSubmitting(false)
      }
    }
  }

  return (
    <DetailLayout
      onBack={onBack}
      actions={
        <>
          {failure ? <Notice tone="error">{failure}</Notice> : null}
          <Button
            label={submitting ? t('builder.create.submitting') : t('builder.create.submit')}
            onPress={() => void submit()}
            loading={submitting}
            disabled={tooLong}
          />
        </>
      }
    >
      <Text role="heading" style={detailStyles.title}>
        {t('builder.create.title')}
      </Text>
      <Text style={[detailStyles.muted, { marginTop: 6 }]}>{t('builder.create.intro')}</Text>

      <View style={[detailStyles.section, { gap: 16 }]}>
        <FormInput
          label={t('builder.create.titleLabel')}
          value={title}
          onChangeText={setTitle}
          placeholder={t('builder.untitled')}
          error={tooLong ? t('builder.validation.tooLong', { max: LIMITS.title }) : null}
        />
        <ChoiceChips
          label={t('builder.fields.templateType')}
          value={templateType}
          onChange={setTemplateType}
          disabled={submitting}
          options={TEMPLATE_TYPES.map((value) => ({ value, label: t(`resumes.templates.${value}`) }))}
        />
        <Text style={detailStyles.hint}>{t(`builder.templateHints.${templateType}`)}</Text>
        <ChoiceChips
          label={t('builder.fields.locale')}
          value={locale}
          onChange={setLocale}
          disabled={submitting}
          options={RESUME_LOCALES.map((value) => ({ value, label: t(`builder.locales.${value}`) }))}
        />
      </View>
    </DetailLayout>
  )
}
