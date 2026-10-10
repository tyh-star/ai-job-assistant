import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { JobDescriptionRenderer } from './JobDescriptionRenderer'

const SAMPLE = `Intro paragraph.

COMPANY OVERVIEW
We help families.

JOB RESPONSIBILITIES
• Case management
- File reports

JOB REQUIREMENTS
* Bachelor's degree
`

describe('JobDescriptionRenderer', () => {
  it('renders known all-caps headings as h2 sections and bullets as list items', () => {
    render(<JobDescriptionRenderer text={SAMPLE} fallbackTitle="职位描述" />)
    expect(screen.getByRole('heading', { level: 2, name: '职位描述' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'COMPANY OVERVIEW' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'JOB RESPONSIBILITIES' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'JOB REQUIREMENTS' })).toBeInTheDocument()
    expect(screen.getByText('Case management').closest('li')).not.toBeNull()
    expect(screen.getByText('File reports').closest('li')).not.toBeNull()
    expect(document.body.innerHTML).not.toContain('dangerouslySetInnerHTML')
  })
})
