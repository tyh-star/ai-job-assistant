/**
 * V3 prototype host (DEV-only route /__design-v3).
 *
 * Leaves the older /__design (V1/V2) prototype and every production page
 * untouched.
 *
 *   /__design-v3/site               signed-out marketing site
 *   /__design-v3/workbench-new      signed in, nothing created yet
 *   /__design-v3/workbench-data     signed in, DEV fixture loaded
 *   /__design-v3/workbench-loading  requests in flight
 *   /__design-v3/workbench-error    every request failed
 *   /__design-v3/workbench-error?partial=1   one panel failed, page did not
 *   /__design-v3/compare            same-viewport A/B against the current pages
 *
 * ?chrome=0 hides the review toolbar -- and with it the "DEV fixture" marker,
 * which is why that marker lives in the toolbar and never inside a page.
 */

import { useEffect } from 'react'
import { Navigate, NavLink, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { useTranslation } from 'react-i18next'
import { PROTOTYPE_NOTICE, type Lang } from './content'
import { Compare } from './Compare'
import { SiteHome } from './SiteHome'
import { Workbench, type WorkbenchState } from './Workbench'
import './v3.css'

const SCREENS: { to: string; label: string; fixture?: boolean }[] = [
  { to: 'site', label: 'Site' },
  { to: 'workbench-new', label: 'New' },
  { to: 'workbench-data', label: 'Data', fixture: true },
  { to: 'workbench-loading', label: 'Loading' },
  { to: 'workbench-error', label: 'Error' },
  { to: 'compare', label: 'Compare' },
]

function useLang(): [Lang, (next: Lang) => void] {
  const { i18n } = useTranslation()
  const lang: Lang = (i18n.resolvedLanguage ?? i18n.language ?? 'zh').startsWith('zh') ? 'zh' : 'en'
  function setLang(next: Lang) {
    void i18n.changeLanguage(next === 'zh' ? 'zh-CN' : 'en')
  }
  return [lang, setLang]
}

/**
 * Review-only chrome. Carries the synthetic-data disclosure so the prototype
 * pages themselves can stay clean; disappears entirely under ?chrome=0.
 */
function ReviewBar({ search }: { search: string }) {
  const location = useLocation()
  const onFixtureScreen = location.pathname.endsWith('workbench-data')
  const onError = location.pathname.endsWith('workbench-error')
  const partial = new URLSearchParams(location.search).get('partial') === '1'

  return (
    <div className="v3-reviewBar">
      <span className="v3-reviewBar__tag">{PROTOTYPE_NOTICE}</span>
      <nav aria-label="Prototype screens">
        {SCREENS.map((screen) => (
          <NavLink key={screen.to} to={`/__design-v3/${screen.to}${search}`}>
            {screen.label}
          </NavLink>
        ))}
      </nav>
      {onError ? (
        <NavLink
          to={`/__design-v3/workbench-error${partial ? '' : '?partial=1'}`}
          className="v3-reviewBar__variant"
        >
          {partial ? '→ full-page failure' : '→ partial failure'}
        </NavLink>
      ) : null}
      {onFixtureScreen ? <span className="v3-reviewBar__fixture">DEV fixture data</span> : null}
    </div>
  )
}

function WorkbenchScreen({ state }: { state: WorkbenchState }) {
  const [lang, setLang] = useLang()
  const navigate = useNavigate()
  const location = useLocation()
  return (
    <Workbench
      state={state}
      lang={lang}
      onLang={setLang}
      onExit={() => navigate(`/__design-v3/site${location.search}`)}
    />
  )
}

function ErrorScreen() {
  const location = useLocation()
  const partial = new URLSearchParams(location.search).get('partial') === '1'
  return <WorkbenchScreen state={partial ? 'partial' : 'error'} />
}

export default function DesignV3App() {
  const location = useLocation()
  const navigate = useNavigate()
  const [lang, setLang] = useLang()
  const chrome = new URLSearchParams(location.search).get('chrome') !== '0'

  // The prototype paints the viewport background; restore the real app's on unmount.
  useEffect(() => {
    const previous = document.body.style.background
    return () => {
      document.body.style.background = previous
    }
  }, [])

  const search = location.search

  return (
    <div className="v3" data-review={chrome ? 'on' : 'off'}>
      <Routes>
        <Route index element={<Navigate to={`site${search}`} replace />} />
        <Route
          path="site"
          element={
            <SiteHome
              lang={lang}
              onLang={setLang}
              onEnterApp={() => navigate(`/__design-v3/workbench-data${search}`)}
            />
          }
        />
        <Route path="workbench-new" element={<WorkbenchScreen state="new" />} />
        <Route path="workbench-data" element={<WorkbenchScreen state="data" />} />
        <Route path="workbench-loading" element={<WorkbenchScreen state="loading" />} />
        <Route path="workbench-error" element={<ErrorScreen />} />
        {/* Round-1 URL, kept so existing links land somewhere sensible. */}
        <Route path="workbench" element={<Navigate to={`/__design-v3/workbench-data${search}`} replace />} />
        <Route path="compare" element={<Compare />} />
        <Route path="*" element={<Navigate to={`site${search}`} replace />} />
      </Routes>
      {chrome ? <ReviewBar search={search} /> : null}
    </div>
  )
}
