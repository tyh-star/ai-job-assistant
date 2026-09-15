import { useEffect, useMemo, useState } from 'react'
import { NavLink, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { COPY, type PreviewLang } from './copy'
import {
  DashboardDataScreen,
  DashboardErrorScreen,
  DashboardLoadingScreen,
  DashboardNewScreen,
  HomeScreen,
  JobDetailScreen,
  JobsScreen,
  TokensScreen,
} from './screens'
import './tokens.css'

const SCREENS = [
  { to: '/__design/home', key: 'home' as const },
  { to: '/__design/dashboard-new', key: 'dashboardNew' as const },
  { to: '/__design/dashboard-data', key: 'dashboardData' as const },
  { to: '/__design/dashboard-loading', key: 'dashboardLoading' as const },
  { to: '/__design/dashboard-error', key: 'dashboardError' as const },
  { to: '/__design/jobs', key: 'jobs' as const },
  { to: '/__design/job-detail', key: 'detail' as const },
  { to: '/__design/tokens', key: 'tokens' as const },
]

function ViewportStamp({ lang }: { lang: PreviewLang }) {
  const [size, setSize] = useState({ w: 0, h: 0, dpr: 1, pinch: 1 })
  useEffect(() => {
    function read() {
      setSize({
        w: window.innerWidth,
        h: window.innerHeight,
        dpr: window.devicePixelRatio || 1,
        pinch: window.visualViewport?.scale || 1,
      })
    }
    read()
    window.addEventListener('resize', read)
    window.visualViewport?.addEventListener('resize', read)
    return () => {
      window.removeEventListener('resize', read)
      window.visualViewport?.removeEventListener('resize', read)
    }
  }, [])
  const t = COPY[lang]
  return (
    <div className="ds-viewport" aria-live="polite">
      <span>
        {t.viewport} {size.w}×{size.h}
      </span>
      <span>
        {t.dprLabel} {size.dpr.toFixed(2)} · {t.dprHint}
      </span>
      {size.pinch !== 1 ? <span>visualViewport.scale {size.pinch.toFixed(2)}</span> : null}
    </div>
  )
}

export default function DesignPreviewApp() {
  const location = useLocation()
  const { i18n } = useTranslation()
  const lang: PreviewLang = (i18n.resolvedLanguage ?? i18n.language).startsWith('zh') ? 'zh' : 'en'
  const chromeOn = new URLSearchParams(location.search).get('chrome') !== '0'
  const t = COPY[lang]
  const screenLabel = useMemo(() => {
    const hit = SCREENS.find((item) => location.pathname.startsWith(item.to))
    return hit ? t.screens[hit.key] : t.screens.home
  }, [location.pathname, t])

  function setLang(next: PreviewLang) {
    void i18n.changeLanguage(next === 'zh' ? 'zh-CN' : 'en')
  }

  return (
    <div className="ds" data-chrome={chromeOn ? 'on' : 'off'}>
      {chromeOn ? (
        <>
          <div className="ds-banner" role="note">
            <strong>{t.prototype}</strong>
            <span>
              {screenLabel} · {lang === 'zh' ? '中文' : 'EN'}
            </span>
          </div>
          <nav className="ds-screen-nav" aria-label="Design preview screens">
            {SCREENS.map((item) => (
              <NavLink key={item.to} to={`${item.to}${location.search}`}>
                {t.screens[item.key]}
              </NavLink>
            ))}
            <span className="ds-lang" role="group" aria-label="Language">
              <button type="button" aria-pressed={lang === 'zh'} onClick={() => setLang('zh')}>
                中文
              </button>
              <button type="button" aria-pressed={lang === 'en'} onClick={() => setLang('en')}>
                EN
              </button>
            </span>
          </nav>
        </>
      ) : null}
      <Routes>
        <Route index element={<Navigate to="home" replace />} />
        <Route path="home" element={<HomeScreen lang={lang} onLangChange={setLang} />} />
        <Route path="dashboard-new" element={<DashboardNewScreen lang={lang} onLangChange={setLang} />} />
        <Route path="dashboard-data" element={<DashboardDataScreen lang={lang} onLangChange={setLang} />} />
        <Route path="dashboard-loading" element={<DashboardLoadingScreen lang={lang} onLangChange={setLang} />} />
        <Route path="dashboard-error" element={<DashboardErrorScreen lang={lang} onLangChange={setLang} />} />
        <Route path="jobs" element={<JobsScreen lang={lang} onLangChange={setLang} />} />
        <Route path="job-detail" element={<JobDetailScreen lang={lang} onLangChange={setLang} />} />
        <Route path="tokens" element={<TokensScreen lang={lang} />} />
        <Route path="*" element={<Navigate to="home" replace />} />
      </Routes>
      {chromeOn ? <ViewportStamp lang={lang} /> : null}
    </div>
  )
}
