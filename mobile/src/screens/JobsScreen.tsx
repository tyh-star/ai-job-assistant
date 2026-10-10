import { useEffect, useMemo, useState } from 'react'
import {
  ActivityIndicator,
  FlatList,
  Keyboard,
  Pressable,
  RefreshControl,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native'
import { SafeAreaView } from 'react-native-safe-area-context'
import { useTranslation } from 'react-i18next'
import { apiErrorKey } from '../api/errors'
import type { CitySuggestion, JobCatalogSummary, JobResponse } from '../api/types'
import { Button } from '../components/Button'
import { StateView } from '../components/feedback'
import { BriefcaseIcon, ChevronLeftIcon, ClockIcon, CloseIcon, PinIcon, SearchIcon } from '../components/Icons'
import { JobCard, JobCardSkeleton } from '../components/JobCard'
import { SegmentedControl } from '../components/SegmentedControl'
import {
  catalogBadges,
  catalogLocation,
  catalogTags,
  personalBadges,
  personalDateLabel,
  salaryLabel,
} from '../jobs/labels'
import {
  CITY_QUERY_MIN_LENGTH,
  EMPTY_FILTERS,
  flattenCatalogPages,
  hasActiveFilters,
  parseSegment,
  splitMyJobs,
  type JobsSegment,
  type SearchFilters,
} from '../jobs/model'
import { useCatalogSearch, useCitySuggestions, useMyJobs } from '../jobs/queries'
import { colors, radius, space } from '../theme'
import { formatMonthDay } from '../utils/dates'
import { useDebouncedValue } from '../utils/useDebouncedValue'

type Props = {
  /** `catalog` | `saved` | `applied` from the route; `tabNonce` re-applies the same tab. */
  tabParam?: string
  tabNonce?: string
  onOpenCatalogJob: (catalogJobId: string) => void
  onOpenJob: (jobId: number) => void
  /** Visitor mode: public catalog only; saved and applied lists (GET /jobs) are never requested. */
  guest?: boolean
  onBack?: () => void
}

type Row = { kind: 'catalog'; job: JobCatalogSummary } | { kind: 'mine'; job: JobResponse }

export function JobsScreen({ tabParam, tabNonce, onOpenCatalogJob, onOpenJob, guest = false, onBack }: Props) {
  const { t, i18n } = useTranslation()
  const [segment, setSegment] = useState<JobsSegment>(guest ? 'catalog' : parseSegment(tabParam))
  const [catalogVisited, setCatalogVisited] = useState(segment === 'catalog')

  useEffect(() => {
    if (tabParam && !guest) {
      setSegment(parseSegment(tabParam))
    }
  }, [tabParam, tabNonce, guest])

  useEffect(() => {
    if (segment === 'catalog') {
      setCatalogVisited(true)
    }
  }, [segment])

  const [draft, setDraft] = useState<SearchFilters>(EMPTY_FILTERS)
  const [committed, setCommitted] = useState<SearchFilters>(EMPTY_FILTERS)
  const [cityText, setCityText] = useState('')
  const debouncedCity = useDebouncedValue(cityText.trim(), 300)
  const cityLookupEnabled = draft.city === null && debouncedCity.length >= CITY_QUERY_MIN_LENGTH
  const cities = useCitySuggestions(debouncedCity, cityLookupEnabled)

  const catalog = useCatalogSearch(committed, catalogVisited)
  const mine = useMyJobs(!guest)

  const catalogRows = useMemo(() => flattenCatalogPages(catalog.data?.pages ?? []), [catalog.data])
  const split = useMemo(() => (mine.data ? splitMyJobs(mine.data) : null), [mine.data])

  const rows: Row[] =
    segment === 'catalog'
      ? catalogRows.map((job) => ({ kind: 'catalog', job }))
      : ((segment === 'saved' ? split?.saved : split?.applied) ?? []).map((job) => ({ kind: 'mine', job }))

  function submit() {
    Keyboard.dismiss()
    const next = { q: draft.q, city: draft.city }
    const unchanged = next.q.trim() === committed.q.trim() && next.city?.value === committed.city?.value
    setCommitted(next)
    if (unchanged) {
      void catalog.refetch()
    }
  }

  function clearFilters() {
    Keyboard.dismiss()
    setDraft(EMPTY_FILTERS)
    setCityText('')
    setCommitted(EMPTY_FILTERS)
  }

  function pickCity(city: CitySuggestion) {
    setDraft((current) => ({ ...current, city }))
    setCityText('')
  }

  const canClear = hasActiveFilters(draft) || hasActiveFilters(committed) || cityText.length > 0
  const totalCount = catalog.data?.pages[0]?.totalCount

  const header = (
    <View style={styles.header}>
      {onBack ? (
        <Pressable role="button" aria-label={t('guest.back')} onPress={onBack} hitSlop={8} style={styles.back}>
          <ChevronLeftIcon />
          <Text style={styles.backText}>{t('guest.back')}</Text>
        </Pressable>
      ) : null}
      <Text role="heading" style={styles.title}>
        {t('jobs.title')}
      </Text>
      {guest ? (
        <Text style={styles.guestNotice}>{t('guest.jobsNotice')}</Text>
      ) : (
        <SegmentedControl
          label={t('jobs.segments.label')}
          value={segment}
          onChange={setSegment}
          options={[
            { value: 'catalog', label: t('jobs.segments.catalog') },
            { value: 'saved', label: t('jobs.segments.saved') },
            { value: 'applied', label: t('jobs.segments.applied') },
          ]}
        />
      )}

      {segment === 'catalog' ? (
        <View style={styles.searchCard}>
          <View style={styles.inputBox}>
            <SearchIcon size={17} color={colors.subtle} />
            <TextInput
              aria-label={t('jobs.search.keywordLabel')}
              value={draft.q}
              onChangeText={(q) => setDraft((current) => ({ ...current, q }))}
              placeholder={t('jobs.search.keywordPlaceholder')}
              placeholderTextColor={colors.subtle}
              returnKeyType="search"
              onSubmitEditing={submit}
              autoCorrect={false}
              style={styles.input}
            />
          </View>

          {draft.city ? (
            <View style={styles.cityChipRow}>
              <View style={styles.cityChip}>
                <PinIcon size={14} color={colors.blueStrong} />
                <Text style={styles.cityChipText} numberOfLines={1}>
                  {cityDisplay(draft.city)}
                </Text>
                <Pressable
                  role="button"
                  aria-label={t('jobs.search.cityClear')}
                  onPress={() => setDraft((current) => ({ ...current, city: null }))}
                  hitSlop={10}
                >
                  <CloseIcon size={13} color={colors.blueStrong} />
                </Pressable>
              </View>
            </View>
          ) : (
            <View>
              <View style={styles.inputBox}>
                <PinIcon size={17} color={colors.subtle} />
                <TextInput
                  aria-label={t('jobs.search.cityLabel')}
                  value={cityText}
                  onChangeText={setCityText}
                  placeholder={t('jobs.search.cityPlaceholder')}
                  placeholderTextColor={colors.subtle}
                  autoCorrect={false}
                  style={styles.input}
                />
              </View>
              {cityLookupEnabled ? (
                <View style={styles.suggestions}>
                  {cities.isPending ? <Text style={styles.suggestionNote}>{t('jobs.search.cityLoading')}</Text> : null}
                  {cities.isError ? <Text style={styles.suggestionNote}>{t('jobs.search.cityFailed')}</Text> : null}
                  {cities.data && cities.data.length === 0 ? (
                    <Text style={styles.suggestionNote}>{t('jobs.search.cityEmpty')}</Text>
                  ) : null}
                  {cities.data?.slice(0, 6).map((city) => (
                    <Pressable
                      key={`${city.value}|${city.countryCode ?? ''}`}
                      role="button"
                      aria-label={cityDisplay(city)}
                      onPress={() => pickCity(city)}
                      style={({ pressed }) => [styles.suggestion, pressed && styles.pressed]}
                    >
                      <Text style={styles.suggestionText}>{cityDisplay(city)}</Text>
                    </Pressable>
                  ))}
                </View>
              ) : null}
            </View>
          )}

          <View style={styles.searchButtons}>
            <Button
              variant="secondary"
              size="sm"
              label={t('jobs.search.clear')}
              onPress={clearFilters}
              disabled={!canClear}
              style={styles.flex}
            />
            <Button size="sm" label={t('jobs.search.submit')} onPress={submit} style={styles.flex} />
          </View>
        </View>
      ) : null}

      {segment === 'catalog' && catalog.data && typeof totalCount === 'number' && catalogRows.length > 0 ? (
        <Text style={styles.resultLine}>{t('jobs.results.count', { count: totalCount })}</Text>
      ) : null}
      {segment === 'catalog' && catalog.isRefetchError && catalog.data ? (
        <Text style={styles.inlineError}>{t('jobs.results.refreshFailed')}</Text>
      ) : null}
      {segment !== 'catalog' && mine.isRefetchError && mine.data ? (
        <Text style={styles.inlineError}>{t('jobs.results.refreshFailed')}</Text>
      ) : null}
    </View>
  )

  let empty: React.ReactElement | null = null
  if (segment === 'catalog') {
    if (catalog.isPending) {
      empty = <SkeletonList />
    } else if (catalog.isError && !catalog.data) {
      empty = (
        <StateView
          tone="error"
          title={t('jobs.results.errorTitle')}
          body={t(apiErrorKey(catalog.error))}
          actionLabel={t('jobs.retry')}
          onAction={() => void catalog.refetch()}
        />
      )
    } else {
      empty = (
        <StateView
          tone="empty"
          icon={<SearchIcon size={22} />}
          title={t('jobs.results.emptyTitle')}
          body={t('jobs.results.emptyBody')}
          actionLabel={hasActiveFilters(committed) ? t('jobs.search.clear') : undefined}
          onAction={hasActiveFilters(committed) ? clearFilters : undefined}
        />
      )
    }
  } else if (mine.isPending) {
    empty = <SkeletonList />
  } else if (mine.isError && !mine.data) {
    empty = (
      <StateView
        tone="error"
        title={t('jobs.mine.errorTitle')}
        body={t(apiErrorKey(mine.error))}
        actionLabel={t('jobs.retry')}
        onAction={() => void mine.refetch()}
      />
    )
  } else {
    const saved = segment === 'saved'
    empty = (
      <StateView
        tone="empty"
        icon={saved ? <BriefcaseIcon size={22} /> : <ClockIcon size={22} />}
        title={t(saved ? 'jobs.saved.emptyTitle' : 'jobs.applied.emptyTitle')}
        body={t(saved ? 'jobs.saved.emptyBody' : 'jobs.applied.emptyBody')}
        actionLabel={t('jobs.goCatalog')}
        onAction={() => setSegment('catalog')}
      />
    )
  }

  let footer: React.ReactElement | null = null
  if (segment === 'catalog' && catalogRows.length > 0) {
    if (catalog.isFetchingNextPage) {
      footer = <ActivityIndicator style={styles.footer} color={colors.blue} />
    } else if (catalog.isFetchNextPageError) {
      footer = (
        <View style={styles.footer}>
          <Text style={styles.inlineError}>{t('jobs.results.loadMoreFailed')}</Text>
          <Button variant="secondary" size="sm" label={t('jobs.retry')} onPress={() => void catalog.fetchNextPage()} />
        </View>
      )
    } else if (catalog.hasNextPage) {
      footer = (
        <View style={styles.footer}>
          <Button variant="secondary" size="sm" label={t('jobs.results.loadMore')} onPress={() => void catalog.fetchNextPage()} />
        </View>
      )
    } else {
      footer = (
        <Text style={[styles.footer, styles.footerNote]}>
          {t('jobs.results.allLoaded', { count: typeof totalCount === 'number' ? totalCount : catalogRows.length })}
        </Text>
      )
    }
  }

  const refreshing =
    segment === 'catalog' ? catalog.isRefetching && !catalog.isFetchingNextPage : mine.isRefetching

  return (
    <SafeAreaView style={styles.safe} edges={['top']}>
      <FlatList
        data={rows}
        keyExtractor={(row) => (row.kind === 'catalog' ? `c:${row.job.id}` : `m:${row.job.id}`)}
        keyboardShouldPersistTaps="handled"
        contentContainerStyle={styles.content}
        ItemSeparatorComponent={Separator}
        ListHeaderComponent={header}
        ListEmptyComponent={empty}
        ListFooterComponent={footer}
        onEndReachedThreshold={0.4}
        onEndReached={() => {
          if (segment === 'catalog' && catalog.hasNextPage && !catalog.isFetchingNextPage && !catalog.isFetchNextPageError) {
            void catalog.fetchNextPage()
          }
        }}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={() => void (segment === 'catalog' ? catalog.refetch() : mine.refetch())}
            tintColor={colors.blue}
            colors={[colors.blue]}
          />
        }
        renderItem={({ item }) =>
          item.kind === 'catalog' ? (
            <JobCard
              title={item.job.title}
              company={item.job.companyName}
              location={catalogLocation(item.job)}
              tags={catalogTags(t, item.job)}
              salary={salaryLabel(t, item.job)}
              dateLabel={
                item.job.postedAt
                  ? t('jobs.card.postedOn', { date: formatMonthDay(item.job.postedAt, i18n.language) })
                  : null
              }
              badges={catalogBadges(t, item.job)}
              onPress={() => onOpenCatalogJob(item.job.id)}
            />
          ) : (
            <JobCard
              title={item.job.title}
              company={item.job.company}
              location={item.job.location?.trim() || null}
              dateLabel={personalDateLabel(t, i18n.language, item.job)}
              badges={personalBadges(t, item.job)}
              onPress={() => onOpenJob(item.job.id)}
            />
          )
        }
      />
    </SafeAreaView>
  )
}

function cityDisplay(city: CitySuggestion): string {
  return city.countryCode ? `${city.value} · ${city.countryCode.toUpperCase()}` : city.value
}

function Separator() {
  return <View style={styles.separator} />
}

function SkeletonList() {
  return (
    <View style={styles.skeletons} aria-busy>
      {[0, 1, 2].map((key) => (
        <JobCardSkeleton key={key} />
      ))}
    </View>
  )
}

const styles = StyleSheet.create({
  safe: { flex: 1, backgroundColor: colors.background },
  content: { paddingHorizontal: space.screenX, paddingBottom: 28 },
  flex: { flex: 1 },
  pressed: { opacity: 0.7 },
  header: { paddingTop: 10, paddingBottom: 14, gap: 14 },
  title: { fontSize: 28, fontWeight: '700', color: colors.ink, letterSpacing: -0.6 },
  back: { flexDirection: 'row', alignItems: 'center', gap: 2, minHeight: 44, alignSelf: 'flex-start' },
  backText: { fontSize: 16, fontWeight: '600', color: colors.blue },
  guestNotice: { fontSize: 14, lineHeight: 20, color: colors.ink2 },

  searchCard: {
    gap: 10,
    padding: 14,
    borderRadius: radius.card,
    borderWidth: 1,
    borderColor: colors.border,
    backgroundColor: colors.card,
  },
  inputBox: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    minHeight: 46,
    paddingHorizontal: 12,
    borderRadius: radius.button,
    borderWidth: 1,
    borderColor: colors.inputBorder,
    backgroundColor: '#FFFFFF',
  },
  input: { flex: 1, minHeight: 44, fontSize: 15, color: colors.ink },
  cityChipRow: { flexDirection: 'row' },
  cityChip: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    maxWidth: '100%',
    minHeight: 36,
    paddingHorizontal: 12,
    borderRadius: radius.pill,
    backgroundColor: '#E4EFFD',
  },
  cityChipText: { flexShrink: 1, fontSize: 14, fontWeight: '600', color: colors.blueStrong },
  suggestions: {
    marginTop: 6,
    borderRadius: radius.button,
    borderWidth: 1,
    borderColor: colors.hairline,
    backgroundColor: colors.surface,
    overflow: 'hidden',
  },
  suggestion: { minHeight: 44, justifyContent: 'center', paddingHorizontal: 12 },
  suggestionText: { fontSize: 15, color: colors.ink },
  suggestionNote: { paddingHorizontal: 12, paddingVertical: 10, fontSize: 13, color: colors.muted },
  searchButtons: { flexDirection: 'row', gap: 10 },

  resultLine: { fontSize: 13, color: colors.muted },
  inlineError: { fontSize: 13, color: colors.danger },
  separator: { height: 10 },
  skeletons: { gap: 10 },
  footer: { marginTop: 16, alignItems: 'center', gap: 8 },
  footerNote: { textAlign: 'center', fontSize: 13, color: colors.subtle },
})
