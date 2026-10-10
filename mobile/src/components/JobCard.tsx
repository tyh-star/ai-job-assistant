import { Pressable, StyleSheet, Text, View } from 'react-native'
import { colors, radius } from '../theme'
import { ChevronRightIcon, PinIcon } from './Icons'
import { Skeleton } from './feedback'

export type JobBadge = { label: string; tone: 'saved' | 'applied' | 'muted' }

type Props = {
  title: string
  company: string
  location?: string | null
  tags?: string[]
  salary?: string | null
  dateLabel?: string | null
  badges?: JobBadge[]
  onPress: () => void
}

/** A single-column job row sized by its own content. */
export function JobCard({ title, company, location, tags = [], salary, dateLabel, badges = [], onPress }: Props) {
  return (
    <Pressable
      role="link"
      aria-label={`${title}, ${company}`}
      onPress={onPress}
      style={({ pressed }) => [styles.card, pressed && styles.pressed]}
    >
      <View style={styles.top}>
        <View style={styles.titleBlock}>
          <Text style={styles.title} numberOfLines={2}>
            {title}
          </Text>
          <Text style={styles.company} numberOfLines={1}>
            {company}
          </Text>
        </View>
        <ChevronRightIcon />
      </View>

      {location ? (
        <View style={styles.locationRow}>
          <PinIcon size={14} />
          <Text style={styles.location} numberOfLines={1}>
            {location}
          </Text>
        </View>
      ) : null}

      {tags.length > 0 || salary ? (
        <View style={styles.tags}>
          {tags.map((tag) => (
            <View key={tag} style={styles.tag}>
              <Text style={styles.tagText}>{tag}</Text>
            </View>
          ))}
          {salary ? (
            <View style={[styles.tag, styles.salaryTag]}>
              <Text style={[styles.tagText, styles.salaryText]}>{salary}</Text>
            </View>
          ) : null}
        </View>
      ) : null}

      {dateLabel || badges.length > 0 ? (
        <View style={styles.footer}>
          <Text style={styles.date}>{dateLabel ?? ''}</Text>
          <View style={styles.badges}>
            {badges.map((badge) => (
              <View key={badge.label} style={[styles.badge, badgeTone[badge.tone]]}>
                <Text style={[styles.badgeText, badgeTextTone[badge.tone]]}>{badge.label}</Text>
              </View>
            ))}
          </View>
        </View>
      ) : null}
    </Pressable>
  )
}

export function JobCardSkeleton() {
  return (
    <View style={styles.card}>
      <Skeleton width="72%" height={16} />
      <Skeleton width="44%" height={12} style={{ marginTop: 8 }} />
      <View style={[styles.tags, { marginTop: 12 }]}>
        <Skeleton width={56} height={22} />
        <Skeleton width={64} height={22} />
      </View>
    </View>
  )
}

const badgeTone = StyleSheet.create({
  saved: { backgroundColor: '#E4EFFD' },
  applied: { backgroundColor: '#E3F6EC' },
  muted: { backgroundColor: '#EEF1F6' },
})

const badgeTextTone = StyleSheet.create({
  saved: { color: colors.blueStrong },
  applied: { color: '#067647' },
  muted: { color: colors.subtle },
})

const styles = StyleSheet.create({
  card: {
    padding: 14,
    borderRadius: radius.card - 2,
    borderWidth: 1,
    borderColor: '#D6E6F8',
    backgroundColor: colors.card,
    shadowColor: '#4083C0',
    shadowOpacity: 0.06,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: 4 },
  },
  pressed: { opacity: 0.75 },
  top: { flexDirection: 'row', alignItems: 'flex-start', gap: 8 },
  titleBlock: { flex: 1, minWidth: 0 },
  title: { fontSize: 16, lineHeight: 22, fontWeight: '600', color: colors.ink },
  company: { marginTop: 2, fontSize: 14, color: colors.muted },
  locationRow: { flexDirection: 'row', alignItems: 'center', gap: 4, marginTop: 8 },
  location: { flex: 1, fontSize: 13, color: colors.muted },
  tags: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginTop: 8 },
  tag: { paddingHorizontal: 8, paddingVertical: 3, borderRadius: radius.chip, backgroundColor: colors.chip },
  tagText: { fontSize: 12, fontWeight: '500', color: colors.blueStrong },
  salaryTag: { backgroundColor: '#FFF6E5' },
  salaryText: { color: '#8A5300' },
  footer: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginTop: 10 },
  date: { flexShrink: 1, fontSize: 12, color: colors.subtle },
  badges: { flexDirection: 'row', gap: 6 },
  badge: { paddingHorizontal: 8, paddingVertical: 3, borderRadius: radius.pill },
  badgeText: { fontSize: 12, fontWeight: '600' },
})
