import '../src/i18n'
import type { ReactElement } from 'react'
import { Alert } from 'react-native'
import * as SecureStore from 'expo-secure-store'
import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, userEvent, waitFor } from '@testing-library/react-native'
import { ApiError } from '../src/api/client'
import { jobsApi, resumesApi, tailorApi } from '../src/api/endpoints'
import type { JobResponse, ResumeResponse } from '../src/api/types'
import { TailorScreen } from '../src/screens/TailorScreen'
import { downloadAndShareTailored } from '../src/tailor/files'
import type { TailoredResumeResponse } from '../src/tailor/types'
import { createTestQueryClient } from '../test-utils/render'

jest.mock('../src/api/endpoints', () => ({
  jobsApi: { list: jest.fn() },
  resumesApi: { list: jest.fn() },
  tailorApi: { structure: jest.fn(), canonicalize: jest.fn(), run: jest.fn(), proposal: jest.fn(), confirm: jest.fn(), download: jest.fn() },
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

jest.mock('../src/tailor/files', () => ({
  ...jest.requireActual('../src/tailor/files'),
  downloadAndShareTailored: jest.fn(async () => ({ name: 'x.docx', uri: 'file:///x.docx' })),
}))

const mockAuth = { user: { userId: 1 } as { userId: number } | null }
jest.mock('../src/auth/AuthContext', () => ({ useAuth: () => mockAuth }))

const jobs = jobsApi as jest.Mocked<typeof jobsApi>
const resumes = resumesApi as jest.Mocked<typeof resumesApi>
const api = tailorApi as jest.Mocked<typeof tailorApi>
const share = downloadAndShareTailored as jest.MockedFunction<typeof downloadAndShareTailored>
const store = SecureStore as unknown as { __reset: () => void }
const UUID = '0f8fad5b-d9cb-469f-a165-70867728950e'
const RECORD_KEY = 'aijob.tailor.1.9'

function job(id: number, patch: Partial<JobResponse> = {}): JobResponse {
  return {
    id, company: 'Example Co.', title: 'Data Analyst', location: '', jobUrl: '', description: 'Build dashboards with SQL.',
    source: 'MANUAL', status: 'SAVED', externalJobId: null, applied: false, appliedAt: null,
    createdAt: '2026-09-01T00:00:00', updatedAt: '2026-09-01T00:00:00', ...patch,
  }
}

function resume(id: number, fileName: string, fileType: string, patch: Partial<ResumeResponse> = {}): ResumeResponse {
  return {
    id, fileName, fileType, createdAt: '2026-09-01T00:00:00', updatedAt: `2026-09-0${id % 9}T00:00:00`, origin: 'UPLOAD',
    reviewStatus: 'READY', parentResumeId: null, canonicalResumeId: null, fileAvailable: true, downloadUrl: null, ...patch,
  }
}

const changes = [
  { nodeId: 'p-1', originalText: 'Made reports', newText: 'Built SQL reports for sales', improvementSummaryZh: '更具体', evidenceType: 'VERIFIED', evidenceQuote: 'reports', applied: false },
  { nodeId: 'p-2', originalText: 'Used Excel', newText: 'Used Excel and Power BI', evidenceType: 'INFERRED', inferredSkill: 'Power BI', applied: false },
]

function conservativeResult(patch: Partial<TailoredResumeResponse> = {}): TailoredResumeResponse {
  return {
    resumeId: 4, jobId: 9, mode: 'CONSERVATIVE', generatedFileName: `${UUID}.docx`, downloadUrl: `/api/v1/jobs/9/tailored/${UUID}.docx`,
    pdfDownloadUrl: null, pdfExportStatus: 'FAILED', changes: [{ ...changes[0], applied: true }], unchanged: false, hasSuggestions: true,
    appliedChangeCount: 1, rejectedChangeCount: 1, rejectedChanges: [{ nodeId: 'p-9', reasonCode: 'UNSUPPORTED_CLAIM' }],
    proposalId: null, status: 'APPLIED', expiresAt: null, ...patch,
  }
}

function proposal(patch: Partial<TailoredResumeResponse> = {}): TailoredResumeResponse {
  return {
    resumeId: 4, jobId: 9, mode: 'AGGRESSIVE', generatedFileName: null, downloadUrl: null, pdfDownloadUrl: null, pdfExportStatus: null,
    changes, unchanged: true, hasSuggestions: true, appliedChangeCount: 0, rejectedChangeCount: 0, rejectedChanges: [],
    proposalId: 31, status: 'PENDING', expiresAt: '2099-01-01T00:00:00', ...patch,
  }
}

function confirmedResult(): TailoredResumeResponse {
  return conservativeResult({ mode: 'AGGRESSIVE', proposalId: 31, status: 'CONFIRMED', changes: [{ ...changes[0], applied: true }, { ...changes[1], applied: false }] })
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

async function renderTailor(props: Partial<Parameters<typeof TailorScreen>[0]> = {}) {
  const client = createTestQueryClient()
  const handlers = { onBack: jest.fn(), onOpenJobs: jest.fn(), onOpenResumes: jest.fn(), onLeaveReasonChange: jest.fn() }
  const ui = (): ReactElement => <TailorScreen {...handlers} {...props} />
  const view = await render(ui(), { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> })
  return { ...handlers, client, rerender: () => view.rerender(ui()) }
}

async function choose(jobTitle = 'Data Analyst', fileName = '简历.docx', mode?: string) {
  const user = userEvent.setup()
  await user.press(await screen.findByRole('radio', { name: jobTitle }))
  await user.press(await screen.findByRole('radio', { name: fileName }))
  if (mode) {
    await user.press(screen.getByRole('radio', { name: mode }))
  }
  return user
}

function confirmDialogs(answer: boolean) {
  return jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => {
    const button = answer ? buttons?.[buttons.length - 1] : buttons?.[0]
    button?.onPress?.()
  })
}

beforeEach(() => {
  jest.resetAllMocks()
  jest.restoreAllMocks()
  store.__reset()
  mockAuth.user = { userId: 1 }
  jobs.list.mockResolvedValue([job(9), job(10, { title: 'No Description Job', description: '' })])
  resumes.list.mockResolvedValue([
    resume(4, '简历.docx', 'docx'),
    resume(5, 'scan.pdf', 'pdf'),
    resume(6, 'missing.docx', 'docx', { fileAvailable: false }),
  ])
})

describe('selection', () => {
  it('keeps empty lists and failed requests apart', async () => {
    jobs.list.mockResolvedValue([])
    await renderTailor()
    expect(await screen.findByText('还没有保存的职位。')).toBeOnTheScreen()

    jest.resetAllMocks()
    jobs.list.mockRejectedValue(new ApiError('/jobs', 502, null))
    resumes.list.mockResolvedValue([])
    await renderTailor()
    expect(await screen.findByText('职位暂时无法加载')).toBeOnTheScreen()
    expect(screen.queryByText('还没有保存的职位。')).not.toBeOnTheScreen()
  })

  it('only offers jobs with a description and available PDF or DOCX files', async () => {
    await renderTailor()
    expect(await screen.findByRole('radio', { name: 'No Description Job' })).toBeDisabled()
    expect(screen.getByText('没有职位描述，无法改写')).toBeOnTheScreen()
    expect(screen.getByRole('radio', { name: 'missing.docx' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '开始改写' })).toBeDisabled()
  })
})

describe('CONSERVATIVE', () => {
  it('generates directly, shows the real counts and rejections, and shares via the tailored route', async () => {
    api.run.mockResolvedValueOnce(conservativeResult())
    await renderTailor()
    const user = await choose()
    await user.press(screen.getByRole('button', { name: '开始改写' }))

    expect(await screen.findByText('文件已生成')).toBeOnTheScreen()
    expect(api.run).toHaveBeenCalledWith(9, 4, 'CONSERVATIVE')
    expect(api.confirm).not.toHaveBeenCalled()
    expect(api.proposal).not.toHaveBeenCalled()
    expect(api.structure).not.toHaveBeenCalled()
    expect(screen.getByText('写入 1 条')).toBeOnTheScreen()
    expect(screen.getByText('未采用 1 条')).toBeOnTheScreen()
    expect(screen.getByText('· 包含简历中不存在的主张')).toBeOnTheScreen()
    expect(screen.getByText('PDF 导出失败，可以下载 DOCX。')).toBeOnTheScreen()
    expect(screen.queryByRole('button', { name: '下载 PDF' })).not.toBeOnTheScreen()

    await user.press(screen.getByRole('button', { name: '下载 DOCX 并分享' }))
    expect(share).toHaveBeenCalledWith({ path: `/jobs/9/tailored/${UUID}.docx`, fileType: 'docx' }, '简历 - Data Analyst.docx')
  })

  it('reports an unchanged result as unchanged', async () => {
    api.run.mockResolvedValueOnce(conservativeResult({ changes: [], appliedChangeCount: 0, unchanged: true, status: 'NO_SUPPORTED_GAP', rejectedChanges: [], rejectedChangeCount: 0 }))
    await renderTailor()
    const user = await choose()
    await user.press(screen.getByRole('button', { name: '开始改写' }))
    expect(await screen.findByText('没有写入任何改写，生成的文件内容与原简历相同。')).toBeOnTheScreen()
    expect(screen.getByText('写入 0 条')).toBeOnTheScreen()
    expect(screen.getByText('没有可安全改写的重叠内容')).toBeOnTheScreen()
  })

  it('turns a PDF into its Word copy first and tailors that copy', async () => {
    api.structure.mockResolvedValueOnce({ resumeId: 5 })
    api.canonicalize.mockResolvedValueOnce(resume(77, 'scan.docx', 'docx', { origin: 'TEMPLATE' }))
    api.run.mockResolvedValueOnce(conservativeResult({ resumeId: 5 }))
    await renderTailor()
    const user = await choose('Data Analyst', 'scan.pdf')
    expect(screen.getByText('PDF 会先识别并转换为可编辑的 Word，再进行改写。')).toBeOnTheScreen()
    await user.press(screen.getByRole('button', { name: '开始改写' }))
    expect(await screen.findByText('文件已生成')).toBeOnTheScreen()
    expect(api.canonicalize).toHaveBeenCalledWith(5)
    expect(api.run).toHaveBeenCalledWith(9, 77, 'CONSERVATIVE')
    expect(api.structure.mock.invocationCallOrder[0]).toBeLessThan(api.canonicalize.mock.invocationCallOrder[0])
  })

  it('stops when PDF recognition is incomplete, opens the review with this context, and prepares again from the saved content', async () => {
    api.structure.mockResolvedValue({ resumeId: 5 })
    api.canonicalize.mockRejectedValueOnce(new ApiError('/resumes/5/canonicalize', 400, { message: 'Full name is required' }))
    const onOpenStructure = jest.fn()
    await renderTailor({ onOpenStructure })
    const user = await choose('Data Analyst', 'scan.pdf')
    await user.press(screen.getByRole('button', { name: '开始改写' }))
    expect(await screen.findByText('改写没有成功：PDF 自动识别的信息不完整，请先确认识别结果后再改写。')).toBeOnTheScreen()
    expect(api.run).not.toHaveBeenCalled()

    await user.press(screen.getByRole('button', { name: '确认识别结果' }))
    expect(onOpenStructure).toHaveBeenCalledWith(5, 9, 'CONSERVATIVE')
    expect(api.canonicalize).toHaveBeenCalledTimes(1)

    // Back from saving the structure: nothing runs until the user starts again.
    api.canonicalize.mockResolvedValueOnce(resume(77, 'scan.docx', 'docx', { origin: 'TEMPLATE' }))
    api.run.mockResolvedValueOnce(conservativeResult({ resumeId: 5 }))
    await user.press(screen.getByRole('button', { name: '开始改写' }))
    expect(await screen.findByText('文件已生成')).toBeOnTheScreen()
    expect(api.structure).toHaveBeenCalledTimes(2)
    expect(api.canonicalize).toHaveBeenCalledTimes(2)
    expect(api.run).toHaveBeenCalledWith(9, 77, 'CONSERVATIVE')
  })

  it('does not offer the review entry for a DOCX or for other failures', async () => {
    api.run.mockRejectedValueOnce(new ApiError('/jobs/9/tailor', 400, { message: 'Resume tailoring currently supports DOCX only' }))
    await renderTailor({ onOpenStructure: jest.fn() })
    const user = await choose()
    await user.press(screen.getByRole('button', { name: '开始改写' }))
    await screen.findByText(/改写没有成功/)
    expect(screen.queryByRole('button', { name: '确认识别结果' })).not.toBeOnTheScreen()
  })

  it('runs once per tap, marks leaving as busy, and never resubmits an unknown outcome by itself', async () => {
    const pending = deferred<TailoredResumeResponse>()
    api.run.mockReturnValueOnce(pending.promise)
    const { onLeaveReasonChange } = await renderTailor()
    const user = await choose()
    await user.press(screen.getByRole('button', { name: '开始改写' }))
    const busy = await screen.findByRole('button', { name: '正在根据职位改写，可能需要一两分钟…' })
    expect(busy).toBeDisabled()
    await user.press(busy)
    expect(api.run).toHaveBeenCalledTimes(1)
    expect(onLeaveReasonChange).toHaveBeenLastCalledWith('busy')

    pending.reject(new ApiError('/jobs/9/tailor', null, null))
    expect(await screen.findByText(/暂时无法确认文件是否已经生成/)).toBeOnTheScreen()
    expect(api.run).toHaveBeenCalledTimes(1)
    expect(onLeaveReasonChange).toHaveBeenLastCalledWith(null)

    const alert = confirmDialogs(false)
    await user.press(screen.getByRole('button', { name: '重新改写' }))
    expect(alert).toHaveBeenCalledWith('重新改写？', expect.stringContaining('再生成一份文件'), expect.any(Array), expect.anything())
    expect(api.run).toHaveBeenCalledTimes(1)
  })

  it('shows a definite failure', async () => {
    api.run.mockRejectedValueOnce(new ApiError('/jobs/9/tailor', 422, { message: 'Job description is not available for matching' }))
    await renderTailor()
    const user = await choose()
    await user.press(screen.getByRole('button', { name: '开始改写' }))
    expect(await screen.findByText('改写没有成功：这个职位没有职位描述，无法改写。')).toBeOnTheScreen()
  })
})

describe('AGGRESSIVE', () => {
  it('previews without a file or a confirm call, and cancelling generates nothing', async () => {
    api.run.mockResolvedValueOnce(proposal())
    await renderTailor()
    const user = await choose('Data Analyst', '简历.docx', '增强建议（确认后生成）')
    await user.press(screen.getByRole('button', { name: '生成增强建议' }))

    expect(await screen.findByText('增强建议（尚未生成文件）')).toBeOnTheScreen()
    expect(api.run).toHaveBeenCalledWith(9, 4, 'AGGRESSIVE')
    expect(screen.getByText('Built SQL reports for sales')).toBeOnTheScreen()
    expect(screen.getByText('Made reports')).toBeOnTheScreen()
    expect(screen.getByText('推断技能：Power BI。原简历中没有直接证据，请确认属实。')).toBeOnTheScreen()
    expect(screen.getByText('证据摘录：reports')).toBeOnTheScreen()
    expect(screen.queryByText('文件已生成')).not.toBeOnTheScreen()
    expect(screen.queryByRole('button', { name: '下载 DOCX 并分享' })).not.toBeOnTheScreen()
    expect(api.confirm).not.toHaveBeenCalled()

    const raw = await SecureStore.getItemAsync(RECORD_KEY)
    expect(JSON.parse(raw as string)).toEqual({
      proposalId: 31,
      jobId: 9,
      resumeId: 4,
      expiresAt: '2099-01-01T00:00:00',
      savedAt: expect.any(String),
    })
    expect(raw).not.toContain('SQL')

    confirmDialogs(true)
    await user.press(screen.getByRole('button', { name: '放弃这份建议' }))
    expect(await screen.findByRole('button', { name: '生成增强建议' })).toBeOnTheScreen()
    expect(api.confirm).not.toHaveBeenCalled()
    expect(await SecureStore.getItemAsync(RECORD_KEY)).toBeNull()
  })

  it('generates only after an explicit confirmation, once, with every proposed node', async () => {
    api.run.mockResolvedValueOnce(proposal())
    const pending = deferred<TailoredResumeResponse>()
    api.confirm.mockReturnValueOnce(pending.promise)
    await renderTailor()
    const user = await choose('Data Analyst', '简历.docx', '增强建议（确认后生成）')
    await user.press(screen.getByRole('button', { name: '生成增强建议' }))
    await user.press(await screen.findByRole('button', { name: '确认并生成文件' }))
    const busy = await screen.findByRole('button', { name: '正在生成文件…' })
    await user.press(busy)
    expect(api.confirm).toHaveBeenCalledTimes(1)
    expect(api.confirm).toHaveBeenCalledWith(9, 31, { selectedNodeIds: ['p-1', 'p-2'] })

    pending.resolve(confirmedResult())
    expect(await screen.findByText('文件已生成')).toBeOnTheScreen()
    expect(screen.getByText('已写入文件')).toBeOnTheScreen()
    expect(screen.getByText('未写入文件')).toBeOnTheScreen()
    expect(await SecureStore.getItemAsync(RECORD_KEY)).toBeNull()
  })

  it('does not offer confirmation for an expired or empty proposal', async () => {
    api.run.mockResolvedValueOnce(proposal({ expiresAt: '2020-01-01T00:00:00' }))
    await renderTailor()
    const user = await choose('Data Analyst', '简历.docx', '增强建议（确认后生成）')
    await user.press(screen.getByRole('button', { name: '生成增强建议' }))
    expect(await screen.findByText('这份建议已过期，不能再确认。')).toBeOnTheScreen()
    expect(screen.getByRole('button', { name: '确认并生成文件' })).toBeDisabled()

    confirmDialogs(true)
    await user.press(screen.getByRole('button', { name: '放弃这份建议' }))
    api.run.mockResolvedValueOnce(proposal({ changes: [], hasSuggestions: false }))
    await user.press(await screen.findByRole('button', { name: '生成增强建议' }))
    expect(await screen.findByText('没有可以写入的建议，不会生成文件。')).toBeOnTheScreen()
    expect(screen.queryByRole('button', { name: '确认并生成文件' })).not.toBeOnTheScreen()
  })

  it('handles an expired/confirmed 404 and a confirmation already in progress by the real contract', async () => {
    api.run.mockResolvedValueOnce(proposal())
    api.confirm.mockRejectedValueOnce(new ApiError('/c', 409, { message: 'Tailor proposal is already being confirmed' }))
    await renderTailor()
    const user = await choose('Data Analyst', '简历.docx', '增强建议（确认后生成）')
    await user.press(screen.getByRole('button', { name: '生成增强建议' }))
    await user.press(await screen.findByRole('button', { name: '确认并生成文件' }))
    expect(await screen.findByText('服务器还在确认这份建议，请稍后再查询。')).toBeOnTheScreen()

    api.proposal.mockResolvedValueOnce(proposal())
    await user.press(screen.getByRole('button', { name: '查询确认结果' }))
    expect(await screen.findByText('上次确认没有完成，这份建议仍然可以确认。')).toBeOnTheScreen()

    api.confirm.mockRejectedValueOnce(new ApiError('/c', 404, { message: 'Tailor proposal not found' }))
    await user.press(screen.getByRole('button', { name: '确认并生成文件' }))
    expect(await screen.findByText('这份建议已不可用：可能已被确认，或已经过期。')).toBeOnTheScreen()
    expect(api.confirm).toHaveBeenCalledTimes(2)
    expect(await SecureStore.getItemAsync(RECORD_KEY)).toBeNull()
  })

  it('checks a lost confirmation instead of confirming again', async () => {
    api.run.mockResolvedValueOnce(proposal())
    api.confirm.mockRejectedValueOnce(new ApiError('/c', null, null))
    await renderTailor()
    const user = await choose('Data Analyst', '简历.docx', '增强建议（确认后生成）')
    await user.press(screen.getByRole('button', { name: '生成增强建议' }))
    await user.press(await screen.findByRole('button', { name: '确认并生成文件' }))
    expect(await screen.findByText(/暂时无法确认文件是否已经生成/)).toBeOnTheScreen()
    expect(screen.queryByRole('button', { name: '确认并生成文件' })).not.toBeOnTheScreen()

    api.proposal.mockRejectedValueOnce(new ApiError('/p', 404, { message: 'Tailor proposal not found' }))
    await user.press(screen.getByRole('button', { name: '查询确认结果' }))
    expect(await screen.findByText(/文件可能已经生成，但后端没有提供取回已生成文件的接口/)).toBeOnTheScreen()
    expect(api.confirm).toHaveBeenCalledTimes(1)
    expect(api.proposal).toHaveBeenCalledWith(9, 31)
  })

  it('keeps a definite confirm failure as a confirmable preview', async () => {
    api.run.mockResolvedValueOnce(proposal())
    api.confirm.mockRejectedValueOnce(new ApiError('/c', 400, { message: 'selectedNodeIds must not be empty' }))
    await renderTailor()
    const user = await choose('Data Analyst', '简历.docx', '增强建议（确认后生成）')
    await user.press(screen.getByRole('button', { name: '生成增强建议' }))
    await user.press(await screen.findByRole('button', { name: '确认并生成文件' }))
    expect(await screen.findByText(/生成文件没有成功：请求无效/)).toBeOnTheScreen()
    expect(screen.getByRole('button', { name: '确认并生成文件' })).toBeEnabled()
  })

  it('blocks a restored proposal made before the PDF recognized content was saved', async () => {
    await SecureStore.setItemAsync(
      RECORD_KEY,
      JSON.stringify({ proposalId: 31, jobId: 9, resumeId: 5, expiresAt: '2099-01-01T00:00:00', savedAt: '2026-09-14T08:00:00.000Z' }),
    )
    await SecureStore.setItemAsync('aijob.structureSavedAt.1.5', '2026-09-14T09:00:00.000Z')
    api.proposal.mockResolvedValueOnce(proposal({ resumeId: 77 }))
    await renderTailor({ initialJobId: 9 })
    expect(await screen.findByText(/识别结果已经修改/)).toBeOnTheScreen()
    expect(screen.getByRole('button', { name: '确认并生成文件' })).toBeDisabled()
    expect(api.confirm).not.toHaveBeenCalled()
    expect(api.canonicalize).not.toHaveBeenCalled()
  })

  it('refuses to confirm when the recognized content is saved while the preview is open', async () => {
    api.structure.mockResolvedValueOnce({ resumeId: 5 })
    api.canonicalize.mockResolvedValueOnce(resume(77, 'scan.docx', 'docx', { origin: 'TEMPLATE' }))
    api.run.mockResolvedValueOnce(proposal({ resumeId: 5 }))
    await renderTailor()
    const user = await choose('Data Analyst', 'scan.pdf', '增强建议（确认后生成）')
    await user.press(screen.getByRole('button', { name: '生成增强建议' }))
    await screen.findByText('增强建议（尚未生成文件）')

    await SecureStore.setItemAsync('aijob.structureSavedAt.1.5', new Date(Date.now() + 60_000).toISOString())
    await user.press(screen.getByRole('button', { name: '确认并生成文件' }))
    expect(await screen.findByText(/识别结果已经修改/)).toBeOnTheScreen()
    expect(api.confirm).not.toHaveBeenCalled()
  })

  it('reopens an unconfirmed preview for the same account and job', async () => {
    await SecureStore.setItemAsync(RECORD_KEY, JSON.stringify({ proposalId: 31, jobId: 9, resumeId: 4, expiresAt: '2099-01-01T00:00:00' }))
    await SecureStore.setItemAsync('aijob.tailor.2.10', JSON.stringify({ proposalId: 99, jobId: 10, resumeId: 4, expiresAt: null }))
    api.proposal.mockResolvedValueOnce(proposal())
    await renderTailor({ initialJobId: 9 })
    expect(await screen.findByText('已恢复上次还没有确认的建议。')).toBeOnTheScreen()
    expect(api.proposal).toHaveBeenCalledTimes(1)
    expect(api.proposal).toHaveBeenCalledWith(9, 31)
    expect(api.run).not.toHaveBeenCalled()
    expect(screen.getByRole('radio', { name: '简历.docx' })).toBeChecked()
  })
})

it('ignores a result that arrives after another account signed in', async () => {
  const pending = deferred<TailoredResumeResponse>()
  api.run.mockReturnValueOnce(pending.promise)
  const { rerender } = await renderTailor()
  const user = await choose('Data Analyst', '简历.docx', '增强建议（确认后生成）')
  await user.press(screen.getByRole('button', { name: '生成增强建议' }))

  mockAuth.user = { userId: 2 }
  await rerender()
  pending.resolve(proposal())
  await new Promise((resolve) => setTimeout(resolve, 0))
  await waitFor(() => expect(api.run).toHaveBeenCalledTimes(1))

  expect(screen.queryByText('增强建议（尚未生成文件）')).not.toBeOnTheScreen()
  expect(await SecureStore.getItemAsync(RECORD_KEY)).toBeNull()
  expect(await SecureStore.getItemAsync('aijob.tailor.2.9')).toBeNull()
})
