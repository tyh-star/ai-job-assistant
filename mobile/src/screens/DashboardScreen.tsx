import type { ReactNode } from 'react'
import { Alert, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import { LinearGradient } from 'expo-linear-gradient'
import Svg, { Path } from 'react-native-svg'
import { useTranslation } from 'react-i18next'
import type { JobResponse, ResumeResponse } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Button } from '../components/Button'
import {
  BrandGlyph,
  BriefcaseIcon,
  CheckIcon,
  ChevronRightIcon,
  ClockIcon,
  FileIcon,
  SearchIcon,
} from '../components/Icons'
import { TextLink } from '../components/TextLink'
import {
  buildResumeSlice,
  deriveView,
  firstName,
  isDraftReady,
  newestDraft,
  qualityTargetId,
  type JobsSection,
  type PrimaryResume,
  type ResumeSection,
} from '../dashboard/model'
import { colors, gradients, radius, space } from '../theme'
import { formatMonthDay, formatShortDate } from '../utils/dates'
import { useMyJobs } from '../jobs/queries'
import { displayFileName } from '../resumes/displayName'
import { useDraft, useDrafts, useResumeFiles } from '../resumes/queries'

/** Where a dashboard control leads; the route layer maps these to screens. */
export type DashboardTarget =
  | { kind: 'catalog' }
  | { kind: 'saved' }
  | { kind: 'applied' }
  /** A personal job record from GET /jobs, by its numeric id. */
  | { kind: 'job'; jobId: number }
  | { kind: 'resumes' }
  /** Opens the resumes tab straight into the document picker. */
  | { kind: 'upload' }
  /** An uploaded or generated file from GET /resumes, by its numeric file id. */
  | { kind: 'resumeFile'; resumeId: number }
  /** Quality score for a file id -- never a draft id. */
  | { kind: 'quality'; resumeId: number }
  /** A resume-builder draft, by its numeric draft id. */
  | { kind: 'draft'; draftId: number }
  | { kind: 'tailor' }

const LIST_LIMIT = 3

type Props = { onOpen: (target: DashboardTarget) => void }

export function DashboardScreen({ onOpen }: Props) {
  const { t } = useTranslation()
  const { user, logout } = useAuth()
  // The same caches the resumes and jobs tabs read, so their changes show up here at once.
  const filesQuery = useResumeFiles()
  const draftsQuery = useDrafts()
  const jobsQuery = useMyJobs()
  const leadDraft = draftsQuery.data ? newestDraft(draftsQuery.data) : null
  const leadDraftDetail = useDraft(leadDraft?.id ?? null)
  const isPending =
    filesQuery.isPending ||
    draftsQuery.isPending ||
    jobsQuery.isPending ||
    (leadDraft !== null && leadDraftDetail.isPending)
  const leadReady =
    leadDraft !== null && leadDraftDetail.data
      ? isDraftReady(leadDraftDetail.data.content, leadDraftDetail.data.targetRole)
      : null
  const view = isPending
    ? null
    : deriveView({
        resumes:
          filesQuery.isError || draftsQuery.isError
            ? null
            : buildResumeSlice(draftsQuery.data ?? [], filesQuery.data ?? [], leadReady),
        jobs: jobsQuery.isError ? null : (jobsQuery.data ?? null),
      })
  const refreshing =
    filesQuery.isRefetching || draftsQuery.isRefetching || jobsQuery.isRefetching || leadDraftDetail.isRefetching
  const refetchAll = () => {
    void filesQuery.refetch()
    void draftsQuery.refetch()
    void jobsQuery.refetch()
    if (leadDraft !== null) {
      void leadDraftDetail.refetch()
    }
  }
  const name = firstName(user?.name)

  function openAccountMenu() {
    Alert.alert(user?.name ?? t('account.menuHint'), user?.email, [
      { text: t('account.cancel'), style: 'cancel' },
      { text: t('nav.logout'), style: 'destructive', onPress: () => void logout() },
    ])
  }

  return (
    <SafeAreaView style={styles.safe} edges={['top']}>
      <ScrollView
        contentContainerStyle={styles.scroll}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={refetchAll}
            tintColor={colors.blue}
            colors={[colors.blue]}
          />
        }
      >
        <View style={styles.topBar}>
          <LinearGradient colors={gradients.brand} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={styles.brandMark}>
            <BrandGlyph size={17} />
          </LinearGradient>
          <Text style={styles.brand}>{t('brand')}</Text>
          <Pressable
            role="button"
            aria-label={t('account.menuHint')}
            onPress={openAccountMenu}
            hitSlop={6}
            style={styles.avatarHit}
          >
            <LinearGradient colors={['#1D93FC', '#2461DC']} style={styles.avatar}>
              <Text style={styles.avatarText}>{(name[0] ?? '?').toUpperCase()}</Text>
            </LinearGradient>
          </Pressable>
        </View>

        <View style={styles.greeting}>
          <Text role="heading" style={styles.h1} numberOfLines={1}>
            {t('dashboard.welcome', { name })}
          </Text>
          <Text style={styles.subtitle}>
            {view?.kind === 'onboarding' ? t('dashboard.newSubtitle') : t('dashboard.subtitle')}
          </Text>
        </View>

        {isPending ? <LoadingView /> : null}
        {view?.kind === 'failed' ? <FailedView onRetry={refetchAll} /> : null}
        {view?.kind === 'onboarding' ? <Onboarding onOpen={onOpen} /> : null}
        {view?.kind === 'workbench' ? (
          <View style={styles.stack}>
            <ResumeBlock section={view.resume} onOpen={onOpen} onRetry={refetchAll} />
            <JobsBlock section={view.jobs} onOpen={onOpen} onRetry={refetchAll} />
            <HistoryBlock section={view.jobs} onOpen={onOpen} />
          </View>
        ) : null}
      </ScrollView>
    </SafeAreaView>
  )
}

/* ------------------------------------------------------------------ pieces */

function Tile({ children, size = 40 }: { children: ReactNode; size?: number }) {
  return <View style={[styles.tile, { width: size, height: size }]}>{children}</View>
}

function Card({ children, tone = 'quiet' }: { children: ReactNode; tone?: 'resume' | 'quiet' }) {
  return (
    <LinearGradient
      colors={tone === 'resume' ? gradients.resumeCard : gradients.quietCard}
      start={{ x: 0, y: 0 }}
      end={{ x: 1, y: 1 }}
      style={styles.card}
    >
      {children}
    </LinearGradient>
  )
}

/** One compact row for a section with nothing (or nothing loadable) in it. */
function CompactRow({
  icon,
  title,
  text,
  action,
}: {
  icon: ReactNode
  title: string
  text: string
  action?: { label: string; onPress: () => void }
}) {
  return (
    <View style={styles.compact}>
      <Tile>{icon}</Tile>
      <View style={styles.compactText}>
        <Text style={styles.compactTitle}>{title}</Text>
        <Text style={styles.compactBody}>{text}</Text>
      </View>
      {action ? <TextLink label={action.label} onPress={action.onPress} /> : null}
    </View>
  )
}

function DocThumb({ name }: { name: string }) {
  return (
    <View style={styles.doc} aria-hidden>
      <Svg width={16} height={16} style={styles.docFold}>
        <Path d="M0 0H16V16Z" fill="#C7DFFF" />
      </Svg>
      <Text style={styles.docName} numberOfLines={1}>
        {name}
      </Text>
      {[0, 1].map((row) => (
        <View key={row} style={styles.docPair}>
          <View style={[styles.docLine, { width: '56%' }]} />
          <View style={[styles.docLine, { width: '30%' }]} />
        </View>
      ))}
      {['100%', '90%', '70%', '88%', '52%'].map((width, index) => (
        <View key={index} style={[styles.docLine, styles.docLineStacked, { width: width as `${number}%` }]} />
      ))}
    </View>
  )
}

/* ------------------------------------------------------------ resume center */

function ResumeBlock({
  section,
  onOpen,
  onRetry,
}: {
  section: ResumeSection
  onOpen: (target: DashboardTarget) => void
  onRetry: () => void
}) {
  const { t } = useTranslation()
  const { user } = useAuth()

  if (section.kind === 'failed') {
    return (
      <CompactRow
        icon={<FileIcon />}
        title={t('dashboard.resumeCenter')}
        text={t('dashboard.resumeFailed')}
        action={{ label: t('dashboard.reload'), onPress: onRetry }}
      />
    )
  }
  if (section.kind === 'empty') {
    return (
      <CompactRow
        icon={<FileIcon />}
        title={t('dashboard.resumeCenter')}
        text={t('dashboard.noResumes')}
        action={{ label: t('dashboard.createResume'), onPress: () => onOpen({ kind: 'upload' }) }}
      />
    )
  }

  // Only a real file id may open a quality score; a draft id never does.
  const qualityId = qualityTargetId(section)

  return (
    <Card tone="resume">
      <View style={styles.cardHead}>
        <Text role="heading" style={styles.cardTitle}>
          {t('dashboard.resumeCenter')}
        </Text>
        <View style={styles.spacer} />
        <TextLink label={t('dashboard.manageAll')} arrow onPress={() => onOpen({ kind: 'resumes' })} />
      </View>

      <PrimaryResumeRow primary={section.primary} ownerName={user?.name ?? ''} />

      <View style={styles.buttons}>
        <Button label={t('dashboard.tailorByJob')} onPress={() => onOpen({ kind: 'tailor' })} style={styles.flexButton} />
        <Button
          variant="secondary"
          label={section.primary.kind === 'draft' ? t('dashboard.editResume') : t('dashboard.createResume')}
          onPress={() =>
            onOpen(
              section.primary.kind === 'draft'
                ? { kind: 'draft', draftId: section.primary.draft.id }
                : { kind: 'resumes' },
            )
          }
          style={styles.flexButton}
        />
      </View>
      {qualityId !== null ? (
        <TextLink
          label={t('dashboard.qualityScore')}
          onPress={() => onOpen({ kind: 'quality', resumeId: qualityId })}
          style={styles.quality}
        />
      ) : null}

      {section.secondary ? (
        <SecondaryResumeRow
          resume={section.secondary}
          onPress={() => onOpen({ kind: 'resumeFile', resumeId: (section.secondary as { id: number }).id })}
        />
      ) : null}
    </Card>
  )
}

function PrimaryResumeRow({ primary, ownerName }: { primary: PrimaryResume; ownerName: string }) {
  const { t, i18n } = useTranslation()
  const title = primary.kind === 'draft' ? primary.draft.title : displayFileName(primary.upload.fileName)
  const kindLabel =
    primary.kind === 'draft'
      ? t(`dashboard.templates.${primary.draft.templateType}`, { defaultValue: String(primary.draft.templateType) })
      : primary.upload.fileType
  const date = formatMonthDay(primary.kind === 'draft' ? primary.draft.updatedAt : primary.upload.updatedAt, i18n.language)
  const meta = [kindLabel, date ? t('dashboard.updatedOn', { date }) : ''].filter(Boolean).join(' · ')

  return (
    <View style={styles.resumeRow}>
      <DocThumb name={ownerName} />
      <View style={styles.resumeInfo}>
        <Text style={styles.resumeTitle} numberOfLines={2}>
          {title}
        </Text>
        {meta ? <Text style={styles.resumeMeta}>{meta}</Text> : null}
        {primary.kind === 'draft' && primary.ready ? (
          <View style={styles.ready}>
            <View style={styles.readyDot}>
              <CheckIcon size={11} />
            </View>
            <Text style={styles.readyText}>{t('dashboard.readyStatus')}</Text>
          </View>
        ) : null}
      </View>
    </View>
  )
}

function SecondaryResumeRow({ resume, onPress }: { resume: ResumeResponse; onPress: () => void }) {
  const { t, i18n } = useTranslation()
  const date = formatMonthDay(resume.updatedAt, i18n.language)
  return (
    <Pressable
      role="link"
      aria-label={displayFileName(resume.fileName)}
      onPress={onPress}
      style={({ pressed }) => [styles.secondary, pressed && styles.pressed]}
    >
      <Tile>
        <FileIcon />
      </Tile>
      <View style={styles.rowText}>
        <Text style={styles.rowTitle} numberOfLines={1}>
          {displayFileName(resume.fileName)}
        </Text>
        <Text style={styles.rowMeta} numberOfLines={1}>
          {[resume.fileType, date ? t('dashboard.updatedOn', { date }) : ''].filter(Boolean).join(' · ')}
        </Text>
      </View>
      <ChevronRightIcon />
    </Pressable>
  )
}

/* --------------------------------------------------------------------- jobs */

function JobsBlock({
  section,
  onOpen,
  onRetry,
}: {
  section: JobsSection
  onOpen: (target: DashboardTarget) => void
  onRetry: () => void
}) {
  const { t } = useTranslation()

  if (section.kind === 'failed') {
    return (
      <CompactRow
        icon={<BriefcaseIcon />}
        title={t('dashboard.jobs')}
        text={t('dashboard.jobsFailed')}
        action={{ label: t('dashboard.reload'), onPress: onRetry }}
      />
    )
  }
  if (section.saved.length === 0) {
    return (
      <CompactRow
        icon={<BriefcaseIcon />}
        title={t('dashboard.jobs')}
        text={t('dashboard.noSaved')}
        action={{ label: t('dashboard.findJobs'), onPress: () => onOpen({ kind: 'catalog' }) }}
      />
    )
  }

  const shown = section.saved.slice(0, LIST_LIMIT)
  return (
    <Card>
      <View style={styles.cardHead}>
        <Tile>
          <BriefcaseIcon />
        </Tile>
        <Text role="heading" style={styles.cardTitle}>
          {t('dashboard.jobs')}
        </Text>
        <View style={styles.badge}>
          <Text style={styles.badgeText}>{t('dashboard.savedBadge', { n: section.saved.length })}</Text>
        </View>
        <View style={styles.spacer} />
        <Button
          variant="secondary"
          size="sm"
          label={t('dashboard.findJobs')}
          icon={<SearchIcon size={15} />}
          onPress={() => onOpen({ kind: 'catalog' })}
        />
      </View>

      <Text style={styles.label}>{t('dashboard.continueSaved')}</Text>
      {shown.map((job, index) => (
        <SavedJobRow key={job.id} job={job} first={index === 0} onPress={() => onOpen({ kind: 'job', jobId: job.id })} />
      ))}
      {section.saved.length > LIST_LIMIT ? (
        <TextLink label={t('dashboard.viewAll')} arrow onPress={() => onOpen({ kind: 'saved' })} />
      ) : null}
    </Card>
  )
}

function SavedJobRow({ job, first, onPress }: { job: JobResponse; first: boolean; onPress: () => void }) {
  const detail = [job.company, job.location].filter((part) => part?.trim()).join(' · ')
  return (
    <Pressable
      role="link"
      aria-label={job.title}
      onPress={onPress}
      style={({ pressed }) => [styles.savedRow, !first && styles.rowDivider, pressed && styles.pressed]}
    >
      <View style={styles.savedBar} />
      <View style={styles.rowText}>
        <Text style={styles.rowTitle} numberOfLines={1}>
          {job.title}
        </Text>
        {detail ? (
          <Text style={styles.rowMeta} numberOfLines={1}>
            {detail}
          </Text>
        ) : null}
      </View>
      <ChevronRightIcon />
    </Pressable>
  )
}

/* ------------------------------------------------------ recent applications */

function HistoryBlock({ section, onOpen }: { section: JobsSection; onOpen: (target: DashboardTarget) => void }) {
  const { t } = useTranslation()

  // The jobs block already reports a failed GET /jobs; saying it twice adds noise.
  if (section.kind === 'failed') {
    return null
  }
  if (section.applied.length === 0) {
    return <CompactRow icon={<ClockIcon />} title={t('dashboard.recentApplied')} text={t('dashboard.noApplied')} />
  }

  const shown = section.applied.slice(0, LIST_LIMIT)
  return (
    <Card>
      <View style={styles.cardHead}>
        <Tile>
          <ClockIcon />
        </Tile>
        <Text role="heading" style={styles.cardTitle}>
          {t('dashboard.recentApplied')}
        </Text>
        <View style={styles.spacer} />
        <TextLink label={t('dashboard.viewAll')} arrow onPress={() => onOpen({ kind: 'applied' })} />
      </View>

      <View style={styles.timeline}>
        {shown.map((job, index) => {
          const last = index === shown.length - 1
          return (
            <Pressable
              key={job.id}
              role="link"
              aria-label={job.title}
              onPress={() => onOpen({ kind: 'job', jobId: job.id })}
              style={({ pressed }) => [styles.tlRow, pressed && styles.pressed]}
            >
              <View style={styles.tlRail}>
                <View style={styles.tlChip}>
                  <Text style={styles.tlChipText}>{formatShortDate(job.appliedAt ?? job.updatedAt)}</Text>
                </View>
                {last ? null : <View style={styles.tlLine} />}
              </View>
              <View style={[styles.tlText, !last && styles.tlTextDivider]}>
                <Text style={styles.rowTitle} numberOfLines={1}>
                  {job.title}
                </Text>
                {job.company?.trim() ? (
                  <Text style={styles.rowMeta} numberOfLines={1}>
                    {job.company}
                  </Text>
                ) : null}
              </View>
            </Pressable>
          )
        })}
      </View>
    </Card>
  )
}

/* -------------------------------------------------------------- first run */

function Onboarding({ onOpen }: { onOpen: (target: DashboardTarget) => void }) {
  const { t } = useTranslation()
  const steps = [
    { title: t('dashboard.step1Title'), body: t('dashboard.step1Body') },
    { title: t('dashboard.step2Title'), body: t('dashboard.step2Body') },
    { title: t('dashboard.step3Title'), body: t('dashboard.step3Body') },
  ]

  return (
    <View style={styles.onboard}>
      <Text role="heading" style={styles.onboardTitle}>
        {t('dashboard.onboardTitle')}
      </Text>
      <Text style={styles.onboardBody}>{t('dashboard.onboardBody')}</Text>

      <Text style={styles.progressText}>{t('dashboard.onboardProgress')}</Text>
      <View style={styles.progressTrack} />

      <View style={styles.steps}>
        {steps.map((step, index) => {
          const current = index === 0
          return (
            <View key={step.title} style={[styles.step, index > 0 && styles.rowDivider]}>
              <View style={[styles.stepNum, current && styles.stepNumCurrent]}>
                <Text style={[styles.stepNumText, current && styles.stepNumTextCurrent]}>{index + 1}</Text>
              </View>
              <View style={styles.rowText}>
                <View style={styles.stepTitleRow}>
                  <Text style={[styles.stepTitle, !current && styles.stepTitleLater]}>{step.title}</Text>
                  {current ? (
                    <View style={styles.pill}>
                      <Text style={styles.pillText}>{t('dashboard.currentStep')}</Text>
                    </View>
                  ) : null}
                </View>
                <Text style={styles.stepBody}>{step.body}</Text>
                {current ? (
                  <Button
                    label={t('dashboard.createOrUpload')}
                    onPress={() => onOpen({ kind: 'upload' })}
                    style={styles.stepButton}
                  />
                ) : null}
              </View>
            </View>
          )
        })}
      </View>

      <TextLink label={t('dashboard.altFindJobs')} arrow onPress={() => onOpen({ kind: 'catalog' })} />
    </View>
  )
}

/* ------------------------------------------------------ loading and errors */

function LoadingView() {
  const { t } = useTranslation()
  return (
    <View style={styles.stack} aria-busy aria-label={t('common.loading')}>
      <View style={[styles.card, styles.skeletonCard]}>
        <View style={[styles.skel, { width: '38%', height: 18 }]} />
        <View style={styles.resumeRow}>
          <View style={[styles.skel, { width: 76, height: 98, borderRadius: 8 }]} />
          <View style={[styles.resumeInfo, { gap: 8 }]}>
            <View style={[styles.skel, { width: '90%', height: 16 }]} />
            <View style={[styles.skel, { width: '60%', height: 12 }]} />
          </View>
        </View>
        <View style={styles.buttons}>
          <View style={[styles.skel, styles.flexButton, { height: 48, borderRadius: radius.button }]} />
          <View style={[styles.skel, styles.flexButton, { height: 48, borderRadius: radius.button }]} />
        </View>
      </View>
      {[0, 1].map((key) => (
        <View key={key} style={styles.compact}>
          <View style={[styles.skel, { width: 40, height: 40, borderRadius: radius.tile }]} />
          <View style={[styles.compactText, { gap: 6 }]}>
            <View style={[styles.skel, { width: '30%', height: 14 }]} />
            <View style={[styles.skel, { width: '64%', height: 12 }]} />
          </View>
        </View>
      ))}
    </View>
  )
}

function FailedView({ onRetry }: { onRetry: () => void }) {
  const { t } = useTranslation()
  return (
    <View style={styles.onboard} role="alert">
      <Text role="heading" style={styles.failTitle}>
        {t('dashboard.errorTitle')}
      </Text>
      <Text style={styles.onboardBody}>{t('dashboard.errorBody')}</Text>
      <Button variant="secondary" label={t('dashboard.reload')} onPress={onRetry} style={styles.failButton} />
    </View>
  )
}

/* ------------------------------------------------------------------ styles */

const shadow = {
  shadowColor: '#4083C0',
  shadowOpacity: 0.09,
  shadowRadius: 14,
  shadowOffset: { width: 0, height: 8 },
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  scroll: { paddingHorizontal: space.screenX, paddingBottom: 32 },
  pressed: { opacity: 0.7 },
  spacer: { flex: 1 },
  flexButton: { flex: 1 },

  topBar: { flexDirection: 'row', alignItems: 'center', height: 56, gap: 10 },
  brandMark: { width: 30, height: 30, borderRadius: 9, alignItems: 'center', justifyContent: 'center' },
  brand: { flex: 1, fontSize: 16, fontWeight: '700', color: colors.ink },
  avatarHit: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center' },
  avatar: { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center' },
  avatarText: { color: '#FFFFFF', fontSize: 15, fontWeight: '700' },

  greeting: { marginTop: 10, marginBottom: 18 },
  h1: { fontSize: 28, fontWeight: '700', color: colors.ink, letterSpacing: -0.6 },
  subtitle: { marginTop: 6, fontSize: 15, lineHeight: 21, color: '#5573A9' },

  stack: { gap: space.sectionGap },

  card: {
    borderRadius: radius.card,
    borderWidth: 1,
    borderColor: colors.border,
    padding: space.cardPadding,
    ...shadow,
  },
  cardHead: { flexDirection: 'row', alignItems: 'center', gap: 10, minHeight: 44 },
  cardTitle: { fontSize: 18, fontWeight: '700', color: colors.ink, letterSpacing: -0.3, flexShrink: 1 },
  tile: { borderRadius: radius.tile, backgroundColor: colors.tile, alignItems: 'center', justifyContent: 'center' },
  label: { marginTop: 12, marginBottom: 2, fontSize: 13, color: colors.muted },
  rowText: { flex: 1, minWidth: 0 },
  rowTitle: { fontSize: 15, fontWeight: '500', color: colors.ink },
  rowMeta: { marginTop: 2, fontSize: 13, color: colors.muted },
  rowDivider: { borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: colors.hairline },

  compact: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    minHeight: 72,
    paddingVertical: 12,
    paddingLeft: 14,
    paddingRight: 16,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.surface,
  },
  compactText: { flex: 1, minWidth: 0 },
  compactTitle: { fontSize: 15, fontWeight: '700', color: colors.ink },
  compactBody: { marginTop: 2, fontSize: 13, color: colors.muted },

  resumeRow: { flexDirection: 'row', gap: 14, marginTop: 10 },
  resumeInfo: { flex: 1, minWidth: 0, paddingTop: 4 },
  resumeTitle: { fontSize: 16, lineHeight: 22, fontWeight: '600', color: colors.ink },
  resumeMeta: { marginTop: 4, fontSize: 13, color: colors.muted },
  ready: { flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 10 },
  readyDot: {
    width: 18,
    height: 18,
    borderRadius: 9,
    backgroundColor: colors.greenFill,
    alignItems: 'center',
    justifyContent: 'center',
  },
  readyText: { flexShrink: 1, fontSize: 13, lineHeight: 18, fontWeight: '600', color: colors.green },
  buttons: { flexDirection: 'row', gap: 10, marginTop: 16 },
  quality: { alignSelf: 'flex-start', marginTop: 2 },
  secondary: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    marginTop: 4,
    paddingTop: 12,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderTopColor: '#C4DDF6',
  },

  doc: {
    width: 76,
    height: 98,
    padding: 9,
    borderRadius: 8,
    backgroundColor: '#FFFFFF',
    overflow: 'hidden',
    shadowColor: '#3668A1',
    shadowOpacity: 0.15,
    shadowRadius: 8,
    shadowOffset: { width: 0, height: 4 },
  },
  docFold: { position: 'absolute', top: 0, right: 0 },
  docName: { fontSize: 8, fontWeight: '700', color: colors.ink, marginBottom: 6, paddingRight: 10 },
  docPair: { flexDirection: 'row', justifyContent: 'space-between', marginBottom: 4 },
  docLine: { height: 3, borderRadius: 2, backgroundColor: '#DCE5F1' },
  docLineStacked: { marginTop: 4 },

  badge: { paddingHorizontal: 9, paddingVertical: 3, borderRadius: radius.pill, backgroundColor: '#E4EFFD' },
  badgeText: { fontSize: 12, color: '#5A73A0' },
  savedRow: { flexDirection: 'row', alignItems: 'center', gap: 12, minHeight: 60, paddingVertical: 10 },
  savedBar: { width: 3, alignSelf: 'stretch', borderRadius: 2, backgroundColor: '#177AFF' },

  timeline: { marginTop: 8 },
  tlRow: { flexDirection: 'row', gap: 12 },
  tlRail: { width: 58, alignItems: 'center' },
  tlChip: {
    minWidth: 56,
    height: 28,
    paddingHorizontal: 6,
    borderRadius: radius.chip,
    backgroundColor: colors.chip,
    alignItems: 'center',
    justifyContent: 'center',
  },
  tlChipText: { fontSize: 12, fontWeight: '600', color: colors.blueStrong, fontVariant: ['tabular-nums'] },
  tlLine: { flex: 1, width: 2, minHeight: 16, marginVertical: 4, borderRadius: 1, backgroundColor: colors.rail },
  tlText: { flex: 1, minWidth: 0, paddingTop: 4, paddingBottom: 14 },
  tlTextDivider: { borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: colors.hairline, marginBottom: 10 },

  onboard: {
    padding: space.cardPadding,
    borderRadius: radius.card,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.card,
    ...shadow,
  },
  onboardTitle: { fontSize: 19, fontWeight: '700', color: colors.ink, letterSpacing: -0.3 },
  onboardBody: { marginTop: 6, fontSize: 14, lineHeight: 20, color: colors.muted },
  progressText: { marginTop: 14, fontSize: 13, fontWeight: '600', color: colors.blueStrong },
  progressTrack: { marginTop: 6, height: 4, borderRadius: 2, backgroundColor: '#DCE8F7' },
  steps: { marginTop: 6 },
  step: { flexDirection: 'row', gap: 12, paddingVertical: 12 },
  stepNum: {
    width: 28,
    height: 28,
    borderRadius: 14,
    backgroundColor: colors.tile,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stepNumCurrent: { backgroundColor: colors.blue },
  stepNumText: { fontSize: 13, fontWeight: '700', color: colors.blue },
  stepNumTextCurrent: { color: '#FFFFFF' },
  stepTitleRow: { flexDirection: 'row', alignItems: 'center', flexWrap: 'wrap', gap: 8, minHeight: 28 },
  stepTitle: { fontSize: 15, fontWeight: '600', color: colors.ink, flexShrink: 1 },
  stepTitleLater: { color: colors.ink2 },
  stepBody: { marginTop: 2, fontSize: 13, lineHeight: 19, color: colors.muted },
  stepButton: { marginTop: 12 },
  pill: { paddingHorizontal: 8, paddingVertical: 2, borderRadius: radius.pill, backgroundColor: '#E4EFFD' },
  pillText: { fontSize: 11, fontWeight: '600', color: colors.blueStrong },

  skeletonCard: { backgroundColor: colors.surface },
  skel: { backgroundColor: colors.skeleton, borderRadius: 6 },

  failTitle: { fontSize: 17, fontWeight: '700', color: colors.ink },
  failButton: { marginTop: 14, alignSelf: 'flex-start' },
})
