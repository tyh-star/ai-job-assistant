import { useTranslation } from 'react-i18next'
import type { NormalizedResume } from '../api/types'

interface PreviewPanelProps {
  preview: NormalizedResume | null
  loading: boolean
}

/** Renders the backend's normalized preview VERBATIM -- the same model the DOCX writer
 * renders from (see NormalizedResume's javadoc on the backend). Never re-derives
 * section content from the raw draft itself, so it can never disagree with the
 * generated file. */
export function PreviewPanel({ preview, loading }: PreviewPanelProps) {
  const { t } = useTranslation()

  if (loading) {
    return <p className="text-sm text-slate-500">{t('common.loading')}</p>
  }
  if (!preview) {
    return <p className="text-sm text-slate-500">{t('resumeBuilder.preview.unavailable')}</p>
  }

  return (
    <div className="space-y-4 rounded-xl border border-slate-200 bg-white p-5 text-sm">
      {!preview.readyToGenerate ? (
        <p className="rounded-lg bg-amber-50 px-3 py-2 text-amber-800">
          {t('resumeBuilder.preview.notReady')}
        </p>
      ) : null}

      {preview.missingKeyFacts.length > 0 ? (
        <div className="rounded-lg bg-slate-50 px-3 py-2 text-slate-700">
          <p className="font-medium">{t('resumeBuilder.preview.missingFacts')}</p>
          <ul className="list-disc pl-5">
            {preview.missingKeyFacts.map((fact) => (
              <li key={fact}>{fact}</li>
            ))}
          </ul>
        </div>
      ) : null}

      {preview.warnings.length > 0 ? (
        <div className="rounded-lg bg-amber-50 px-3 py-2 text-amber-800">
          {preview.warnings.map((warning) => (
            <p key={warning}>{warning}</p>
          ))}
        </div>
      ) : null}

      <div className="border-b border-slate-200 pb-3">
        {preview.header.fullName ? (
          <p className="text-lg font-semibold text-slate-900">{preview.header.fullName}</p>
        ) : (
          <p className="text-lg font-semibold text-slate-400">{t('resumeBuilder.preview.noName')}</p>
        )}
        {preview.header.targetTitle ? <p className="text-slate-700">{preview.header.targetTitle}</p> : null}
        <p className="text-slate-500">
          {[preview.header.location, preview.header.phone, preview.header.email, ...preview.header.links]
            .filter(Boolean)
            .join('  |  ')}
        </p>
      </div>

      {preview.summary ? <p className="whitespace-pre-line text-slate-700">{preview.summary}</p> : null}

      {preview.sections.map((section) => (
        <div key={section.id}>
          <h3 className="border-b border-slate-200 pb-1 text-xs font-semibold uppercase tracking-wide text-slate-500">
            {section.title}
          </h3>
          <div className="mt-2 space-y-3">
            {section.blocks.map((block, index) => (
              <div key={index}>
                {block.heading || block.meta ? (
                  <div className="flex items-baseline justify-between gap-2">
                    <span className="font-medium text-slate-800">{block.heading}</span>
                    <span className="text-xs text-slate-500">{block.meta}</span>
                  </div>
                ) : null}
                {block.bulletedLines ? (
                  <ul className="mt-1 list-disc space-y-0.5 pl-5 text-slate-700">
                    {block.lines.map((line, lineIndex) => (
                      <li key={lineIndex}>{line}</li>
                    ))}
                  </ul>
                ) : (
                  block.lines.map((line, lineIndex) => (
                    <p key={lineIndex} className="mt-1 text-slate-700">
                      {line}
                    </p>
                  ))
                )}
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  )
}
