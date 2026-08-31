import { describe, expect, it } from 'vitest'
import type { V3EntryReview } from '../api/types'
import {
  buildEditedEntries,
  buildEditedSkillReviews,
  defaultSelectedEntryKeys,
  defaultSelectedSkillReviewIds,
  hasNoHighValueRewrite,
  selectedNodeIdsForEntries,
} from './enhancedConfirm'

const reviews: V3EntryReview[] = [
  {
    canonicalEntryKey: 'PROJECT:prime-deals',
    entryTitle: 'Prime Deals',
    originalNodes: [
      { nodeId: 'p-14', text: 'Built a Java checkout service.' },
      { nodeId: 'p-15', text: 'Integrated RESTful APIs for cart operations.' },
      { nodeId: 'p-16', text: 'Stored order data in MySQL.' },
      { nodeId: 'p-17', text: 'Wrote unit tests for checkout.' },
      { nodeId: 'p-18', text: 'Deployed the project on AWS.' },
    ],
    proposedNodes: [
      { nodeId: 'p-14', text: 'Built a Java Spring Boot checkout service.' },
      { nodeId: 'p-15', text: 'Integrated RESTful APIs for shopping cart operations.' },
      { nodeId: 'p-16', text: 'Stored checkout orders in MySQL.' },
      { nodeId: 'p-17', text: 'Wrote unit tests covering checkout flows.' },
      { nodeId: 'p-18', text: 'Deployed and maintained the project on AWS with weekly patching.' },
    ],
    whyBetter: 'Adds confirmed maintenance on AWS.',
    suggestionType: 'EVIDENCE_ENHANCED',
    usedEvidenceItemIds: [41],
  },
  {
    canonicalEntryKey: 'PROJECT:smartpc-builder',
    entryTitle: 'SmartPC Builder',
    originalNodes: [{ nodeId: 'p-10', text: 'Built a Java tool.' }],
    proposedNodes: [{ nodeId: 'p-10', text: 'Built a Java comparison tool.' }],
    whyBetter: 'Clarifies the Java comparison work.',
    suggestionType: 'JD_TARGETED',
    usedEvidenceItemIds: [],
  },
]

describe('enhancedConfirm', () => {
  it('defaults to selecting every eligible entry', () => {
    expect(defaultSelectedEntryKeys(reviews)).toEqual([
      'PROJECT:prime-deals',
      'PROJECT:smartpc-builder',
    ])
  })

  it('maps a selected entry to every writable proposed node id', () => {
    expect(selectedNodeIdsForEntries(reviews, ['PROJECT:prime-deals'])).toEqual([
      'p-14', 'p-15', 'p-16', 'p-17', 'p-18',
    ])
    expect(selectedNodeIdsForEntries(reviews, ['PROJECT:prime-deals'])).not.toContain('p-10')
  })

  it('sends the full proposed node set for edited entries', () => {
    const edited = buildEditedEntries(
      reviews,
      ['PROJECT:prime-deals'],
      { 'p-14': 'Built a Java Spring Boot checkout service used in production.' },
      {
        'p-14': 'Built a Java Spring Boot checkout service.',
        'p-15': 'Integrated RESTful APIs for shopping cart operations.',
        'p-16': 'Stored checkout orders in MySQL.',
        'p-17': 'Wrote unit tests covering checkout flows.',
        'p-18': 'Deployed and maintained the project on AWS with weekly patching.',
      },
    )
    expect(edited[0].canonicalEntryKey).toBe('PROJECT:prime-deals')
    expect(edited[0].nodes.map((node) => node.nodeId)).toEqual([
      'p-14', 'p-15', 'p-16', 'p-17', 'p-18',
    ])
    expect(edited[0].nodes[0].newText).toContain('used in production')
  })

  it('treats an enhanced proposal without reviews as no high-value rewrite', () => {
    expect(hasNoHighValueRewrite({ productMode: 'ENHANCED', hasSuggestions: false, entryReviews: [] })).toBe(true)
  })

  it('defaults admitted skill reviews and omits unselected skills from edits', () => {
    const skills = [
      {
        skillReviewId: 'skills:technical',
        originalSkills: 'Java, SQL',
        proposedSkills: 'Java, SQL, MySQL',
        addedSkills: ['MySQL'],
        admitted: true,
        proposedNodes: [{ nodeId: 'p-11', text: 'Java, SQL, MySQL' }],
      },
    ]
    expect(defaultSelectedSkillReviewIds(skills)).toEqual(['skills:technical'])
    expect(buildEditedSkillReviews(skills, [], { 'p-11': 'Java, SQL, MySQL, Redis' }, { 'p-11': 'Java, SQL, MySQL' })).toEqual([])
    const edited = buildEditedSkillReviews(
      skills,
      ['skills:technical'],
      { 'p-11': 'Java, SQL, MySQL, Redis', 'skills:technical': 'Java, SQL, MySQL, Redis' },
      { 'p-11': 'Java, SQL, MySQL', 'skills:technical': 'Java, SQL, MySQL' },
    )
    expect(edited[0].skillReviewId).toBe('skills:technical')
    expect(edited[0].proposedSkills).toContain('Redis')
  })
})
