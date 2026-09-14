import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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

function missingFactItems() {
  const heading = screen.getByText('Still missing:')
  const list = heading.parentElement?.querySelector('ul')
  return [...(list?.querySelectorAll('li') ?? [])].map((item) => item.textContent)
}

function generateButton() {
  return screen.getByRole('button', { name: 'Generate formal resume (DOCX)' })
}

function echoUpdate(_id: number, body: Record<string, unknown>) {
  const version = typeof body.version === 'number' ? body.version : 1
  return Promise.resolve({
    data: {
      ...draftResponse().data,
      ...body,
      version: version + 1,
    },
  })
}

describe('ResumeBuilderEditorPage', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('en')
    getResumeBuilderDraft.mockResolvedValue(draftResponse())
    updateResumeBuilderDraft.mockImplementation(echoUpdate)
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
    expect(linksBefore[1]).toBe('Work experience')

    const user = userEvent.setup()
    const moveDownButtons = nav.getAllByRole('button', { name: 'Move down' })
    await user.click(moveDownButtons[0])

    const linksAfter = nav.getAllByRole('link').map((link) => link.textContent)
    expect(linksAfter[0]).toBe('Work experience')
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

  it('an empty draft shows the same three missing facts used by the generate button', async () => {
    renderEditor()
    await screen.findByLabelText('Full name')
    expect(missingFactItems()).toEqual([
      'your name',
      'a way to contact you (email or phone)',
      'a target job title',
    ])
    expect(screen.getByText('(no name entered yet)')).toBeInTheDocument()
    expect(generateButton()).toBeDisabled()
  })

  it('filling the name immediately updates the preview and drops the name-missing hints', async () => {
    renderEditor()
    const fullName = await screen.findByLabelText('Full name')
    const user = userEvent.setup()
    await user.type(fullName, 'Alex Morgan')

    expect(screen.getByText('Alex Morgan')).toBeInTheDocument()
    expect(screen.queryByText('(no name entered yet)')).not.toBeInTheDocument()
    expect(missingFactItems()).not.toContain('your name')
    expect(generateButton()).toBeDisabled()
  })

  it('phone alone is enough to clear the missing-contact hint', async () => {
    renderEditor()
    await screen.findByLabelText('Full name')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Phone'), '(416) 555-0142')

    expect(missingFactItems()).not.toContain('a way to contact you (email or phone)')
    expect(generateButton()).toBeDisabled()
  })

  it('email alone is enough to clear the missing-contact hint', async () => {
    renderEditor()
    await screen.findByLabelText('Full name')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Email'), 'alex.morgan@example.com')

    expect(missingFactItems()).not.toContain('a way to contact you (email or phone)')
    expect(generateButton()).toBeDisabled()
  })

  it('the draft-settings target role clears the missing-target hint', async () => {
    renderEditor()
    await screen.findByLabelText('Full name')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Target role (shown in the draft list)'), 'Junior Software Developer')

    expect(screen.getByLabelText('Target job title')).toHaveValue('Junior Software Developer')
    expect(missingFactItems()).not.toContain('a target job title')
    expect(generateButton()).toBeDisabled()
  })

  it('enables generate only after name, contact, and target are all present, and disables it again when contact is cleared', async () => {
    renderEditor()
    await screen.findByLabelText('Full name')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Full name'), 'Alex Morgan')
    await user.type(screen.getByLabelText('Email'), 'alex.morgan@example.com')
    await user.type(screen.getByLabelText('Target role (shown in the draft list)'), 'Junior Software Developer')

    expect(screen.queryByText('Still missing:')).not.toBeInTheDocument()
    expect(generateButton()).toBeEnabled()

    await user.clear(screen.getByLabelText('Email'))
    expect(missingFactItems()).toContain('a way to contact you (email or phone)')
    expect(generateButton()).toBeDisabled()

    await user.type(screen.getByLabelText('Phone'), '(416) 555-0142')
    expect(screen.queryByText('Still missing:')).not.toBeInTheDocument()
    expect(generateButton()).toBeEnabled()

    await user.clear(screen.getByLabelText('Phone'))
    expect(missingFactItems()).toContain('a way to contact you (email or phone)')
    expect(generateButton()).toBeDisabled()
  })

  it('keeps name, contact, target, and preview after autosave and a full reload', async () => {
    let stored = draftResponse()
    updateResumeBuilderDraft.mockImplementation((_id: number, body: Record<string, unknown>) => {
      const version = typeof body.version === 'number' ? body.version : stored.data.version
      stored = {
        data: {
          ...stored.data,
          ...body,
          version: version + 1,
        },
      }
      return Promise.resolve(stored)
    })
    getResumeBuilderDraft.mockImplementation(() => Promise.resolve(stored))

    const { unmount } = renderEditor()
    await screen.findByLabelText('Full name')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Full name'), 'Alex Morgan')
    await user.type(screen.getByLabelText('Email'), 'alex.morgan@example.com')
    await user.type(screen.getByLabelText('Phone'), '(416) 555-0142')
    await user.type(screen.getByLabelText('Target role (shown in the draft list)'), 'Junior Software Developer')

    await waitFor(() => expect(screen.getByText('Saved')).toBeInTheDocument(), { timeout: 3000 })
    expect(stored.data.content.basicInfo.fullName).toBe('Alex Morgan')
    expect(stored.data.targetRole).toBe('Junior Software Developer')
    expect(stored.data.content.jobTarget.targetTitle).toBe('Junior Software Developer')

    unmount()
    renderEditor()

    expect(await screen.findByLabelText('Full name')).toHaveValue('Alex Morgan')
    expect(screen.getByLabelText('Email')).toHaveValue('alex.morgan@example.com')
    expect(screen.getByLabelText('Phone')).toHaveValue('(416) 555-0142')
    expect(screen.getByLabelText('Target role (shown in the draft list)')).toHaveValue('Junior Software Developer')
    expect(screen.getByLabelText('Target job title')).toHaveValue('Junior Software Developer')
    expect(screen.getByText('Alex Morgan')).toBeInTheDocument()
    expect(screen.queryByText('Still missing:')).not.toBeInTheDocument()
    expect(generateButton()).toBeEnabled()
  }, 10000)

  it('does not overwrite newer keystrokes with an in-flight save response', async () => {
    let resolveFirst!: (value: unknown) => void
    const firstSave = new Promise((resolve) => {
      resolveFirst = resolve
    })
    updateResumeBuilderDraft.mockImplementation((_id: number, body: Record<string, unknown>) => {
      return firstSave.then(() => ({
        data: {
          ...draftResponse().data,
          ...body,
          version: 2,
        },
      }))
    })

    renderEditor()
    const fullName = await screen.findByLabelText('Full name')
    const user = userEvent.setup()
    await user.type(fullName, 'Alex')
    await waitFor(() => expect(updateResumeBuilderDraft).toHaveBeenCalledTimes(1), { timeout: 3000 })
    await user.type(fullName, ' Morgan')
    resolveFirst(undefined)

    await waitFor(() => expect(fullName).toHaveValue('Alex Morgan'))
    expect(screen.queryByText(/changed elsewhere/)).not.toBeInTheDocument()
  }, 10000)

  it('rapid edits during an in-flight save use the new version and never 409 against this tab', async () => {
    let resolveFirst!: (value: unknown) => void
    const firstSave = new Promise((resolve) => {
      resolveFirst = resolve
    })
    const versionsSent: number[] = []
    updateResumeBuilderDraft.mockImplementation((_id: number, body: { version: number }) => {
      versionsSent.push(body.version)
      if (versionsSent.length === 1) {
        return firstSave.then(() => ({
          data: {
            ...draftResponse().data,
            ...body,
            version: 2,
          },
        }))
      }
      return Promise.resolve({
        data: {
          ...draftResponse().data,
          ...body,
          version: body.version + 1,
        },
      })
    })

    renderEditor()
    const fullName = await screen.findByLabelText('Full name')
    const user = userEvent.setup()
    await user.type(fullName, 'Al')
    await waitFor(() => expect(updateResumeBuilderDraft).toHaveBeenCalledTimes(1), { timeout: 3000 })
    await user.type(fullName, 'ex')
    resolveFirst(undefined)

    await waitFor(() => expect(updateResumeBuilderDraft.mock.calls.length).toBeGreaterThanOrEqual(2), { timeout: 4000 })
    expect(versionsSent[0]).toBe(1)
    expect(versionsSent[1]).toBe(2)
    expect(fullName).toHaveValue('Alex')
    expect(screen.queryByText(/changed elsewhere/)).not.toBeInTheDocument()
  }, 10000)

  it('the preview tab follows live form readiness and generate still hits the backend', async () => {
    renderEditor()
    await screen.findByLabelText('Full name')
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Preview & generate' }))

    await screen.findByText(/Add your name/)
    expect(generateButton()).toBeDisabled()

    await user.click(screen.getByRole('button', { name: 'Resume sections' }))
    await user.type(screen.getByLabelText('Full name'), 'Alex Morgan')
    await user.type(screen.getByLabelText('Email'), 'alex.morgan@example.com')
    await user.type(screen.getByLabelText('Target role (shown in the draft list)'), 'Junior Software Developer')

    await screen.findByText('Alex Morgan')
    expect(screen.queryByText('(no name entered yet)')).not.toBeInTheDocument()
    expect(generateButton()).toBeEnabled()

    generateResumeBuilderDraft.mockResolvedValue({ data: { id: 99, origin: 'GENERATED' } })
    await user.click(generateButton())

    expect(await screen.findByText('Resume generated.')).toBeInTheDocument()
    expect(generateResumeBuilderDraft).toHaveBeenCalledWith(5, { idempotencyKey: 'test-idempotency-key' })
  }, 10000)

  it('a double-click of generate starts only one generate request', async () => {
    renderEditor()
    await screen.findByLabelText('Full name')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Full name'), 'Alex Morgan')
    await user.type(screen.getByLabelText('Email'), 'alex.morgan@example.com')
    await user.type(screen.getByLabelText('Target role (shown in the draft list)'), 'Junior Software Developer')
    expect(generateButton()).toBeEnabled()

    let resolveGenerate: (value: unknown) => void = () => {}
    const pending = new Promise((resolve) => {
      resolveGenerate = resolve
    })
    generateResumeBuilderDraft.mockImplementation(() => pending)

    const button = generateButton()
    fireEvent.click(button)
    fireEvent.click(button)
    resolveGenerate({ data: { id: 77, origin: 'GENERATED' } })

    expect(await screen.findByText('Resume generated.')).toBeInTheDocument()
    expect(generateResumeBuilderDraft).toHaveBeenCalledTimes(1)
    expect(generateResumeBuilderDraft).toHaveBeenCalledWith(5, { idempotencyKey: 'test-idempotency-key' })
  }, 10000)

  it('GENERIC_ATS does not show a GitHub field by default and keeps skills generic', async () => {
    renderEditor()
    await screen.findByLabelText('Full name')
    expect(screen.queryByLabelText('GitHub')).not.toBeInTheDocument()
    expect(screen.queryByText('GitHub')).not.toBeInTheDocument()
    expect(
      screen.getByText(
        'Add job-related skills, tools, certifications, or core competencies, such as customer service, financial analysis, nursing skills, project management, or programming languages.',
      ),
    ).toBeInTheDocument()
    expect(
      screen.getByText(
        'A project may be a workplace initiative, course project, research activity, event, portfolio piece, or technical system.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByText(/\bJava\b/)).not.toBeInTheDocument()
    expect(screen.queryByText(/\bPython\b/)).not.toBeInTheDocument()
    expect(screen.queryByText(/\bAWS\b/)).not.toBeInTheDocument()
  })

  it('lets a TECHNICAL user add a GitHub professional link', async () => {
    getResumeBuilderDraft.mockResolvedValue(draftResponse())
    renderEditor()
    await screen.findByLabelText('Full name')
    const user = userEvent.setup()
    await user.selectOptions(screen.getByRole('combobox', { name: /Template type/ }), 'TECHNICAL')
    await user.click(screen.getByRole('button', { name: /Add suggested: GitHub/ }))
    await user.type(screen.getByLabelText('URL'), 'https://github.com/alexmorgan')
    expect(screen.getByLabelText('URL')).toHaveValue('https://github.com/alexmorgan')
  })

  it('loads legacy linkedin/github/website scalars into professional links', async () => {
    const content = blankDraftContent()
    delete (content.basicInfo as { professionalLinks?: unknown }).professionalLinks
    content.basicInfo.linkedin = 'https://linkedin.com/in/alexmorgan'
    content.basicInfo.github = 'https://github.com/alexmorgan'
    content.basicInfo.website = 'https://alex.example'
    getResumeBuilderDraft.mockResolvedValue(draftResponse(content))
    renderEditor()
    await screen.findByLabelText('Full name')
    const urls = screen.getAllByLabelText('URL').map((input) => (input as HTMLInputElement).value)
    expect(urls).toEqual([
      'https://linkedin.com/in/alexmorgan',
      'https://github.com/alexmorgan',
      'https://alex.example',
    ])
  })

  it('switching templates does not delete filled skill entries', async () => {
    renderEditor()
    await screen.findByLabelText('Full name')
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Add category' }))
    await user.type(screen.getByLabelText('Category name'), 'Office support')
    await user.selectOptions(screen.getByRole('combobox', { name: /Template type/ }), 'STUDENT')
    expect(screen.getByLabelText('Category name')).toHaveValue('Office support')
    expect(screen.getAllByText('Internships').length).toBeGreaterThan(0)
  })

  it('renders the Chinese skills and project hints', async () => {
    await i18n.changeLanguage('zh-CN')
    renderEditor()
    await screen.findByLabelText('姓名')
    expect(
      screen.getByText(
        '填写与目标职位有关的专业技能、工具、证照或核心能力，例如客户服务、财务分析、护理技能、项目管理、编程语言等。',
      ),
    ).toBeInTheDocument()
    expect(
      screen.getByText('项目可以是工作项目、课程项目、研究、活动策划、作品集项目或技术系统。'),
    ).toBeInTheDocument()
  })
})
