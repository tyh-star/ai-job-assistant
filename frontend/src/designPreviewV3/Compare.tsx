/**
 * Same-viewport A/B view: the current pages beside the V3 redesign.
 *
 * Both panes are real iframes pinned to the SAME pixel viewport and scaled to
 * fit, so the comparison is like-for-like rather than two screenshots taken at
 * different widths. The signed-in "before" points at the V2 prototype's
 * dashboard screen because the production /dashboard is behind auth.
 */

import { useState } from 'react'

const VIEWPORTS = [
  { id: 'desktop', label: '1440 × 900', w: 1440, h: 900 },
  { id: 'laptop', label: '1280 × 800', w: 1280, h: 800 },
  { id: 'mobile', label: '390 × 844', w: 390, h: 844 },
] as const

const PAIRS = [
  {
    id: 'anon',
    title: '未登录 · 首页 / Signed out · home',
    before: { label: '现状 Before', src: '/' },
    after: { label: 'V3 After', src: '/__design-v3/site?chrome=0' },
  },
  {
    id: 'auth',
    title: '已登录 · 控制台（有数据）/ Signed in · console (with data)',
    before: { label: '现状 Before', src: '/__design/dashboard-data?chrome=0' },
    after: { label: 'V3 After', src: '/__design-v3/workbench-data?chrome=0' },
  },
  {
    id: 'new',
    title: '已登录 · 新用户 / Signed in · new account',
    before: { label: '现状 Before', src: '/__design/dashboard-new?chrome=0' },
    after: { label: 'V3 After', src: '/__design-v3/workbench-new?chrome=0' },
  },
] as const

function Pane({
  label,
  src,
  w,
  h,
  scale,
  tone,
}: {
  label: string
  src: string
  w: number
  h: number
  scale: number
  tone: 'before' | 'after'
}) {
  return (
    <figure style={{ margin: 0, minWidth: 0 }}>
      <figcaption
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          marginBottom: 8,
          fontSize: 12,
          fontWeight: 700,
          letterSpacing: '0.04em',
          textTransform: 'uppercase',
          color: tone === 'after' ? '#1d4ed8' : '#8493ad',
        }}
      >
        <span
          style={{
            width: 8,
            height: 8,
            borderRadius: 999,
            background: tone === 'after' ? '#2563eb' : '#c3cddd',
          }}
        />
        {label}
        <span style={{ marginLeft: 'auto', fontWeight: 500, textTransform: 'none', color: '#8493ad' }}>
          {w}×{h}
        </span>
      </figcaption>
      <div
        style={{
          width: w * scale,
          height: h * scale,
          borderRadius: 10,
          border: '1px solid #e4e9f1',
          background: '#fff',
          overflow: 'hidden',
          boxShadow: '0 6px 24px -10px rgb(13 21 38 / 0.25)',
        }}
      >
        <iframe
          title={label}
          src={src}
          width={w}
          height={h}
          style={{
            width: w,
            height: h,
            border: 0,
            transform: `scale(${scale})`,
            transformOrigin: 'top left',
            display: 'block',
          }}
        />
      </div>
    </figure>
  )
}

export function Compare() {
  const [vp, setVp] = useState<(typeof VIEWPORTS)[number]>(VIEWPORTS[0])
  // Two panes side by side inside a ~1500px page, minus gaps and padding.
  const scale = Math.min(1, 700 / vp.w)

  return (
    <div
      style={{
        minHeight: '100vh',
        background: '#f5f7fb',
        padding: '28px 24px 64px',
        fontFamily: '"Segoe UI", system-ui, sans-serif',
        color: '#0d1526',
      }}
    >
      <div style={{ maxWidth: 1500, margin: '0 auto' }}>
        <header style={{ display: 'flex', alignItems: 'center', gap: 16, flexWrap: 'wrap', marginBottom: 24 }}>
          <div style={{ minWidth: 0 }}>
            <h1 style={{ margin: 0, fontSize: 20, fontWeight: 800, letterSpacing: '-0.02em' }}>
              同视口对比 · Same-viewport comparison
            </h1>
            <p style={{ margin: '4px 0 0', fontSize: 13.5, color: '#5c6a85' }}>
              左：现状。右：V3。两侧使用完全相同的视口尺寸，均为可交互的真实页面。
            </p>
          </div>
          <div
            role="group"
            aria-label="Viewport"
            style={{ marginLeft: 'auto', display: 'flex', gap: 4, padding: 3, background: '#eef1f7', borderRadius: 999 }}
          >
            {VIEWPORTS.map((item) => (
              <button
                key={item.id}
                type="button"
                onClick={() => setVp(item)}
                aria-pressed={vp.id === item.id}
                style={{
                  border: 0,
                  cursor: 'pointer',
                  padding: '7px 14px',
                  borderRadius: 999,
                  fontSize: 13,
                  fontWeight: 600,
                  background: vp.id === item.id ? '#fff' : 'transparent',
                  color: vp.id === item.id ? '#0d1526' : '#5c6a85',
                  boxShadow: vp.id === item.id ? '0 1px 2px rgb(13 21 38 / 0.12)' : 'none',
                }}
              >
                {item.label}
              </button>
            ))}
          </div>
        </header>

        {PAIRS.map((pair) => (
          <section key={pair.id} style={{ marginBottom: 40 }}>
            <h2
              style={{
                margin: '0 0 12px',
                fontSize: 14,
                fontWeight: 700,
                paddingBottom: 8,
                borderBottom: '1px solid #e4e9f1',
              }}
            >
              {pair.title}
            </h2>
            <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap', alignItems: 'flex-start' }}>
              <Pane {...pair.before} w={vp.w} h={vp.h} scale={scale} tone="before" />
              <Pane {...pair.after} w={vp.w} h={vp.h} scale={scale} tone="after" />
            </div>
          </section>
        ))}
      </div>
    </div>
  )
}
