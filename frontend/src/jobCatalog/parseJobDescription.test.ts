import { describe, expect, it } from 'vitest'
import { parseJobDescription } from './parseJobDescription'

const SAMPLE = `Acme is hiring.

COMPANY OVERVIEW
We help families in the Bronx.

JOB RESPONSIBILITIES
• Case management
• Community outreach
- File reports

JOB REQUIREMENTS
* Bachelor's degree
· Two years of experience

Other leftover notes stay visible.`

describe('parseJobDescription', () => {
  it('splits COMPANY OVERVIEW / RESPONSIBILITIES / REQUIREMENTS into titled sections', () => {
    const sections = parseJobDescription(SAMPLE, 'Job description')
    expect(sections.map((section) => section.title)).toEqual([
      'Job description',
      'COMPANY OVERVIEW',
      'JOB RESPONSIBILITIES',
      'JOB REQUIREMENTS',
    ])
    expect(sections[1].blocks).toEqual([{ type: 'paragraph', text: 'We help families in the Bronx.' }])
    expect(sections[2].blocks[0]).toEqual({
      type: 'list',
      items: ['Case management', 'Community outreach', 'File reports'],
    })
    expect(sections[3].blocks[0]).toEqual({
      type: 'list',
      items: ["Bachelor's degree", 'Two years of experience'],
    })
    expect(sections[3].blocks[1]).toEqual({
      type: 'paragraph',
      text: 'Other leftover notes stay visible.',
    })
  })

  it('keeps unrecognized copy under the fallback title without dropping text', () => {
    const sections = parseJobDescription('Just a paragraph.\nAnd another.', 'Job description')
    expect(sections).toEqual([
      {
        title: 'Job description',
        blocks: [{ type: 'paragraph', text: 'Just a paragraph. And another.' }],
      },
    ])
  })

  it('does not treat a long uppercase sentence as a heading', () => {
    const line = 'THIS ROLE REQUIRES YOU TO TRAVEL ACROSS MULTIPLE BOROUGHS EACH WEEK.'
    const sections = parseJobDescription(line, 'Job description')
    expect(sections[0].title).toBe('Job description')
    expect(sections[0].blocks[0]).toMatchObject({ type: 'paragraph' })
  })
})
