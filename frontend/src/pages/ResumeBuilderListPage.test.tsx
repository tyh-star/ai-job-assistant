import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import i18n from '../i18n'
import { ResumeBuilderListPage } from './ResumeBuilderListPage'

const listResumeBuilderDrafts = vi.fn()
const createResumeBuilderDraft = vi.fn()
const duplicateResumeBuilderDraft = vi.fn()
const deleteResumeBuilderDraft = vi.fn()

vi.mock('../api/resumeBuilder', () => ({
  listResumeBuilderDrafts: (...args: unknown[]) => listResumeBuilderDrafts(...args),
  createResumeBuilderDraft: (...args: unknown[]) => createResumeBuilderDraft(...args),
  duplicateResumeBuilderDraft: (...args: unknown[]) => duplicateResumeBuilderDraft(...args),
  deleteResumeBuilderDraft: (...args: unknown[]) => deleteResumeBuilderDraft(...args),
}))

function renderPage() {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={['/resumes/builder']}>
        <Routes>
          <Route path="/resumes/builder" element={<ResumeBuilderListPage />} />
          <Route path="/resumes/builder/:id" element={<div>editor placeholder</div>} />
        </Routes>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

describe('ResumeBuilderListPage', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
    listResumeBuilderDrafts.mockResolvedValue({ data: [] })
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('shows an empty state with no prior drafts', async () => {
    renderPage()
    expect(await screen.findByText('No drafts yet. Create one to get started.')).toBeInTheDocument()
  })

  it('creating a new draft navigates straight to its editor', async () => {
    createResumeBuilderDraft.mockResolvedValue({ data: { id: 42 } })
    renderPage()
    await screen.findByText('No drafts yet. Create one to get started.')

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Create new draft' }))

    expect(await screen.findByText('editor placeholder')).toBeInTheDocument()
    expect(createResumeBuilderDraft).toHaveBeenCalledWith(
      expect.objectContaining({ templateType: 'GENERIC_ATS', locale: 'en' }),
    )
  })

  it('lists existing drafts with title and target role, and supports duplicate/delete', async () => {
    listResumeBuilderDrafts.mockResolvedValue({
      data: [
        {
          id: 1,
          title: 'My First Draft',
          templateType: 'GENERIC_ATS',
          targetRole: 'Backend Engineer',
          locale: 'en',
          version: 1,
          createdAt: '2026-01-01T00:00:00',
          updatedAt: '2026-01-02T00:00:00',
        },
      ],
    })
    duplicateResumeBuilderDraft.mockResolvedValue({ data: { id: 2 } })
    deleteResumeBuilderDraft.mockResolvedValue({})
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    renderPage()
    expect(await screen.findByText('My First Draft')).toBeInTheDocument()
    expect(screen.getByText('Backend Engineer')).toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Duplicate' }))
    await waitFor(() => expect(duplicateResumeBuilderDraft).toHaveBeenCalledWith(1))

    await user.click(screen.getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(deleteResumeBuilderDraft).toHaveBeenCalledWith(1))
  })
})
