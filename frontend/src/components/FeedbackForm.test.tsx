import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter } from 'react-router-dom'
import i18n from '../i18n'
import { AuthProvider } from '../auth/AuthContext'
import { saveSession } from '../auth/session'
import { FeedbackForm } from './FeedbackForm'

const getFeedbackSummary = vi.fn()
const getMyFeedback = vi.fn()
const saveMyFeedback = vi.fn()
const deleteMyFeedback = vi.fn()

vi.mock('../api/feedback', () => ({
  getFeedbackSummary: (...args: unknown[]) => getFeedbackSummary(...args),
  getMyFeedback: (...args: unknown[]) => getMyFeedback(...args),
  saveMyFeedback: (...args: unknown[]) => saveMyFeedback(...args),
  deleteMyFeedback: (...args: unknown[]) => deleteMyFeedback(...args),
}))

const EXISTING_USER = { userId: 1, name: 'Alice', email: 'alice@example.com', role: 'USER' }
const HTML_COMMENT = '<img src=x onerror=alert(1)>Hello</img>'

function emptySummary() {
  return { data: { averageRating: 0, reviewerCount: 0 } }
}

function summaryOf(averageRating: number, reviewerCount: number) {
  return { data: { averageRating, reviewerCount } }
}

function myFeedback(rating: number, comment: string | null) {
  return {
    data: {
      rating,
      comment,
      createdAt: '2026-09-01T00:00:00',
      updatedAt: '2026-09-01T00:00:00',
    },
  }
}

function renderForm(path = '/profile') {
  saveSession('test-token', EXISTING_USER)
  return render(
    <I18nextProvider i18n={i18n}>
      <MemoryRouter initialEntries={[path]}>
        <AuthProvider>
          <FeedbackForm />
        </AuthProvider>
      </MemoryRouter>
    </I18nextProvider>,
  )
}

describe('FeedbackForm', () => {
  beforeEach(async () => {
    localStorage.clear()
    await i18n.changeLanguage('zh-CN')
    getFeedbackSummary.mockResolvedValue(emptySummary())
    getMyFeedback.mockRejectedValue({ response: { status: 404, data: { message: 'No feedback submitted yet' } } })
    saveMyFeedback.mockReset()
    deleteMyFeedback.mockReset()
    Element.prototype.scrollIntoView = vi.fn()
  })

  afterEach(() => {
    cleanup()
    localStorage.clear()
    vi.clearAllMocks()
    vi.unstubAllGlobals()
  })

  it('loads the signed-in visitor\'s own rating into the form', async () => {
    getMyFeedback.mockResolvedValue(myFeedback(3, 'pretty good'))
    renderForm()

    expect(await screen.findByDisplayValue('pretty good')).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: '3 星' })).toHaveAttribute('aria-checked', 'true')
    expect(getMyFeedback).toHaveBeenCalled()
    expect(getFeedbackSummary).toHaveBeenCalled()
  })

  it('submits a 1-star rating', async () => {
    saveMyFeedback.mockResolvedValue(myFeedback(1, 'needs work'))
    getFeedbackSummary
      .mockResolvedValueOnce(emptySummary())
      .mockResolvedValueOnce(summaryOf(1, 1))
    renderForm()
    await screen.findByRole('button', { name: '提交评价' })

    const user = userEvent.setup()
    await user.click(screen.getByRole('radio', { name: '1 星' }))
    await user.type(screen.getByRole('textbox'), 'needs work')
    await user.click(screen.getByRole('button', { name: '提交评价' }))

    await waitFor(() => expect(saveMyFeedback).toHaveBeenCalledWith({ rating: 1, comment: 'needs work' }))
    expect(await screen.findByText('感谢你的评价！')).toBeInTheDocument()
  })

  it('submits a 5-star rating', async () => {
    saveMyFeedback.mockResolvedValue(myFeedback(5, null))
    renderForm()
    await screen.findByRole('button', { name: '提交评价' })

    const user = userEvent.setup()
    await user.click(screen.getByRole('radio', { name: '5 星' }))
    await user.click(screen.getByRole('button', { name: '提交评价' }))

    await waitFor(() => expect(saveMyFeedback).toHaveBeenCalledWith({ rating: 5, comment: null }))
  })

  it('shows a distinct updated message when editing an existing rating', async () => {
    getMyFeedback.mockResolvedValue(myFeedback(2, 'meh'))
    saveMyFeedback.mockResolvedValue(myFeedback(4, 'better now'))
    renderForm()
    await screen.findByDisplayValue('meh')

    const user = userEvent.setup()
    await user.click(screen.getByRole('radio', { name: '4 星' }))
    await user.clear(screen.getByRole('textbox'))
    await user.type(screen.getByRole('textbox'), 'better now')
    await user.click(screen.getByRole('button', { name: '更新评价' }))

    await waitFor(() => expect(saveMyFeedback).toHaveBeenCalledWith({ rating: 4, comment: 'better now' }))
    expect(await screen.findByText('你的评价已更新。')).toBeInTheDocument()
  })

  it('deletes the caller\'s own feedback after a confirm', async () => {
    getMyFeedback.mockResolvedValue(myFeedback(4, 'fine'))
    deleteMyFeedback.mockResolvedValue({})
    vi.stubGlobal('confirm', vi.fn(() => true))
    renderForm()
    await screen.findByDisplayValue('fine')

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '删除我的评价' }))

    await waitFor(() => expect(deleteMyFeedback).toHaveBeenCalled())
    expect(await screen.findByText('你的评价已删除。')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '提交评价' })).toBeInTheDocument()
  })

  it('does not delete when the confirm is cancelled', async () => {
    getMyFeedback.mockResolvedValue(myFeedback(4, 'fine'))
    vi.stubGlobal('confirm', vi.fn(() => false))
    renderForm()
    await screen.findByDisplayValue('fine')

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '删除我的评价' }))

    expect(deleteMyFeedback).not.toHaveBeenCalled()
  })

  it('refuses to submit with 0 stars and never offers a 6th star', async () => {
    renderForm()
    await screen.findByRole('button', { name: '提交评价' })

    expect(screen.getAllByRole('radio')).toHaveLength(5)
    expect(screen.queryByRole('radio', { name: '6 星' })).not.toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: '0 星' })).not.toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '提交评价' }))

    expect(await screen.findByText('请选择 1-5 星评分')).toBeInTheDocument()
    expect(saveMyFeedback).not.toHaveBeenCalled()
  })

  it('refuses to submit a comment longer than 1000 characters', async () => {
    renderForm()
    await screen.findByRole('button', { name: '提交评价' })

    const user = userEvent.setup()
    await user.click(screen.getByRole('radio', { name: '3 星' }))
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'a'.repeat(1001) } })
    await user.click(screen.getByRole('button', { name: '提交评价' }))

    expect(await screen.findByText('评价内容不能超过 1000 字')).toBeInTheDocument()
    expect(saveMyFeedback).not.toHaveBeenCalled()
  })

  it('keeps the drafted comment when save fails', async () => {
    saveMyFeedback.mockRejectedValue({ response: { status: 500, data: { message: 'boom' } } })
    renderForm()
    await screen.findByRole('button', { name: '提交评价' })

    const user = userEvent.setup()
    await user.click(screen.getByRole('radio', { name: '4 星' }))
    await user.type(screen.getByRole('textbox'), 'please keep this draft')
    await user.click(screen.getByRole('button', { name: '提交评价' }))

    expect(await screen.findByText('请求失败，请稍后重试。')).toBeInTheDocument()
    expect(screen.getByDisplayValue('please keep this draft')).toBeInTheDocument()
  })

  it('renders an HTML comment as plain text, never as markup', async () => {
    getMyFeedback.mockResolvedValue(myFeedback(5, HTML_COMMENT))
    renderForm()

    const textarea = await screen.findByRole('textbox')
    expect(textarea).toHaveValue(HTML_COMMENT)
    expect(textarea).not.toHaveAttribute('dangerouslySetInnerHTML')
    expect(document.querySelector('img')).toBeNull()
    expect(document.querySelector('script')).toBeNull()
  })

  it('exposes keyboard-operable stars with aria labels and a remaining-character count', async () => {
    renderForm()
    await screen.findByRole('button', { name: '提交评价' })

    const group = screen.getByRole('radiogroup')
    expect(group.className).toMatch(/flex-wrap/)
    expect(screen.getByRole('radio', { name: '1 星' })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: '5 星' })).toBeInTheDocument()

    const firstStar = screen.getByRole('radio', { name: '1 星' })
    firstStar.focus()
    fireEvent.keyDown(firstStar, { key: 'ArrowRight' })
    await waitFor(() => expect(screen.getByRole('radio', { name: '1 星' })).toHaveAttribute('aria-checked', 'true'))
    fireEvent.keyDown(screen.getByRole('radio', { name: '1 星' }), { key: 'End' })
    await waitFor(() => expect(screen.getByRole('radio', { name: '5 星' })).toHaveAttribute('aria-checked', 'true'))

    expect(screen.getByText(/还可输入 1000 字/)).toBeInTheDocument()
  })

  it('uses wrapping, min-width-free layout so 390px and 200% zoom cannot overflow sideways', async () => {
    renderForm()
    await screen.findByRole('button', { name: '提交评价' })

    const card = screen.getByRole('heading', { name: '网站评价' }).closest('section')
    expect(card?.className).toMatch(/min-w-0/)
    expect(screen.getByRole('radiogroup').className).toMatch(/flex-wrap/)
    expect(screen.getByRole('textbox').className).toMatch(/min-w-0/)
    expect(screen.getByRole('textbox').className).not.toMatch(/whitespace-nowrap/)
  })

  it('prevents a second submit while the first save is still in flight', async () => {
    let resolveSave: (value: unknown) => void = () => {}
    saveMyFeedback.mockImplementation(
      () => new Promise((resolve) => {
        resolveSave = resolve
      }),
    )
    renderForm()
    await screen.findByRole('button', { name: '提交评价' })

    const user = userEvent.setup()
    await user.click(screen.getByRole('radio', { name: '5 星' }))
    await user.click(screen.getByRole('button', { name: '提交评价' }))
    expect(screen.getByRole('button', { name: '提交中…' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '提交中…' }))
    expect(saveMyFeedback).toHaveBeenCalledTimes(1)
    resolveSave(myFeedback(5, null))
    await screen.findByText('感谢你的评价！')
  })

  it('uses singular English copy for 1 review and plural for 0 and 2', async () => {
    await i18n.changeLanguage('en')
    getFeedbackSummary.mockResolvedValue(emptySummary())
    const first = renderForm()
    expect(await screen.findByText('No reviews yet -- be the first to rate the site.')).toBeInTheDocument()
    first.unmount()

    getFeedbackSummary.mockResolvedValue(summaryOf(5, 1))
    const second = renderForm()
    expect(await screen.findByText('1 review so far, average 5.0 / 5')).toBeInTheDocument()
    second.unmount()

    getFeedbackSummary.mockResolvedValue(summaryOf(4, 2))
    renderForm()
    expect(await screen.findByText('2 reviews so far, average 4.0 / 5')).toBeInTheDocument()
  })

  it('scrolls the card into view when opened at /profile#feedback', async () => {
    renderForm('/profile#feedback')
    await screen.findByRole('button', { name: '提交评价' })
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
  })
})
