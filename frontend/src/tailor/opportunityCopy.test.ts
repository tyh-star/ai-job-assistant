import { describe, expect, it } from 'vitest'
import type { V3OpportunityMap } from '../api/types'
import { cleanDisplayItems, groupedRequirements, hasCannotInvent, mostRelevantTitles } from './opportunityCopy'

const map: V3OpportunityMap = {
  entries: [
    {
      canonicalEntryKey: 'PROJECT:prime-deals',
      entryTitle: 'Prime Deals',
      entryType: 'PROJECT',
      priority: 'HIGH',
      coveredRequirements: ['Java and Spring Boot'],
      partiallyCoveredRequirements: ['Deploy and maintain applications on AWS'],
      canStrengthenWithExistingFacts: ['REST APIs'],
      missingFactsThatCanBeConfirmed: ['ongoing maintenance'],
      cannotInvent: ['Kubernetes cluster operations'],
    },
    {
      canonicalEntryKey: 'PROJECT:smartpc-builder',
      entryTitle: 'SmartPC Builder',
      entryType: 'PROJECT',
      priority: 'MEDIUM',
      coveredRequirements: ['Java'],
      partiallyCoveredRequirements: ['Java and Spring Boot'],
      canStrengthenWithExistingFacts: [],
      missingFactsThatCanBeConfirmed: ['Spring Boot'],
      cannotInvent: [],
    },
  ],
  questions: [],
  skippedQuestionIds: [],
  writerCalls: 0,
  openaiCalls: 0,
  wroteJobOrResume: false,
}

describe('opportunityCopy', () => {
  it('keeps SmartPC isolated from Prime-only tech lists', () => {
    const smart = map.entries[1]
    expect(smart.canStrengthenWithExistingFacts.join(' ')).not.toMatch(/Spring Boot|REST|AWS/i)
    expect(smart.coveredRequirements.join(' ')).not.toMatch(/Spring Boot|REST|AWS/i)
  })

  it('flags cannotInvent without exposing the list', () => {
    expect(hasCannotInvent(map)).toBe(true)
    expect(mostRelevantTitles(map)).toEqual(['Prime Deals'])
  })

  it('drops duplicate bullets and empty dashes and keeps PARTIAL out of covered', () => {
    expect(cleanDisplayItems(['• • JavaScript', '-', ' ', 'JavaScript', '•'])).toEqual(['JavaScript'])
    const grouped = groupedRequirements({
      ...map.entries[0],
      coveredRequirements: ['should not win'],
      requirements: [
        {
          requirementText: 'JavaScript skills and API/event contract design',
          status: 'PARTIALLY_COVERED',
          coveredAtoms: ['JavaScript'],
          missingAtoms: ['API contract design'],
        },
        {
          requirementText: 'Practical experience with NX monorepos',
          status: 'MISSING',
          coveredAtoms: [],
          missingAtoms: ['Nx'],
        },
      ],
    })
    expect(grouped.covered).toEqual([])
    expect(grouped.partial).toHaveLength(1)
    expect(grouped.missing).toHaveLength(1)
  })
})
