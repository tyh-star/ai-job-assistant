import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import i18n from '../i18n'
import { blankDraftContent } from '../resumeBuilder/constants'
import { ResumeBuilderEditorPage } from './ResumeBuilderEditorPage'

const getResumeBuilderDraft = vi.fn()
const updateResumeBuilderDraft = vi.fn()
const previewResumeBuilderDraft = vi.fn()
const generateResumeBuilderDraft = vi.fn()
const getResumeBuilderCareerMaterials = vi.fn()

vi.mock('../api/resumeBuilder', () => ({
  getResumeBuilderDraft: (...args: unknown[]) => getResumeBuilderDraft(...args),
  updateResumeBuilderDraft: (...args: unknown[]) => updateResumeBuilderDraft(...args),
  previewResumeBuilderDraft: (...args: unknown[]) => previewResumeBuilderDraft(...args),
  generateResumeBuilderDraft: (...args: unknown[]) => generateResumeBuilderDraft(...args),
  getResumeBuilderCareerMaterials: (...args: unknown[]) => getResumeBuilderCareerMaterials(...args),
  newIdempotencyKey: () => 'test-idempotency-key',
}))

function draftResponse(overrides: Partial<ReturnType<typeof blankDraftContent>> = {}) {
  return {
    data: {
      id: 5,
      title: 'My Resume',
      templateType: 'GENERIC_ATS',
      targetRole: null,
      locale: 'en',
      schemaVersion: 1,
      version: 1,
      content: { ...blankDraftContent(), ...overrides },
      createdAt: '2026-01-01T00:00:00',
      updatedAt: '2026-01-01T00:00:00',
    },
  }
}

function renderEditor() {
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={['/resumes/builder/5']}>
        <Routes>
          <Route path="/resumes/builder/:id" element={<ResumeBuilderEditorPage />} />
        </Routes>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

describe('ResumeBuilderEditorPage', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
    getResumeBuilderDraft.mockResolvedValue(draftResponse())
    updateResumeBuilderDraft.mockResolvedValue(draftResponse())
    previewResumeBuilderDraft.mockResolvedValue({
      data: {
        header: { fullName: '', location: '', phone: '', email: '', links: [], targetTitle: '' },
        summary: null,
        sections: [],
        omittedSectionIds: [],
        warnings: [],
        missingKeyFacts: ['your name'],
        readyToGenerate: false,
      },
    })
    getResumeBuilderCareerMaterials.mockResolvedValue({
      data: {
        positioningSummary: '',
        selfIntroduction: '',
        aiReflection: '',
        strengthsAndWeaknesses: '',
        developmentPlan: '',
      },
    })
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('loads a completely blank form -- no field is pre-filled with any real data', async () => {
    renderEditor()
    const fullName = await screen.findByLabelText('Full name')
    expect(fullName).toHaveValue('')
    expect(screen.getByLabelText('Email')).toHaveValue('')
    expect(screen.getByLabelText('Target job title')).toHaveValue('')
  })

  it('supports adding multiple education entries', async () => {
    renderEditor()
    await screen.findByLabelText('Full name')
    const user = userEvent.setup()
    const addButtons = screen.getAllByRole('button', { name: 'Add entry' })
    // EXPERIENCE is first in default order, EDUCATION second.
    const addEducation = addButtons[1]
    await user.click(addEducation)
    await user.click(addEducation)
    await user.click(addEducation)
    expect(screen.getAllByLabelText('School')).toHaveLength(3)
  })

  it('supports an unlimited number of project entries, never capped at 3', async () => {
    renderEditor()
    await screen.findByLabelText('Full name')
    const user = userEvent.setup()
    const addButtons = screen.getAllByRole('button', { name: 'Add entry' })
    // EXPERIENCE(0), EDUCATION(1), PROJECTS(2).
    const addProject = addButtons[2]
    for (let i = 0; i < 6; i += 1) {
      await user.click(addProject)
    }
    expect(screen.getAllByLabelText('Project name')).toHaveLength(6)
  })

  it('supports multiple work experience and volunteer entries', async () => {
    renderEditor()
    await screen.findByLabelText('Full name')
    const user = userEvent.setup()
    const addButtons = screen.getAllByRole('button', { name: 'Add entry' })
    const addExperience = addButtons[0]
    await user.click(addExperience)
    await user.click(addExperience)
    expect(screen.getAllByLabelText('Company')).toHaveLength(2)

    const addVolunteer = addButtons[3]
    await user.click(addVolunteer)
    await user.click(addVolunteer)
    expect(screen.getAllByLabelText('Organization')).toHaveLength(2)
  })

  it('can disable a section from the sidebar', async () => {
    renderEditor()
    await screen.findByLabelText('Full name')
    const nav = within(screen.getByRole('navigation'))
    const checkboxes = nav.getAllByRole('checkbox', { name: 'Show this section on the resume' })
    expect(checkboxes[0]).toBeChecked()

    const user = userEvent.setup()
    await user.click(checkboxes[0])
    expect(checkboxes[0]).not.toBeChecked()
  })

  it('can reorder sections with the move-down button, not only by dragging', async () => {
    renderEditor()
    await screen.findByLabelText('Full name')
    const nav = within(screen.getByRole('navigation'))
    const linksBefore = nav.getAllByRole('link').map((link) => link.textContent)
    expect(linksBefore[0]).toBe('Skills')
    expect(linksBefore[1]).toBe('Work / internship experience')

    const user = userEvent.setup()
    const moveDownButtons = nav.getAllByRole('button', { name: 'Move down' })
    await user.click(moveDownButtons[0])

    const linksAfter = nav.getAllByRole('link').map((link) => link.textContent)
    expect(linksAfter[0]).toBe('Work / internship experience')
    expect(linksAfter[1]).toBe('Skills')
  })

  it('a save failure (e.g. a network error) keeps the edit in the input -- it is never cleared or rolled back', async () => {
    updateResumeBuilderDraft.mockRejectedValue({ response: undefined }) // network error, no response at all
    renderEditor()
    const titleInput = await screen.findByLabelText('Draft title')
    const user = userEvent.setup()
    await user.clear(titleInput)
    await user.type(titleInput, 'Edit During Outage')

    await waitFor(() => expect(updateResumeBuilderDraft).toHaveBeenCalled(), { timeout: 3000 })
    expect(await screen.findByText('Save failed')).toBeInTheDocument()
    // The text the user typed is still sitting right there in the field -- nothing
    // was cleared, reverted, or silently discarded because the save failed.
    expect(screen.getByLabelText('Draft title')).toHaveValue('Edit During Outage')
  }, 10000)

  it('autosaves an edit after a short pause', async () => {
    renderEditor()
    const titleInput = await screen.findByLabelText('Draft title')
    const user = userEvent.setup()
    await user.clear(titleInput)
    await user.type(titleInput, 'Updated Title')

    await waitFor(() => expect(updateResumeBuilderDraft).toHaveBeenCalled(), { timeout: 3000 })
    expect(updateResumeBuilderDraft).toHaveBeenCalledWith(5, expect.objectContaining({ title: 'Updated Title', version: 1 }))
  }, 10000)

  it('a save conflict shows a banner and freezes further autosaves rather than overwriting', async () => {
    updateResumeBuilderDraft.mockRejectedValue({
      response: { status: 409, data: { message: 'This draft changed elsewhere since it was last loaded.' } },
    })
    renderEditor()
    const titleInput = await screen.findByLabelText('Draft title')
    const user = userEvent.setup()
    await user.clear(titleInput)
    await user.type(titleInput, 'First Edit')

    await screen.findByText(/changed elsewhere/, {}, { timeout: 3000 })
    expect(await screen.findByRole('button', { name: 'Reload draft' })).toBeInTheDocument()

    const callsAfterConflict = updateResumeBuilderDraft.mock.calls.length
    await user.type(titleInput, ' more')
    await new Promise((resolve) => setTimeout(resolve, 1500))
    expect(updateResumeBuilderDraft.mock.calls.length).toBe(callsAfterConflict)
  }, 10000)

  it('reloading after a conflict replaces local state with the server version', async () => {
    updateResumeBuilderDraft.mockRejectedValue({
      response: { status: 409, data: { message: 'This draft changed elsewhere since it was last loaded.' } },
    })
    renderEditor()
    const titleInput = await screen.findByLabelText('Draft title')
    const user = userEvent.setup()
    await user.clear(titleInput)
    await user.type(titleInput, 'Stale Local Edit')
    await screen.findByRole('button', { name: 'Reload draft' }, { timeout: 3000 })

    getResumeBuilderDraft.mockResolvedValue(draftResponse())
    await user.click(screen.getByRole('button', { name: 'Reload draft' }))

    await waitFor(() => expect(screen.getByLabelText('Draft title')).toHaveValue('My Resume'))
  }, 10000)

  it('the preview tab reflects the backend normalized model and the generate button follows it', async () => {
    renderEditor()
    await screen.findByLabelText('Full name')
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Preview & generate' }))

    await screen.findByText(/Add at least a name/)
    expect(screen.getByRole('button', { name: 'Generate formal resume (DOCX)' })).toBeDisabled()

    previewResumeBuilderDraft.mockResolvedValue({
      data: {
        header: { fullName: 'Alex Morgan', location: '', phone: '', email: 'alex@example.com', links: [], targetTitle: '' },
        summary: null,
        sections: [],
        omittedSectionIds: [],
        warnings: [],
        missingKeyFacts: [],
        readyToGenerate: true,
      },
    })
    generateResumeBuilderDraft.mockResolvedValue({ data: { id: 99, origin: 'GENERATED' } })

    await user.click(screen.getByRole('button', { name: 'Resume sections' }))
    await user.click(screen.getByRole('button', { name: 'Preview & generate' }))
    await screen.findByText('Alex Morgan')

    const generateButton = screen.getByRole('button', { name: 'Generate formal resume (DOCX)' })
    expect(generateButton).toBeEnabled()
    await user.click(generateButton)

    expect(await screen.findByText('Resume generated.')).toBeInTheDocument()
    expect(generateResumeBuilderDraft).toHaveBeenCalledWith(5, { idempotencyKey: 'test-idempotency-key' })
  })
})
