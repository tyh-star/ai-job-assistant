import '../src/i18n'
import type { ReactElement } from 'react'
import { Alert } from 'react-native'
import * as SecureStore from 'expo-secure-store'
import { QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, userEvent, waitFor } from '@testing-library/react-native'
import { ApiError } from '../src/api/client'
import { resumesApi, tailorApi } from '../src/api/endpoints'
import { resumeKeys } from '../src/resumes/queries'
import { ResumeStructureScreen } from '../src/screens/ResumeStructureScreen'
import { createTestQueryClient } from '../test-utils/render'

jest.mock('../src/api/endpoints', () => ({
  resumesApi: { structure: jest.fn(), updateStructure: jest.fn() },
  tailorApi: { structure: jest.fn(), canonicalize: jest.fn(), run: jest.fn() },
  jobsApi: {},
  draftsApi: {},
  catalogApi: {},
  authApi: {},
}))

jest.mock('expo-secure-store', () => {
  const mockValues = new Map<string, string>()
  return {
    getItemAsync: async (key: string) => mockValues.get(key) ?? null,
    setItemAsync: async (key: string, value: string) => void mockValues.set(key, value),
    deleteItemAsync: async (key: string) => void mockValues.delete(key),
    __reset: () => mockValues.clear(),
  }
})

const mockAuth = { user: { userId: 1 } as { userId: number } | null }
jest.mock('../src/auth/AuthContext', () => ({ useAuth: () => mockAuth }))

const api = resumesApi as jest.Mocked<typeof resumesApi>
const tailor = tailorApi as jest.Mocked<typeof tailorApi>
const store = SecureStore as unknown as { __reset: () => void }
const MARKER = 'aijob.structureSavedAt.1.5'

function recognized(patch: Record<string, unknown> = {}) {
  return {
    resumeId: 5,
    reviewStatus: 'PENDING_REVIEW',
    extractionStatus: 'LAYOUT',
    llmClassified: true,
    structureFormatVersion: 2,
    contact: { fullName: '', email: 'person@example.test', phone: '', location: 'Toronto', links: [] },
    summary: '',
    summaryProvenance: { provenance: 'LLM' },
    summaryBullets: [{ text: 'Analyst with reporting experience', sourceBlockIds: ['b1'], sourcePage: 1, confidence: 'HIGH', provenance: 'LLM' }],
    skills: [{ name: 'SQL', sourceBlockIds: ['b2'], confidence: 'MEDIUM', provenance: 'LLM' }],
    experience: [
      { company: 'Example Co.', title: 'Analyst', startDate: '2024-01', endDate: '', location: '', bullets: ['Built reports'], sourceBlockIds: ['b3'], provenance: 'LLM' },
    ],
    education: [],
    projects: [],
    unassigned: [{ id: 'b9', page: 1, x: 10, y: 20, width: 100, height: 10, fontSize: 10, text: 'References available on request', features: [] }],
    fieldConfidence: { 'contact.fullName': 'LOW' },
    fieldProvenance: { summary: 'LLM' },
    layoutProfile: { version: 1, pageSize: 'LETTER', confidence: 'HIGH' },
    ...patch,
  }
}

function deferred<T>() {
  let resolve: (value: T) => void = () => {}
  let reject: (error: unknown) => void = () => {}
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

async function renderStructure(props: Partial<Parameters<typeof ResumeStructureScreen>[0]> = {}) {
  const client = createTestQueryClient()
  const handlers = { onBack: jest.fn(), onContinueTailor: jest.fn(), onLeaveReasonChange: jest.fn() }
  const ui = (): ReactElement => <ResumeStructureScreen resumeId={5} {...handlers} {...props} />
  const view = await render(ui(), { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> })
  return { ...handlers, client, rerender: () => view.rerender(ui()) }
}

beforeEach(() => {
  jest.resetAllMocks()
  jest.restoreAllMocks()
  store.__reset()
  mockAuth.user = { userId: 1 }
})

it('shows what was recognized, leaves missing fields empty and says the PDF is not changed', async () => {
  api.structure.mockResolvedValue(recognized())
  await renderStructure()
  expect(await screen.findByLabelText('姓名（必填）')).toHaveDisplayValue('')
  expect(api.structure).toHaveBeenCalledWith(5)
  expect(screen.getByLabelText('邮箱')).toHaveDisplayValue('person@example.test')
  expect(screen.getByLabelText('技能')).toHaveDisplayValue('SQL')
  expect(screen.getByLabelText('公司')).toHaveDisplayValue('Example Co.')
  expect(screen.getByText('References available on request')).toBeOnTheScreen()
  expect(screen.getByText(/原 PDF 文件不会被修改/)).toBeOnTheScreen()
  expect(screen.getByRole('button', { name: '保存识别结果' })).toBeDisabled()
})

it('shows a missing resume as not found', async () => {
  api.structure.mockRejectedValueOnce(new ApiError('/resumes/5/structure', 404, { message: 'Resume not found' }))
  await renderStructure()
  expect(await screen.findByText('找不到这份简历')).toBeOnTheScreen()
})

it('shows a non-PDF as having no recognized content', async () => {
  api.structure.mockRejectedValueOnce(new ApiError('/resumes/5/structure', 400, { message: 'Resume structure is only available for PDF resumes' }))
  await renderStructure()
  expect(await screen.findByText('只有 PDF 简历有识别结果。')).toBeOnTheScreen()
})

it('offers a retry after a failed read', async () => {
  api.structure.mockRejectedValueOnce(new ApiError('/resumes/5/structure', 502, null)).mockResolvedValueOnce(recognized())
  await renderStructure()
  expect(await screen.findByText('识别结果暂时无法加载')).toBeOnTheScreen()
  await userEvent.setup().press(screen.getByRole('button', { name: '重试' }))
  expect(await screen.findByLabelText('姓名（必填）')).toBeOnTheScreen()
})

it('needs a name before saving', async () => {
  api.structure.mockResolvedValue(recognized())
  await renderStructure()
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('电话'), '555')
  await user.press(screen.getByRole('button', { name: '保存识别结果' }))
  expect(screen.getByText('姓名不能为空')).toBeOnTheScreen()
  expect(api.updateStructure).not.toHaveBeenCalled()
})

it('saves the whole structure with only the edits, keeps input on failure, and marks the save on this device', async () => {
  api.structure.mockResolvedValue(recognized())
  const { onLeaveReasonChange, client } = await renderStructure()
  const invalidate = jest.spyOn(client, 'invalidateQueries')
  const user = userEvent.setup()

  await user.type(await screen.findByLabelText('姓名（必填）'), 'Test Person')
  fireEvent.changeText(screen.getByLabelText('技能'), 'PostgreSQL\nPython')
  expect(onLeaveReasonChange).toHaveBeenLastCalledWith('unsaved')

  api.updateStructure.mockRejectedValueOnce(new ApiError('/resumes/5/structure', 502, null))
  await user.press(screen.getByRole('button', { name: '保存识别结果' }))
  expect(await screen.findByText('识别结果没有保存：服务暂时不可用，请稍后再试。 你的修改都已保留。')).toBeOnTheScreen()
  expect(screen.getByLabelText('姓名（必填）')).toHaveDisplayValue('Test Person')
  expect(screen.queryByText('识别结果已保存。')).not.toBeOnTheScreen()
  expect(await SecureStore.getItemAsync(MARKER)).toBeNull()
  expect(onLeaveReasonChange).toHaveBeenLastCalledWith('unsaved')

  const original = recognized()
  const expected = {
    ...original,
    contact: { ...original.contact, fullName: 'Test Person' },
    skills: [{ ...original.skills[0], name: 'PostgreSQL' }, { name: 'Python' }],
  }
  api.updateStructure.mockResolvedValueOnce({ ...expected, fieldProvenance: { summary: 'LLM', 'contact.fullName': 'USER_EDITED' } })
  await user.press(screen.getByRole('button', { name: '保存识别结果' }))
  expect(await screen.findByText('识别结果已保存。')).toBeOnTheScreen()

  expect(api.updateStructure).toHaveBeenLastCalledWith(5, expected)
  const body = api.updateStructure.mock.calls[1][1]
  expect(body.layoutProfile).toEqual(original.layoutProfile)
  expect(body.unassigned).toEqual(original.unassigned)
  expect(body.summaryBullets).toEqual(original.summaryBullets)
  expect(body.experience).toEqual(original.experience)
  expect(Date.parse((await SecureStore.getItemAsync(MARKER)) as string)).not.toBeNaN()
  expect(invalidate).toHaveBeenCalledWith({ queryKey: resumeKeys.files(1), exact: true })
  expect(invalidate).toHaveBeenCalledWith({ queryKey: resumeKeys.file(1, 5) })
  await waitFor(() => expect(onLeaveReasonChange).toHaveBeenLastCalledWith(null))
  expect(screen.getByRole('button', { name: '保存识别结果' })).toBeDisabled()
})

it('asks before removing an entry that has content', async () => {
  api.structure.mockResolvedValue(recognized())
  const alert = jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => buttons?.[0]?.onPress?.())
  await renderStructure()
  const user = userEvent.setup()
  await user.press(await screen.findByRole('button', { name: '删除' }))
  expect(alert).toHaveBeenCalledWith('删除这一项？', '保存识别结果后才会生效。', expect.any(Array), expect.anything())
  expect(screen.getByLabelText('公司')).toBeOnTheScreen()

  jest.restoreAllMocks()
  jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => buttons?.[buttons.length - 1]?.onPress?.())
  await user.press(screen.getByRole('button', { name: '删除' }))
  expect(screen.queryByLabelText('公司')).not.toBeOnTheScreen()
  await user.press(screen.getByRole('button', { name: '添加经历' }))
  expect(screen.getByLabelText('公司')).toHaveDisplayValue('')
})

it('offers the way back to tailoring after a save, without tailoring by itself', async () => {
  api.structure.mockResolvedValue(recognized({ contact: { fullName: 'Test Person', email: '', phone: '', location: '', links: [] } }))
  api.updateStructure.mockImplementation(async (_id, body) => body)
  const { onContinueTailor } = await renderStructure({ fromTailor: true })
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('电话'), '555')
  expect(screen.queryByRole('button', { name: '返回改写' })).not.toBeOnTheScreen()
  await user.press(screen.getByRole('button', { name: '保存识别结果' }))
  expect(await screen.findByText('识别结果已保存。回到改写后请重新开始，App 不会自动改写。')).toBeOnTheScreen()
  await user.press(screen.getByRole('button', { name: '返回改写' }))
  expect(onContinueTailor).toHaveBeenCalledTimes(1)
  expect(tailor.structure).not.toHaveBeenCalled()
  expect(tailor.canonicalize).not.toHaveBeenCalled()
  expect(tailor.run).not.toHaveBeenCalled()
})

it('drops a read that finishes after another account signed in', async () => {
  const pending = deferred<unknown>()
  api.structure.mockReturnValueOnce(pending.promise)
  const { rerender } = await renderStructure()
  mockAuth.user = { userId: 2 }
  await rerender()
  pending.resolve(recognized())
  await new Promise((resolve) => setTimeout(resolve, 0))
  expect(screen.queryByLabelText('姓名（必填）')).not.toBeOnTheScreen()
})

it('drops a save that finishes after another account signed in', async () => {
  api.structure.mockResolvedValue(recognized())
  const pending = deferred<unknown>()
  api.updateStructure.mockReturnValueOnce(pending.promise)
  const { rerender } = await renderStructure()
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('姓名（必填）'), 'Test Person')
  await user.press(screen.getByRole('button', { name: '保存识别结果' }))

  mockAuth.user = { userId: 2 }
  await rerender()
  pending.resolve(recognized())
  await new Promise((resolve) => setTimeout(resolve, 0))
  expect(screen.queryByText('识别结果已保存。')).not.toBeOnTheScreen()
  expect(await SecureStore.getItemAsync(MARKER)).toBeNull()
  expect(await SecureStore.getItemAsync('aijob.structureSavedAt.2.5')).toBeNull()
})
