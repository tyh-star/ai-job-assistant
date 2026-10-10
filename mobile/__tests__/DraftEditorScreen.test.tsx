import '../src/i18n'
import type { ReactElement } from 'react'
import { Alert } from 'react-native'
import * as SecureStore from 'expo-secure-store'
import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, userEvent, waitFor } from '@testing-library/react-native'
import { ApiError } from '../src/api/client'
import { draftsApi } from '../src/api/endpoints'
import { resumeKeys } from '../src/resumes/queries'
import { downloadAndShare } from '../src/resumes/files'
import { DraftEditorScreen } from '../src/screens/DraftEditorScreen'
import { builderDraft, generatedResume, readyDraft } from '../test-utils/builderFixtures'
import { createTestQueryClient } from '../test-utils/render'

jest.mock('../src/api/endpoints', () => ({
  draftsApi: { get: jest.fn(), update: jest.fn(), generate: jest.fn(), list: jest.fn() },
  resumesApi: { list: jest.fn() },
  jobsApi: {},
  catalogApi: {},
  authApi: {},
}))

// Plain functions, so jest.resetAllMocks() between tests cannot erase the store's behaviour.
jest.mock('expo-secure-store', () => {
  const mockValues = new Map<string, string>()
  return {
    getItemAsync: async (key: string) => mockValues.get(key) ?? null,
    setItemAsync: async (key: string, value: string) => void mockValues.set(key, value),
    deleteItemAsync: async (key: string) => void mockValues.delete(key),
    __reset: () => mockValues.clear(),
  }
})

jest.mock('../src/resumes/files', () => ({
  ...jest.requireActual('../src/resumes/files'),
  downloadAndShare: jest.fn(async () => ({ name: 'x.docx', uri: 'file:///x.docx' })),
}))

const mockAuth = { user: { userId: 1, name: 'nb', email: 'nb@example.test', role: 'USER' } as { userId: number } | null }
jest.mock('../src/auth/AuthContext', () => ({ useAuth: () => mockAuth }))

const drafts = draftsApi as jest.Mocked<typeof draftsApi>
const share = downloadAndShare as jest.MockedFunction<typeof downloadAndShare>

function deferred<T>() {
  let resolve: (value: T) => void = () => {}
  let reject: (error: unknown) => void = () => {}
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

async function renderEditor(props: Partial<Parameters<typeof DraftEditorScreen>[0]> = {}, client = createTestQueryClient()) {
  const handlers = { onBack: jest.fn(), onOpenFile: jest.fn(), onOpenFiles: jest.fn(), onLeaveReasonChange: jest.fn() }
  const ui = (): ReactElement => <DraftEditorScreen draftId={7} {...handlers} {...props} />
  const view = await render(ui(), {
    wrapper: ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>,
  })
  return { ...handlers, client, rerender: () => view.rerender(ui()) }
}

const store = SecureStore as unknown as { __reset: () => void }
const PENDING_KEY = 'aijob.generate.1.7'

function confirmDialogs() {
  return jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => buttons?.[buttons.length - 1]?.onPress?.())
}

beforeEach(() => {
  jest.resetAllMocks()
  jest.restoreAllMocks()
  store.__reset()
  mockAuth.user = { userId: 1 } as { userId: number }
  drafts.list.mockResolvedValue([])
})

describe('loading', () => {
  it('shows loading, then a failure with retry that is never worded as "nothing here"', async () => {
    const pending = deferred<never>()
    drafts.get.mockReturnValueOnce(pending.promise)
    await renderEditor()
    expect(screen.getByLabelText('加载中…')).toBeOnTheScreen()

    pending.reject(new ApiError('/resume-builder/drafts/7', 502, null))
    expect(await screen.findByText('生成的简历暂时无法加载')).toBeOnTheScreen()
    expect(screen.queryByText('还没有生成的简历')).not.toBeOnTheScreen()

    drafts.get.mockResolvedValueOnce(builderDraft(7))
    await userEvent.setup().press(screen.getByRole('button', { name: '重试' }))
    expect(await screen.findByLabelText('姓名')).toBeOnTheScreen()
  })

  it('says when the resume no longer exists', async () => {
    drafts.get.mockRejectedValue(new ApiError('/resume-builder/drafts/7', 404, { message: 'Draft not found' }))
    await renderEditor()
    expect(await screen.findByText('找不到这份生成的简历')).toBeOnTheScreen()
  })
})

describe('saving', () => {
  it('saves only on request, shows "saved" only after success, and reopens with the saved values', async () => {
    drafts.get.mockResolvedValue(builderDraft(7))
    const { onLeaveReasonChange, client } = await renderEditor()
    const user = userEvent.setup()
    await screen.findByLabelText('姓名')
    expect(screen.queryByText('已保存')).not.toBeOnTheScreen()
    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled()

    await user.type(screen.getByLabelText('姓名'), 'Test Person')
    await user.type(screen.getByLabelText('目标职位'), 'Junior Analyst')
    expect(screen.getByText('有未保存的修改')).toBeOnTheScreen()
    expect(onLeaveReasonChange).toHaveBeenLastCalledWith('unsaved')
    expect(drafts.update).not.toHaveBeenCalled()

    const saved = builderDraft(
      7,
      { version: 4, targetRole: 'Junior Analyst' },
      { basicInfo: { fullName: 'Test Person' }, jobTarget: { targetTitle: 'Junior Analyst' } },
    )
    drafts.update.mockResolvedValueOnce(saved)
    await user.press(screen.getByRole('button', { name: '保存' }))
    expect(await screen.findByText('已保存')).toBeOnTheScreen()
    const [id, body] = drafts.update.mock.calls[0]
    expect(id).toBe(7)
    expect(body).toMatchObject({ title: 'My resume', version: 3, targetRole: 'Junior Analyst', templateType: 'GENERIC_ATS', locale: 'zh-CN' })
    expect(body.content.basicInfo.fullName).toBe('Test Person')
    expect(body.content.jobTarget.targetTitle).toBe('Junior Analyst')
    expect(onLeaveReasonChange).toHaveBeenLastCalledWith(null)
    expect(client.getQueryData(resumeKeys.draft(1, 7))).toEqual(saved)

    // A later save uses the version the server returned.
    await user.type(screen.getByLabelText('城市'), 'Toronto')
    drafts.update.mockResolvedValueOnce({ ...saved, version: 5 })
    await user.press(screen.getByRole('button', { name: '保存' }))
    await waitFor(() => expect(drafts.update).toHaveBeenCalledTimes(2))
    expect(drafts.update.mock.calls[1][1].version).toBe(4)
  })

  it('reopens with what the server saved, including sections added on the website', async () => {
    drafts.get.mockResolvedValue(
      builderDraft(
        7,
        { version: 4, targetRole: 'Junior Analyst' },
        {
          basicInfo: { fullName: 'Test Person', phone: '555-0100' },
          jobTarget: { targetTitle: 'Junior Analyst' },
          languages: [{ language: 'English', proficiency: 'Fluent' }],
        },
      ),
    )
    await renderEditor()
    expect(await screen.findByDisplayValue('Test Person')).toBeOnTheScreen()
    expect(screen.getByDisplayValue('555-0100')).toBeOnTheScreen()
    expect(screen.getByDisplayValue('Junior Analyst')).toBeOnTheScreen()
    expect(screen.getByText('1 条')).toBeOnTheScreen()
    await userEvent.setup().press(screen.getByRole('button', { name: '语言能力' }))
    expect(screen.getByDisplayValue('Fluent')).toBeOnTheScreen()
    expect(screen.queryByText('有未保存的修改')).not.toBeOnTheScreen()
  })

  it('keeps the input after a failed save and lets the user retry', async () => {
    drafts.get.mockResolvedValue(builderDraft(7))
    await renderEditor()
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('姓名'), 'Test Person')

    drafts.update.mockRejectedValueOnce(new ApiError('/resume-builder/drafts/7', 502, null))
    await user.press(screen.getByRole('button', { name: '保存' }))
    expect(await screen.findByText(/保存没有成功：服务暂时不可用，请稍后再试。/)).toBeOnTheScreen()
    expect(screen.getByText('保存失败')).toBeOnTheScreen()
    expect(screen.queryByText('已保存')).not.toBeOnTheScreen()
    expect(screen.getByLabelText('姓名')).toHaveDisplayValue('Test Person')

    drafts.update.mockResolvedValueOnce(builderDraft(7, { version: 4 }, { basicInfo: { fullName: 'Test Person' } }))
    await user.press(screen.getByRole('button', { name: '保存' }))
    expect(await screen.findByText('已保存')).toBeOnTheScreen()
  })

  it('stops on a version conflict without overwriting, and blocks generating', async () => {
    drafts.get.mockResolvedValue(readyDraft(7))
    await renderEditor()
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('城市'), 'Toronto')

    drafts.update.mockRejectedValueOnce(
      new ApiError('/resume-builder/drafts/7', 409, { message: 'This draft changed elsewhere since it was last loaded. Reload before saving.' }),
    )
    await user.press(screen.getByRole('button', { name: '保存' }))
    expect(await screen.findByText(/已在网站或其他设备上更新/)).toBeOnTheScreen()
    expect(screen.getByLabelText('城市')).toHaveDisplayValue('Toronto')
    expect(screen.getByRole('button', { name: '保存' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '生成简历' })).toBeDisabled()
    expect(drafts.update).toHaveBeenCalledTimes(1)
  })

  it('checks the backend limits before sending', async () => {
    drafts.get.mockResolvedValue(builderDraft(7))
    await renderEditor()
    const user = userEvent.setup()
    await screen.findByLabelText('姓名')
    await user.press(screen.getByRole('button', { name: '添加专业链接' }))
    await user.type(screen.getByLabelText('链接'), 'example.com')
    await user.press(screen.getByRole('button', { name: '保存' }))

    expect(await screen.findByText('链接需要以 http:// 或 https:// 开头')).toBeOnTheScreen()
    expect(screen.getByText('有 1 处内容需要修改后才能保存。')).toBeOnTheScreen()
    expect(drafts.update).not.toHaveBeenCalled()
    expect(screen.getByLabelText('链接')).toHaveDisplayValue('example.com')
  })

  it('asks before removing an entry that has content', async () => {
    const alert = jest.spyOn(Alert, 'alert').mockImplementation((_title, _message, buttons) => buttons?.[0]?.onPress?.())
    drafts.get.mockResolvedValue(builderDraft(7))
    await renderEditor()
    const user = userEvent.setup()
    await screen.findByLabelText('姓名')
    await user.press(screen.getByRole('button', { name: '语言能力' }))
    await user.press(screen.getByRole('button', { name: '添加一条' }))
    await user.type(screen.getByLabelText('熟练程度'), 'Fluent')
    await user.press(screen.getByRole('button', { name: '删除' }))
    expect(alert).toHaveBeenCalledWith('删除这一条？', '保存后才会从简历中删除。', expect.any(Array), expect.anything())
    expect(screen.getByLabelText('熟练程度')).toHaveDisplayValue('Fluent')
  })
})

describe('generating', () => {
  it('generates once, shows the file, refreshes the resume list and shares through the existing flow', async () => {
    drafts.get.mockResolvedValue(readyDraft(7))
    const client = createTestQueryClient()
    client.setQueryData(resumeKeys.files(1), [])
    const { onOpenFile } = await renderEditor({}, client)
    const user = userEvent.setup()
    await screen.findByLabelText('姓名')

    const created = generatedResume(40, '测试简历.docx')
    const pending = deferred<typeof created>()
    drafts.generate.mockReturnValueOnce(pending.promise)
    await user.press(screen.getByRole('button', { name: '生成简历' }))
    expect(await screen.findByText('正在生成简历，请稍候…')).toBeOnTheScreen()
    const busy = screen.getByRole('button', { name: '正在生成…' })
    expect(busy).toBeDisabled()
    await user.press(busy)
    expect(drafts.generate).toHaveBeenCalledTimes(1)
    expect(drafts.update).not.toHaveBeenCalled()
    expect(drafts.generate.mock.calls[0]).toEqual([7, { idempotencyKey: expect.stringMatching(/^[0-9a-f-]{36}$/) }])

    pending.resolve(created)
    expect(await screen.findByText('简历已生成：测试简历.docx')).toBeOnTheScreen()
    await waitFor(() => expect(client.getQueryData(resumeKeys.files(1))).toEqual([created]))

    await user.press(screen.getByRole('button', { name: '下载并分享' }))
    expect(share).toHaveBeenCalledWith(created)
    await user.press(screen.getByRole('button', { name: '查看文件' }))
    expect(onOpenFile).toHaveBeenCalledWith(40)
  })

  it('saves unsaved edits first, and does not generate when that save fails', async () => {
    drafts.get.mockResolvedValue(readyDraft(7))
    await renderEditor()
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('城市'), 'Toronto')

    drafts.update.mockRejectedValueOnce(new ApiError('/resume-builder/drafts/7', 502, null))
    await user.press(screen.getByRole('button', { name: '生成简历' }))
    expect(await screen.findByText(/保存没有成功/)).toBeOnTheScreen()
    expect(drafts.generate).not.toHaveBeenCalled()

    drafts.update.mockResolvedValueOnce(readyDraft(7, { version: 4 }))
    drafts.generate.mockResolvedValueOnce(generatedResume(41, 'Test Person.docx'))
    await user.press(screen.getByRole('button', { name: '生成简历' }))
    expect(await screen.findByText('简历已生成：Test Person.docx')).toBeOnTheScreen()
    expect(drafts.update.mock.invocationCallOrder[1]).toBeLessThan(drafts.generate.mock.invocationCallOrder[0])
  })

  it('is unavailable until a name, a contact and a target title are filled in', async () => {
    drafts.get.mockResolvedValue(builderDraft(7))
    await renderEditor()
    expect(await screen.findByText('生成前还需要填写：姓名、联系方式（邮箱或电话）、目标职位')).toBeOnTheScreen()
    expect(screen.getByRole('button', { name: '生成简历' })).toBeDisabled()
  })

  it('reports a definite failure, and a retry is a new request', async () => {
    drafts.get.mockResolvedValue(readyDraft(7))
    await renderEditor()
    const user = userEvent.setup()
    await screen.findByLabelText('姓名')

    drafts.generate.mockRejectedValueOnce(
      new ApiError('/resume-builder/drafts/7/generate', 400, { message: 'Add at least a name or a way to contact you before generating' }),
    )
    await user.press(screen.getByRole('button', { name: '生成简历' }))
    expect(await screen.findByText('生成没有成功：生成前请至少填写姓名或一种联系方式。')).toBeOnTheScreen()

    drafts.generate.mockRejectedValueOnce(new ApiError('/resume-builder/drafts/7/generate', 404, { message: 'Draft not found' }))
    await user.press(screen.getByRole('button', { name: '生成简历' }))
    expect(await screen.findByText('生成没有成功：这份简历已不存在，可能已被删除。')).toBeOnTheScreen()
    const [first, second] = drafts.generate.mock.calls.map((args) => args[1].idempotencyKey)
    expect(second).not.toBe(first)
    expect(await SecureStore.getItemAsync(PENDING_KEY)).toBeNull()
  })

  it('treats a 500 as possibly generated: keeps the key and stores only request identifiers', async () => {
    drafts.get.mockResolvedValue(readyDraft(7))
    await renderEditor()
    const user = userEvent.setup()
    await screen.findByLabelText('姓名')

    drafts.generate.mockRejectedValueOnce(new ApiError('/resume-builder/drafts/7/generate', 500, { message: 'Internal server error' }))
    await user.press(screen.getByRole('button', { name: '生成简历' }))
    expect(await screen.findByText('暂时无法确认简历是否已经生成（服务器返回了错误。）。')).toBeOnTheScreen()
    expect(screen.queryByText(/生成没有成功/)).not.toBeOnTheScreen()

    const key = drafts.generate.mock.calls[0][1].idempotencyKey
    const raw = await SecureStore.getItemAsync(PENDING_KEY)
    expect(JSON.parse(raw as string)).toEqual({ idempotencyKey: key, draftVersion: 3 })
    expect(raw).not.toContain('Test Person')

    drafts.generate.mockResolvedValueOnce(generatedResume(44, 'Test Person.docx'))
    await user.press(screen.getByRole('button', { name: '确认上一次生成' }))
    expect(await screen.findByText('简历已生成：Test Person.docx')).toBeOnTheScreen()
    expect(drafts.generate.mock.calls[1][1].idempotencyKey).toBe(key)
    expect(await SecureStore.getItemAsync(PENDING_KEY)).toBeNull()
  })

  it('offers to confirm an unresolved request after re-entering, with the original key', async () => {
    await SecureStore.setItemAsync(PENDING_KEY, JSON.stringify({ idempotencyKey: 'earlier-key', draftVersion: 3 }))
    drafts.get.mockResolvedValue(readyDraft(7))
    await renderEditor()
    expect(await screen.findByText('上一次生成请求的结果还没有确认。')).toBeOnTheScreen()
    expect(screen.getByText(/沿用同一个请求/)).toBeOnTheScreen()

    drafts.generate.mockResolvedValueOnce(generatedResume(45, 'Test Person.docx'))
    await userEvent.setup().press(screen.getByRole('button', { name: '确认上一次生成' }))
    expect(await screen.findByText('简历已生成：Test Person.docx')).toBeOnTheScreen()
    expect(drafts.generate).toHaveBeenCalledTimes(1)
    expect(drafts.generate).toHaveBeenCalledWith(7, { idempotencyKey: 'earlier-key' })
    expect(await SecureStore.getItemAsync(PENDING_KEY)).toBeNull()
  })

  it('never confirms with the old key once the draft changed elsewhere; a new version needs a new key', async () => {
    await SecureStore.setItemAsync(PENDING_KEY, JSON.stringify({ idempotencyKey: 'earlier-key', draftVersion: 3 }))
    drafts.get.mockResolvedValueOnce(readyDraft(7)).mockResolvedValueOnce(readyDraft(7, { version: 5 }))
    await renderEditor()
    const user = userEvent.setup()
    await screen.findByText('上一次生成请求的结果还没有确认。')

    await user.press(screen.getByRole('button', { name: '确认上一次生成' }))
    expect(await screen.findByText(/不能再按原请求确认/)).toBeOnTheScreen()
    expect(drafts.generate).not.toHaveBeenCalled()

    const alert = confirmDialogs()
    drafts.generate.mockResolvedValueOnce(generatedResume(46, 'Test Person.docx'))
    await user.press(screen.getByRole('button', { name: '生成新版本' }))
    expect(alert).toHaveBeenCalledWith('生成新版本？', expect.stringContaining('多出一份'), expect.any(Array), expect.anything())
    await waitFor(() => expect(drafts.generate).toHaveBeenCalledTimes(1))
    expect(drafts.generate.mock.calls[0][1].idempotencyKey).not.toBe('earlier-key')
  })

  it('asks for a new version when the content was edited after an unconfirmed request', async () => {
    drafts.get.mockResolvedValue(readyDraft(7))
    await renderEditor()
    const user = userEvent.setup()
    await screen.findByLabelText('姓名')

    drafts.generate.mockRejectedValueOnce(new ApiError('/resume-builder/drafts/7/generate', null, null))
    await user.press(screen.getByRole('button', { name: '生成简历' }))
    await screen.findByText(/暂时无法确认/)
    const firstKey = drafts.generate.mock.calls[0][1].idempotencyKey

    await user.type(screen.getByLabelText('城市'), 'Toronto')
    expect(screen.queryByRole('button', { name: '确认上一次生成' })).not.toBeOnTheScreen()
    expect(screen.getByText(/不能再按原请求确认/)).toBeOnTheScreen()

    confirmDialogs()
    drafts.update.mockResolvedValueOnce(
      builderDraft(7, { version: 4, targetRole: 'Junior Analyst' }, {
        basicInfo: { fullName: 'Test Person', email: 'person@example.test', city: 'Toronto' },
        jobTarget: { targetTitle: 'Junior Analyst' },
      }),
    )
    drafts.generate.mockResolvedValueOnce(generatedResume(47, 'Test Person.docx'))
    await user.press(screen.getByRole('button', { name: '生成新版本' }))
    expect(await screen.findByText('简历已生成：Test Person.docx')).toBeOnTheScreen()
    expect(drafts.update).toHaveBeenCalledTimes(1)
    expect(drafts.generate.mock.calls[1][1].idempotencyKey).not.toBe(firstKey)
    expect(JSON.parse((await SecureStore.getItemAsync(PENDING_KEY)) ?? 'null')).toBeNull()
  })

  it('does not present an earlier file as the result of later edits', async () => {
    drafts.get.mockResolvedValue(readyDraft(7))
    await renderEditor()
    const user = userEvent.setup()
    await screen.findByLabelText('姓名')

    drafts.generate.mockResolvedValueOnce(generatedResume(48, 'First.docx'))
    await user.press(screen.getByRole('button', { name: '生成简历' }))
    expect(await screen.findByText('简历已生成：First.docx')).toBeOnTheScreen()

    await user.type(screen.getByLabelText('城市'), 'Toronto')
    expect(screen.getByText('修改前生成的文件：First.docx')).toBeOnTheScreen()
    expect(screen.queryByText('简历已生成：First.docx')).not.toBeOnTheScreen()

    drafts.update.mockResolvedValueOnce(
      builderDraft(7, { version: 4, targetRole: 'Junior Analyst' }, {
        basicInfo: { fullName: 'Test Person', email: 'person@example.test', city: 'Toronto' },
        jobTarget: { targetTitle: 'Junior Analyst' },
      }),
    )
    await user.press(screen.getByRole('button', { name: '保存' }))
    expect(await screen.findByText('已保存')).toBeOnTheScreen()
    expect(screen.getByText('修改前生成的文件：First.docx')).toBeOnTheScreen()

    drafts.generate.mockResolvedValueOnce(generatedResume(49, 'Second.docx'))
    await user.press(screen.getByRole('button', { name: '生成简历' }))
    expect(await screen.findByText('简历已生成：Second.docx')).toBeOnTheScreen()
    const [first, second] = drafts.generate.mock.calls.map((args) => args[1].idempotencyKey)
    expect(second).not.toBe(first)
  })

  it('keeps unresolved requests per account', async () => {
    await SecureStore.setItemAsync('aijob.generate.2.7', JSON.stringify({ idempotencyKey: 'other-account', draftVersion: 3 }))
    drafts.get.mockResolvedValue(readyDraft(7))
    await renderEditor()
    await screen.findByLabelText('姓名')
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(screen.queryByText('上一次生成请求的结果还没有确认。')).not.toBeOnTheScreen()
    expect(screen.getByRole('button', { name: '生成简历' })).toBeOnTheScreen()
  })

  it('does not blindly resubmit when the outcome is unknown: the check reuses the same key', async () => {
    drafts.get.mockResolvedValue(readyDraft(7))
    const { onOpenFiles } = await renderEditor()
    const user = userEvent.setup()
    await screen.findByLabelText('姓名')

    drafts.generate.mockRejectedValueOnce(new ApiError('/resume-builder/drafts/7/generate', null, null))
    await user.press(screen.getByRole('button', { name: '生成简历' }))
    expect(await screen.findByText(/暂时无法确认简历是否已经生成/)).toBeOnTheScreen()
    expect(screen.queryByText(/简历已生成/)).not.toBeOnTheScreen()
    expect(drafts.generate).toHaveBeenCalledTimes(1)
    await user.press(screen.getByRole('button', { name: '查看简历列表' }))
    expect(onOpenFiles).toHaveBeenCalled()

    drafts.generate.mockResolvedValueOnce(generatedResume(42, 'Test Person.docx'))
    await user.press(screen.getByRole('button', { name: '确认上一次生成' }))
    expect(await screen.findByText('简历已生成：Test Person.docx')).toBeOnTheScreen()
    const [first, second] = drafts.generate.mock.calls.map((args) => args[1].idempotencyKey)
    expect(second).toBe(first)
  })
})

describe('account switch', () => {
  it('drops a save response that arrives after another account signed in', async () => {
    drafts.get.mockResolvedValueOnce(builderDraft(7))
    const { client, rerender } = await renderEditor()
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('姓名'), 'Test Person')

    const pending = deferred<ReturnType<typeof builderDraft>>()
    drafts.update.mockReturnValueOnce(pending.promise)
    await user.press(screen.getByRole('button', { name: '保存' }))

    drafts.get.mockReturnValue(new Promise(() => {}))
    mockAuth.user = { userId: 2 }
    client.clear()
    await rerender()
    pending.resolve(builderDraft(7, { version: 9 }, { basicInfo: { fullName: 'Test Person' } }))
    await waitFor(() => expect(drafts.update).toHaveBeenCalledTimes(1))
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(screen.queryByText('已保存')).not.toBeOnTheScreen()
    expect(screen.queryByDisplayValue('Test Person')).not.toBeOnTheScreen()
    expect(client.getQueryData(resumeKeys.draft(1, 7))).toBeUndefined()
    expect(client.getQueryData(resumeKeys.drafts(1))).toBeUndefined()
  })

  it('drops a generate response after sign-out, leaving the new account untouched', async () => {
    drafts.get.mockResolvedValueOnce(readyDraft(7))
    const { client, rerender } = await renderEditor()
    const user = userEvent.setup()
    await screen.findByLabelText('姓名')

    const pending = deferred<ReturnType<typeof generatedResume>>()
    drafts.generate.mockReturnValueOnce(pending.promise)
    await user.press(screen.getByRole('button', { name: '生成简历' }))

    drafts.get.mockReturnValue(new Promise(() => {}))
    mockAuth.user = { userId: 2 }
    client.clear()
    client.setQueryData(resumeKeys.files(2), [])
    await rerender()
    pending.resolve(generatedResume(43, 'Test Person.docx'))
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(screen.queryByText(/简历已生成/)).not.toBeOnTheScreen()
    expect(client.getQueryData(resumeKeys.files(1))).toBeUndefined()
    expect(client.getQueryData(resumeKeys.files(2))).toEqual([])
  })
})
