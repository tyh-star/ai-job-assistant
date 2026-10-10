import '../src/i18n'
import type { ReactElement } from 'react'
import { Alert } from 'react-native'
import * as SecureStore from 'expo-secure-store'
import { QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, userEvent, waitFor } from '@testing-library/react-native'
import { ApiError } from '../src/api/client'
import { enhancedApi, evidenceApi, jobsApi, resumesApi, tailorApi } from '../src/api/endpoints'
import type { JobResponse, ResumeResponse } from '../src/api/types'
import { TailorScreen } from '../src/screens/TailorScreen'
import { downloadAndShareTailored } from '../src/tailor/files'
import type { ResumeEvidenceItem, TailoredResumeResponse, V3OpportunityMap } from '../src/tailor/types'
import { createTestQueryClient } from '../test-utils/render'

jest.mock('../src/api/endpoints', () => ({
  jobsApi: { list: jest.fn() },
  resumesApi: { list: jest.fn() },
  tailorApi: { structure: jest.fn(), canonicalize: jest.fn(), run: jest.fn(), proposal: jest.fn(), confirm: jest.fn(), download: jest.fn() },
  enhancedApi: { scan: jest.fn(), answer: jest.fn(), createProposal: jest.fn(), confirm: jest.fn() },
  evidenceApi: { list: jest.fn(), save: jest.fn(), remove: jest.fn() },
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
const tailor = tailorApi as jest.Mocked<typeof tailorApi>
const enhanced = enhancedApi as jest.Mocked<typeof enhancedApi>
const evidence = evidenceApi as jest.Mocked<typeof evidenceApi>
const share = downloadAndShareTailored as jest.MockedFunction<typeof downloadAndShareTailored>
const store = SecureStore as unknown as { __reset: () => void }

const UUID = '0f8fad5b-d9cb-469f-a165-70867728950e'
const ENHANCED_KEY = 'aijob.tailor.1.9.ENHANCED'
const LEGACY_KEY = 'aijob.tailor.1.9'
const ENHANCED_MODE = '深度增强（问答后审阅确认）'

function job(id: number, patch: Partial<JobResponse> = {}): JobResponse {
  return {
    id, company: 'Example Co.', title: 'Data Analyst', location: '', jobUrl: '', description: 'Build dashboards with SQL.',
    source: 'MANUAL', status: 'SAVED', externalJobId: null, applied: false, appliedAt: null,
    createdAt: '2026-09-01T00:00:00', updatedAt: '2026-09-01T00:00:00', ...patch,
  }
}

function resume(id: number, fileName: string, fileType: string): ResumeResponse {
  return {
    id, fileName, fileType, createdAt: '2026-09-01T00:00:00', updatedAt: `2026-09-0${id}T00:00:00`, origin: 'UPLOAD',
    reviewStatus: 'READY', parentResumeId: null, canonicalResumeId: null, fileAvailable: true, downloadUrl: null,
  }
}

function opportunity(questions: V3OpportunityMap['questions'] = []): V3OpportunityMap {
  const entry = (canonicalEntryKey: string, entryTitle: string, priority: string) => ({
    canonicalEntryKey, entryTitle, entryType: 'EXPERIENCE', priority,
    coveredRequirements: ['SQL reporting'], partiallyCoveredRequirements: ['Dashboards'], canStrengthenWithExistingFacts: [],
    missingFactsThatCanBeConfirmed: [], cannotInvent: [],
  })
  return {
    entries: [entry('exp-1', 'Analyst at Example', 'HIGH'), entry('proj-1', 'Sales dashboard project', 'MEDIUM')],
    questions, skippedQuestionIds: [], writerCalls: 0, openaiCalls: 0, wroteJobOrResume: false,
  }
}

const QUESTIONS: V3OpportunityMap['questions'] = [
  { id: 'q1', canonicalEntryKey: 'exp-1', entryTitle: 'Analyst at Example', topic: 'dashboards', promptEn: 'Did you build dashboards?',
    promptZh: '你是否做过仪表盘？', skipAllowed: true, requirementText: 'Build dashboards', allowedEntryKeys: ['exp-1', 'proj-1'] },
  { id: 'q2', canonicalEntryKey: 'exp-1', entryTitle: 'Analyst at Example', topic: 'sql', promptEn: 'Did you write SQL?',
    promptZh: '你是否写过 SQL？', skipAllowed: true },
]

function enhancedProposal(patch: Partial<TailoredResumeResponse> = {}): TailoredResumeResponse {
  return {
    resumeId: 4, jobId: 9, mode: 'CONSERVATIVE', productMode: 'ENHANCED', engineVersion: 'V3', proposalId: 51, status: 'PENDING',
    expiresAt: '2099-01-01T00:00:00', generatedFileName: null, downloadUrl: null, pdfDownloadUrl: null, pdfExportStatus: null,
    changes: [], unchanged: true, hasSuggestions: true, appliedChangeCount: 0, rejectedChangeCount: 0, rejectedChanges: [],
    entryReviews: [
      { canonicalEntryKey: 'exp-1', entryTitle: 'Analyst at Example', whyBetter: 'Specific', whyBetterZh: '更具体', suggestionType: 'EVIDENCE_ENHANCED',
        usedEvidenceItemIds: [81], originalNodes: [{ nodeId: 'p-1', text: 'Made reports' }, { nodeId: 'p-2', text: 'Used Excel' }],
        proposedNodes: [{ nodeId: 'p-1', text: 'Built SQL reports' }, { nodeId: 'p-2', text: 'Built Excel dashboards' }] },
      { canonicalEntryKey: 'proj-1', entryTitle: 'Sales dashboard project', whyBetter: '', whyBetterZh: '贴近职位', suggestionType: 'JD_TARGETED',
        usedEvidenceItemIds: [], originalNodes: [{ nodeId: 'p-5', text: 'Built a dashboard' }], proposedNodes: [{ nodeId: 'p-5', text: 'Built a sales dashboard' }] },
    ],
    skillReviews: [
      { skillReviewId: 'skills:technical', originalSkills: 'SQL, Excel', proposedSkills: 'SQL, Excel, Power BI', addedSkills: ['Power BI'],
        admitted: true, originalNodes: [{ nodeId: 's-1', text: 'SQL, Excel' }], proposedNodes: [{ nodeId: 's-1', text: 'SQL, Excel, Power BI' }] },
    ],
    evidenceUsage: [
      { evidenceItemId: 81, canonicalEntryKey: 'exp-1', topicKey: 'dashboards', usageStatus: 'USED_IN_ENTRY_AND_SKILLS', usedInEntryKeys: ['exp-1'], usedInSkillKeys: ['Power BI'] },
    ],
    ...patch,
  }
}

const EVIDENCE: ResumeEvidenceItem[] = [
  { id: 81, resumeId: 4, topic: 'dashboards', topicKey: 'dashboards', category: 'HARD_SKILL', answerStatus: 'CONFIRMED_USED', entryKey: 'exp-1',
    entryLabel: 'Analyst at Example', relatedNodeIds: [], context: '', action: 'Built weekly Power BI dashboards', result: '', metric: '' },
]

function confirmedResult(): TailoredResumeResponse {
  return enhancedProposal({
    status: 'CONFIRMED', generatedFileName: `${UUID}.docx`, downloadUrl: `/api/v1/jobs/9/tailored/${UUID}.docx`,
    pdfDownloadUrl: `/api/v1/jobs/9/tailored/${UUID}.pdf`, pdfExportStatus: 'SUCCESS', unchanged: false,
    appliedChangeCount: 3, rejectedChangeCount: 1, rejectedChanges: [{ nodeId: 'p-5', reasonCode: 'ORIGINAL_TEXT_MISMATCH' }],
    changes: [{ nodeId: 'p-1', originalText: 'Made reports', newText: 'Built SQL reports for sales', applied: true, evidenceType: 'USER_EDITED' }],
  })
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

function dialogs(answer: boolean) {
  return jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => {
    const button = answer ? buttons?.[buttons.length - 1] : buttons?.[0]
    button?.onPress?.()
  })
}

async function renderTailor(props: Partial<Parameters<typeof TailorScreen>[0]> = {}) {
  const client = createTestQueryClient()
  const handlers = { onBack: jest.fn(), onOpenJobs: jest.fn(), onOpenResumes: jest.fn(), onLeaveReasonChange: jest.fn() }
  const ui = (): ReactElement => <TailorScreen {...handlers} {...props} />
  const view = await render(ui(), { wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider> })
  return { ...handlers, rerender: () => view.rerender(ui()) }
}

async function chooseEnhanced(fileName = '简历.docx') {
  const user = userEvent.setup()
  await user.press(await screen.findByRole('radio', { name: 'Data Analyst' }))
  await user.press(await screen.findByRole('radio', { name: fileName }))
  await user.press(screen.getByRole('radio', { name: ENHANCED_MODE }))
  return user
}

/** Scan with no questions, submit, and land on the review. */
async function reachReview(proposal = enhancedProposal()) {
  enhanced.scan.mockResolvedValue(opportunity())
  enhanced.createProposal.mockResolvedValueOnce(proposal)
  const view = await renderTailor()
  const user = await chooseEnhanced()
  await user.press(screen.getByRole('button', { name: '检查可增强内容' }))
  await user.press(await screen.findByRole('button', { name: '提交回答并生成建议' }))
  await screen.findByText('深度增强建议（尚未生成文件）')
  return { ...view, user }
}

beforeEach(() => {
  jest.resetAllMocks()
  jest.restoreAllMocks()
  store.__reset()
  mockAuth.user = { userId: 1 }
  jobs.list.mockResolvedValue([job(9)])
  resumes.list.mockResolvedValue([resume(4, '简历.docx', 'docx'), resume(5, 'scan.pdf', 'pdf')])
  evidence.list.mockResolvedValue(EVIDENCE)
})

it('asks, validates, keeps input on a failed answer, resends only what is unsaved, then previews without a file', async () => {
  enhanced.scan.mockResolvedValue(opportunity(QUESTIONS))
  enhanced.createProposal.mockResolvedValueOnce(enhancedProposal())
  const { onLeaveReasonChange } = await renderTailor()
  const user = await chooseEnhanced()
  await user.press(screen.getByRole('button', { name: '检查可增强内容' }))

  expect(await screen.findByText('你是否做过仪表盘？')).toBeOnTheScreen()
  expect(enhanced.scan).toHaveBeenCalledWith(9, 4)
  expect(tailor.canonicalize).not.toHaveBeenCalled()
  expect(screen.getByText('为什么询问：Build dashboards')).toBeOnTheScreen()

  await user.press(screen.getByRole('radio', { name: '是，我做过' }))
  await user.type(screen.getByLabelText('请说明你具体做了什么'), '做过')
  expect(screen.getByText('请填写真实经历，不要只输入“是”或“做过”。')).toBeOnTheScreen()
  expect(onLeaveReasonChange).toHaveBeenLastCalledWith('unsaved')
  await user.press(screen.getByRole('button', { name: '提交回答并生成建议' }))
  expect(enhanced.answer).not.toHaveBeenCalled()

  await user.clear(screen.getByLabelText('请说明你具体做了什么'))
  await user.type(screen.getByLabelText('请说明你具体做了什么'), 'Built weekly Power BI dashboards')
  await user.press(screen.getByRole('radio', { name: 'Sales dashboard project' }))
  await user.press(screen.getByRole('button', { name: '下一题' }))
  await user.press(screen.getByRole('radio', { name: '没有做过' }))

  enhanced.answer.mockResolvedValueOnce({ questionId: 'q1', answerStatus: 'CONFIRMED_USED', canonicalEntryKey: 'proj-1', skipped: false })
  enhanced.answer.mockRejectedValueOnce(new ApiError('/a', null, null))
  await user.press(screen.getByRole('button', { name: '提交回答并生成建议' }))
  expect(await screen.findByText(/这一题没有保存成功：网络异常，请检查连接。/)).toBeOnTheScreen()
  expect(screen.getByText('你是否写过 SQL？')).toBeOnTheScreen()
  expect(screen.getByRole('radio', { name: '没有做过' })).toBeChecked()
  expect(enhanced.createProposal).not.toHaveBeenCalled()
  expect(enhanced.answer.mock.calls.map((args) => args[2])).toEqual([
    { questionId: 'q1', answerStatus: 'CONFIRMED_USED', answerText: 'Built weekly Power BI dashboards', entryKey: 'proj-1' },
    { questionId: 'q2', answerStatus: 'CONFIRMED_NOT_USED', entryKey: 'exp-1' },
  ])

  await user.press(screen.getByRole('button', { name: '上一题' }))
  expect(screen.getByText('这题已保存')).toBeOnTheScreen()
  expect(screen.getByLabelText('请说明你具体做了什么')).toHaveDisplayValue('Built weekly Power BI dashboards')

  enhanced.answer.mockResolvedValueOnce({ questionId: 'q2', answerStatus: 'CONFIRMED_NOT_USED', canonicalEntryKey: 'exp-1', skipped: false })
  await user.press(screen.getByRole('button', { name: '提交回答并生成建议' }))
  expect(await screen.findByText('深度增强建议（尚未生成文件）')).toBeOnTheScreen()
  expect(enhanced.answer).toHaveBeenCalledTimes(3)
  expect(enhanced.answer.mock.calls[2][2].questionId).toBe('q2')
  expect(enhanced.scan).toHaveBeenCalledTimes(2)
  expect(enhanced.createProposal).toHaveBeenCalledWith(9, 4)
  expect(enhanced.scan.mock.invocationCallOrder[1]).toBeLessThan(enhanced.createProposal.mock.invocationCallOrder[0])

  expect(screen.queryByText('文件已生成')).not.toBeOnTheScreen()
  expect(screen.queryByRole('button', { name: '下载 DOCX 并分享' })).not.toBeOnTheScreen()
  expect(enhanced.confirm).not.toHaveBeenCalled()
  expect(tailor.confirm).not.toHaveBeenCalled()
  const raw = await SecureStore.getItemAsync(ENHANCED_KEY)
  expect(JSON.parse(raw as string)).toEqual({
    productMode: 'ENHANCED', proposalId: 51, jobId: 9, resumeId: 4, tailorResumeId: 4, expiresAt: '2099-01-01T00:00:00', stale: false,
    savedAt: expect.any(String), preparedAt: expect.any(String),
  })
  expect(raw).not.toContain('Power BI')
  expect(await SecureStore.getItemAsync(LEGACY_KEY)).toBeNull()
})

it('confirms only the selected items with the real edit mapping, then downloads and remembers the file', async () => {
  const { user } = await reachReview()
  expect(screen.getByRole('checkbox', { name: 'Analyst at Example' })).toBeChecked()
  expect(screen.getByRole('checkbox', { name: 'Sales dashboard project' })).toBeChecked()
  expect(screen.getByRole('checkbox', { name: '技能更新' })).toBeChecked()
  expect(screen.getByText('更具体', { exact: false })).toBeOnTheScreen()
  expect(await screen.findByText('· Built weekly Power BI dashboards')).toBeOnTheScreen()

  await user.press(screen.getByRole('checkbox', { name: 'Sales dashboard project' }))
  await user.press(screen.getAllByRole('button', { name: '编辑建议' })[0])
  fireEvent.changeText(screen.getByLabelText('编辑「Analyst at Example」'), 'Built SQL reports for sales')
  await user.press(screen.getByRole('button', { name: '保存编辑' }))
  await user.press(screen.getByRole('button', { name: '确认并生成简历' }))
  expect(await screen.findByText('编辑后的每一行都不能为空。')).toBeOnTheScreen()
  expect(enhanced.confirm).not.toHaveBeenCalled()

  await user.press(screen.getAllByRole('button', { name: '编辑建议' })[0])
  fireEvent.changeText(screen.getByLabelText('编辑「Analyst at Example」'), 'Built SQL reports for sales\nBuilt Excel dashboards')
  expect(screen.getByRole('button', { name: '确认并生成简历' })).toBeDisabled()
  await user.press(screen.getByRole('button', { name: '保存编辑' }))
  expect(screen.getByText('已由你编辑')).toBeOnTheScreen()

  const pending = deferred<TailoredResumeResponse>()
  enhanced.confirm.mockReturnValueOnce(pending.promise)
  await user.press(screen.getByRole('button', { name: '确认并生成简历' }))
  await user.press(await screen.findByRole('button', { name: '正在生成文件…' }))
  expect(enhanced.confirm).toHaveBeenCalledTimes(1)
  const [jobId, proposalId, body] = enhanced.confirm.mock.calls[0]
  expect([jobId, proposalId]).toEqual([9, 51])
  expect(body).toEqual({
    selectedEntryKeys: ['exp-1'],
    editedEntries: [
      { canonicalEntryKey: 'exp-1', nodes: [{ nodeId: 'p-1', newText: 'Built SQL reports for sales' }, { nodeId: 'p-2', newText: 'Built Excel dashboards' }] },
    ],
    selectedSkillReviewIds: ['skills:technical'],
  })
  expect(tailor.confirm).not.toHaveBeenCalled()

  pending.resolve(confirmedResult())
  expect(await screen.findByText('文件已生成')).toBeOnTheScreen()
  expect(screen.getByText('写入 3 条')).toBeOnTheScreen()
  expect(screen.getByText('未采用 1 条')).toBeOnTheScreen()
  expect(screen.getByText('· 原文不匹配')).toBeOnTheScreen()
  await user.press(screen.getByRole('button', { name: '下载 DOCX 并分享' }))
  expect(share).toHaveBeenCalledWith({ path: `/jobs/9/tailored/${UUID}.docx`, fileType: 'docx' }, '简历 - Data Analyst.docx')
  expect(await SecureStore.getItemAsync(ENHANCED_KEY)).toBeNull()
  expect(JSON.parse((await SecureStore.getItemAsync('aijob.tailorResult.1.9.ENHANCED')) as string)).toMatchObject({
    mode: 'ENHANCED', downloadUrl: `/api/v1/jobs/9/tailored/${UUID}.docx`, pdfDownloadUrl: `/api/v1/jobs/9/tailored/${UUID}.pdf`,
  })

  await user.press(screen.getByRole('button', { name: '重新选择' }))
  expect(await screen.findByText('本机保存的上次结果')).toBeOnTheScreen()
})

it('disables confirmation when nothing is selected', async () => {
  const { user } = await reachReview()
  await user.press(screen.getByRole('checkbox', { name: 'Analyst at Example' }))
  await user.press(screen.getByRole('checkbox', { name: 'Sales dashboard project' }))
  await user.press(screen.getByRole('checkbox', { name: '技能更新' }))
  expect(screen.getByRole('button', { name: '确认并生成简历' })).toBeDisabled()
})

it('keeps input when an evidence correction fails, and blocks the old suggestion once it succeeds', async () => {
  const { user } = await reachReview()
  dialogs(true)
  await user.press(await screen.findByRole('button', { name: '更正' }))
  const field = screen.getByLabelText('更正你做过的内容')
  expect(field).toHaveDisplayValue('Built weekly Power BI dashboards')
  fireEvent.changeText(field, 'Built weekly Power BI dashboards for sales')

  evidence.save.mockRejectedValueOnce(new ApiError('/e', 400, { message: 'EVIDENCE_ENTRY_MISMATCH' }))
  await user.press(screen.getByRole('button', { name: '保存更正' }))
  expect(await screen.findByText('补充信息没有更新：补充信息没有通过服务器校验，请修改后重试。')).toBeOnTheScreen()
  expect(screen.getByLabelText('更正你做过的内容')).toHaveDisplayValue('Built weekly Power BI dashboards for sales')
  expect(screen.getByRole('button', { name: '确认并生成简历' })).toBeEnabled()

  evidence.save.mockResolvedValueOnce(EVIDENCE[0])
  await user.press(screen.getByRole('button', { name: '保存更正' }))
  expect(await screen.findByText(/补充信息已经修改/)).toBeOnTheScreen()
  expect(evidence.save).toHaveBeenLastCalledWith(4, {
    topic: 'dashboards', category: 'HARD_SKILL', answerStatus: 'CONFIRMED_USED', entryKey: 'exp-1', relatedNodeIds: [],
    context: '', action: 'Built weekly Power BI dashboards for sales', result: '', metric: '', confirmed: true,
  })
  expect(screen.queryByRole('button', { name: '确认并生成简历' })).not.toBeOnTheScreen()
  expect(screen.getByRole('button', { name: '重新检查并生成建议' })).toBeOnTheScreen()
  expect(JSON.parse((await SecureStore.getItemAsync(ENHANCED_KEY)) as string).stale).toBe(true)
  expect(enhanced.confirm).not.toHaveBeenCalled()
})

it('blocks the old suggestion after deleting evidence', async () => {
  const { user } = await reachReview()
  dialogs(true)
  evidence.remove.mockResolvedValueOnce(undefined)
  await user.press(await screen.findByRole('button', { name: '删除' }))
  expect(await screen.findByText(/补充信息已经修改/)).toBeOnTheScreen()
  expect(evidence.remove).toHaveBeenCalledWith(4, 81)
  expect(screen.queryByRole('button', { name: '确认并生成简历' })).not.toBeOnTheScreen()
})

it('restores each product mode from its own record, keeps a stale flag, and never re-prepares the DOCX', async () => {
  await SecureStore.setItemAsync(LEGACY_KEY, JSON.stringify({ proposalId: 31, jobId: 9, resumeId: 4, expiresAt: '2099-01-01T00:00:00' }))
  await SecureStore.setItemAsync(
    ENHANCED_KEY,
    JSON.stringify({ productMode: 'ENHANCED', proposalId: 51, jobId: 9, resumeId: 5, tailorResumeId: 77, expiresAt: '2099-01-01T00:00:00', stale: true }),
  )
  tailor.proposal.mockImplementation(async (_jobId, proposalId) =>
    proposalId === 51
      ? enhancedProposal({ resumeId: 77 })
      : { ...enhancedProposal(), productMode: null, mode: 'AGGRESSIVE', proposalId: 31, entryReviews: null, skillReviews: null,
          changes: [{ nodeId: 'p-9', originalText: 'Old', newText: 'Aggressive text', evidenceType: 'VERIFIED' }] },
  )
  enhanced.scan.mockResolvedValue(opportunity(QUESTIONS))
  await renderTailor({ initialJobId: 9 })

  expect(await screen.findByText('深度增强建议（尚未生成文件）')).toBeOnTheScreen()
  expect(screen.getByText('已恢复上次还没有确认的建议。')).toBeOnTheScreen()
  expect(screen.getByText(/补充信息已经修改/)).toBeOnTheScreen()
  expect(tailor.proposal).toHaveBeenCalledWith(9, 51)
  expect(screen.queryByRole('button', { name: '确认并生成简历' })).not.toBeOnTheScreen()
  expect(screen.getByRole('radio', { name: 'scan.pdf' })).toBeChecked()

  const user = userEvent.setup()
  await user.press(screen.getByRole('button', { name: '重新检查并生成建议' }))
  expect(await screen.findByText('你是否做过仪表盘？')).toBeOnTheScreen()
  expect(enhanced.scan).toHaveBeenLastCalledWith(9, 77)
  expect(tailor.structure).not.toHaveBeenCalled()
  expect(tailor.canonicalize).not.toHaveBeenCalled()
  expect(await SecureStore.getItemAsync(ENHANCED_KEY)).toBeNull()
  expect(JSON.parse((await SecureStore.getItemAsync(LEGACY_KEY)) as string).proposalId).toBe(31)

  dialogs(true)
  await user.press(screen.getByRole('button', { name: '放弃这份建议' }))
  await user.press(await screen.findByRole('radio', { name: '增强建议（确认后生成）' }))
  expect(await screen.findByText('增强建议（尚未生成文件）')).toBeOnTheScreen()
  expect(tailor.proposal).toHaveBeenLastCalledWith(9, 31)
  expect(screen.getByText('Aggressive text')).toBeOnTheScreen()
})

it('blocks a restored PDF proposal made before the recognized content was saved, and prepares the DOCX again to regenerate', async () => {
  await SecureStore.setItemAsync(
    ENHANCED_KEY,
    JSON.stringify({
      productMode: 'ENHANCED', proposalId: 51, jobId: 9, resumeId: 5, tailorResumeId: 77, expiresAt: '2099-01-01T00:00:00', stale: false,
      savedAt: '2026-09-14T08:00:00.000Z', preparedAt: '2026-09-14T07:59:00.000Z',
    }),
  )
  await SecureStore.setItemAsync('aijob.structureSavedAt.1.5', '2026-09-14T09:00:00.000Z')
  tailor.proposal.mockResolvedValue(enhancedProposal({ resumeId: 77 }))
  enhanced.scan.mockResolvedValue(opportunity(QUESTIONS))
  tailor.structure.mockResolvedValue({ resumeId: 5 })
  tailor.canonicalize.mockResolvedValue(resume(8, 'scan.docx', 'docx'))
  await renderTailor({ initialJobId: 9 })

  expect(await screen.findByText(/识别结果已经修改/)).toBeOnTheScreen()
  expect(screen.queryByRole('button', { name: '确认并生成简历' })).not.toBeOnTheScreen()
  expect(tailor.canonicalize).not.toHaveBeenCalled()
  expect(enhanced.scan).not.toHaveBeenCalled()

  await userEvent.setup().press(screen.getByRole('button', { name: '重新检查并生成建议' }))
  expect(await screen.findByText('你是否做过仪表盘？')).toBeOnTheScreen()
  expect(tailor.canonicalize).toHaveBeenCalledWith(5)
  expect(enhanced.scan).toHaveBeenLastCalledWith(9, 8)
  expect(enhanced.confirm).not.toHaveBeenCalled()
})

it('never opens an ENHANCED proposal from an older AGGRESSIVE record', async () => {
  await SecureStore.setItemAsync(LEGACY_KEY, JSON.stringify({ proposalId: 51, jobId: 9, resumeId: 4, expiresAt: null }))
  tailor.proposal.mockResolvedValue(enhancedProposal())
  await renderTailor({ initialJobId: 9 })
  expect(await screen.findByText('这份建议已不可用：可能已被确认，或已经过期。')).toBeOnTheScreen()
  expect(screen.queryByText('增强建议（尚未生成文件）')).not.toBeOnTheScreen()
  expect(screen.queryByText('深度增强建议（尚未生成文件）')).not.toBeOnTheScreen()
  expect(await SecureStore.getItemAsync(LEGACY_KEY)).toBeNull()
})

it('checks a lost confirmation with the proposal status and reports 404 without guessing', async () => {
  const { user } = await reachReview()
  enhanced.confirm.mockRejectedValueOnce(new ApiError('/c', null, null))
  await user.press(screen.getByRole('button', { name: '确认并生成简历' }))
  expect(await screen.findByText(/暂时无法确认文件是否已经生成/)).toBeOnTheScreen()
  expect(screen.queryByRole('button', { name: '确认并生成简历' })).not.toBeOnTheScreen()

  tailor.proposal.mockResolvedValueOnce(enhancedProposal({ status: 'CONFIRMING' }))
  await user.press(screen.getByRole('button', { name: '查询确认结果' }))
  expect(await screen.findByText('服务器还在确认这份建议，请稍后再查询。')).toBeOnTheScreen()

  tailor.proposal.mockResolvedValueOnce(enhancedProposal())
  await user.press(screen.getByRole('button', { name: '查询确认结果' }))
  expect(await screen.findByText('上次确认没有完成，这份建议仍然可以确认。')).toBeOnTheScreen()
  expect(screen.getByRole('button', { name: '确认并生成简历' })).toBeEnabled()

  enhanced.confirm.mockRejectedValueOnce(new ApiError('/c', 409, { message: 'Tailor proposal is already being confirmed' }))
  await user.press(screen.getByRole('button', { name: '确认并生成简历' }))
  await screen.findByText('服务器还在确认这份建议，请稍后再查询。')
  tailor.proposal.mockRejectedValueOnce(new ApiError('/p', 404, { message: 'Tailor proposal not found' }))
  await user.press(screen.getByRole('button', { name: '查询确认结果' }))
  expect(await screen.findByText(/可能已被确认、已过期或不存在/)).toBeOnTheScreen()
  expect(screen.queryByText('按服务器给出的有效期，这份建议已经过期。')).not.toBeOnTheScreen()
  expect(enhanced.confirm).toHaveBeenCalledTimes(2)
  expect(await SecureStore.getItemAsync(ENHANCED_KEY)).toBeNull()
})

it('does not create another proposal by itself when the outcome is unknown', async () => {
  enhanced.scan.mockResolvedValue(opportunity())
  enhanced.createProposal.mockRejectedValueOnce(new ApiError('/p', null, null))
  await renderTailor()
  const user = await chooseEnhanced()
  await user.press(screen.getByRole('button', { name: '检查可增强内容' }))
  await user.press(await screen.findByRole('button', { name: '提交回答并生成建议' }))
  expect(await screen.findByText(/暂时无法确认建议是否已经生成/)).toBeOnTheScreen()
  expect(enhanced.createProposal).toHaveBeenCalledTimes(1)

  dialogs(false)
  await user.press(screen.getByRole('button', { name: '重新生成建议' }))
  expect(enhanced.createProposal).toHaveBeenCalledTimes(1)

  jest.restoreAllMocks()
  dialogs(true)
  enhanced.createProposal.mockResolvedValueOnce(enhancedProposal())
  await user.press(screen.getByRole('button', { name: '重新生成建议' }))
  expect(await screen.findByText('深度增强建议（尚未生成文件）')).toBeOnTheScreen()
  expect(enhanced.createProposal).toHaveBeenCalledTimes(2)
  expect(enhanced.answer).not.toHaveBeenCalled()
})

it('offers a download saved on this device after a conservative run, through the tailored route', async () => {
  tailor.run.mockResolvedValueOnce({
    ...confirmedResult(), mode: 'CONSERVATIVE', productMode: null, proposalId: null, status: 'APPLIED', entryReviews: null, skillReviews: null,
  })
  await renderTailor()
  const user = userEvent.setup()
  await user.press(await screen.findByRole('radio', { name: 'Data Analyst' }))
  await user.press(await screen.findByRole('radio', { name: '简历.docx' }))
  await user.press(screen.getByRole('button', { name: '开始改写' }))
  await screen.findByText('文件已生成')
  expect(JSON.parse((await SecureStore.getItemAsync('aijob.tailorResult.1.9.CONSERVATIVE')) as string)).toMatchObject({
    mode: 'CONSERVATIVE', jobId: 9, resumeId: 4, downloadUrl: `/api/v1/jobs/9/tailored/${UUID}.docx`,
  })

  await user.press(screen.getByRole('button', { name: '重新选择' }))
  expect(await screen.findByText('本机保存的上次结果')).toBeOnTheScreen()
  expect(screen.getByText(/不是服务器的改写历史/)).toBeOnTheScreen()
  await user.press(screen.getAllByRole('button', { name: '下载 DOCX 并分享' })[0])
  expect(share).toHaveBeenCalledWith({ path: `/jobs/9/tailored/${UUID}.docx`, fileType: 'docx' }, '简历 - Data Analyst.docx')
})

it('drops an ENHANCED response that arrives after another account signed in', async () => {
  enhanced.scan.mockResolvedValue(opportunity())
  const pending = deferred<TailoredResumeResponse>()
  enhanced.createProposal.mockReturnValueOnce(pending.promise)
  const { rerender } = await renderTailor()
  const user = await chooseEnhanced()
  await user.press(screen.getByRole('button', { name: '检查可增强内容' }))
  await user.press(await screen.findByRole('button', { name: '提交回答并生成建议' }))
  await screen.findByRole('button', { name: '正在生成深度增强建议，可能需要一两分钟…' })

  mockAuth.user = { userId: 2 }
  await rerender()
  pending.resolve(enhancedProposal())
  await new Promise((resolve) => setTimeout(resolve, 0))
  await waitFor(() => expect(enhanced.createProposal).toHaveBeenCalledTimes(1))

  expect(screen.queryByText('深度增强建议（尚未生成文件）')).not.toBeOnTheScreen()
  expect(await SecureStore.getItemAsync(ENHANCED_KEY)).toBeNull()
  expect(await SecureStore.getItemAsync('aijob.tailor.2.9.ENHANCED')).toBeNull()
  expect(evidence.list).not.toHaveBeenCalled()
})
