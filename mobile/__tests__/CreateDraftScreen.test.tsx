import '../src/i18n'
import { screen, userEvent, waitFor } from '@testing-library/react-native'
import { ApiError } from '../src/api/client'
import { draftsApi } from '../src/api/endpoints'
import { resumeKeys } from '../src/resumes/queries'
import { CreateDraftScreen } from '../src/screens/CreateDraftScreen'
import { builderDraft } from '../test-utils/builderFixtures'
import { renderWithQuery } from '../test-utils/render'

jest.mock('../src/api/endpoints', () => ({
  draftsApi: { create: jest.fn(), list: jest.fn() },
  resumesApi: {},
  jobsApi: {},
  catalogApi: {},
  authApi: {},
}))

jest.mock('../src/auth/AuthContext', () => ({
  useAuth: () => ({ user: { userId: 1, name: 'nb', email: 'nb@example.test', role: 'USER' } }),
}))

const drafts = draftsApi as jest.Mocked<typeof draftsApi>

beforeEach(() => {
  jest.resetAllMocks()
  drafts.list.mockResolvedValue([])
})

it('creates a blank resume with the chosen template and language, once', async () => {
  let finish: (value: ReturnType<typeof builderDraft>) => void = () => {}
  drafts.create.mockReturnValue(new Promise((resolve) => (finish = resolve)))
  const onCreated = jest.fn()
  const client = await renderWithQuery(<CreateDraftScreen onBack={jest.fn()} onCreated={onCreated} />)
  const user = userEvent.setup()

  expect(screen.getByRole('radio', { name: '通用 ATS 模板' })).toBeChecked()
  expect(screen.getByRole('radio', { name: '中文' })).toBeChecked()
  await user.press(screen.getByRole('radio', { name: '学生模板' }))
  await user.press(screen.getByRole('radio', { name: 'English' }))
  await user.press(screen.getByRole('button', { name: '创建并开始填写' }))
  const busy = screen.getByRole('button', { name: '正在创建…' })
  expect(busy).toBeDisabled()
  await user.press(busy)
  expect(drafts.create).toHaveBeenCalledTimes(1)
  expect(drafts.create).toHaveBeenCalledWith({ title: '未命名简历', templateType: 'STUDENT', locale: 'en' })

  const created = builderDraft(15, { templateType: 'STUDENT', locale: 'en' })
  finish(created)
  await waitFor(() => expect(onCreated).toHaveBeenCalledWith(15))
  expect(client.getQueryData(resumeKeys.draft(1, 15))).toEqual(created)
})

it('keeps the choices after a failure so the user can try again', async () => {
  drafts.create.mockRejectedValueOnce(new ApiError('/resume-builder/drafts', null, null))
  const onCreated = jest.fn()
  await renderWithQuery(<CreateDraftScreen onBack={jest.fn()} onCreated={onCreated} />)
  const user = userEvent.setup()

  await user.type(screen.getByLabelText('简历标题（可选）'), '数据分析简历')
  await user.press(screen.getByRole('radio', { name: '技术岗模板' }))
  await user.press(screen.getByRole('button', { name: '创建并开始填写' }))
  expect(await screen.findByText('没有创建成功：网络异常，请检查连接。')).toBeOnTheScreen()
  expect(screen.getByLabelText('简历标题（可选）')).toHaveDisplayValue('数据分析简历')
  expect(screen.getByRole('radio', { name: '技术岗模板' })).toBeChecked()
  expect(onCreated).not.toHaveBeenCalled()

  drafts.create.mockResolvedValueOnce(builderDraft(16))
  await user.press(screen.getByRole('button', { name: '创建并开始填写' }))
  await waitFor(() => expect(onCreated).toHaveBeenCalledWith(16))
  expect(drafts.create).toHaveBeenLastCalledWith({ title: '数据分析简历', templateType: 'TECHNICAL', locale: 'zh-CN' })
})
