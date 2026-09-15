/**
 * V3 signed-out surface: product marketing site, TOP horizontal navigation.
 *
 * Content rule: no invented facts. There is no metric strip, no listing feed,
 * no employer names, no user counts, no success rates, no testimonials, and no
 * pricing, because none of those exist in the product. Every claim here maps
 * to a route in App.tsx or an endpoint in api/endpoints.ts -- see content.ts.
 *
 * The hero illustration is an abstract, dataless rendering of the workbench
 * chrome: bars and rules only, no numbers, job titles, or company names.
 *
 * Structure, fixed: nav -> hero + two actions -> three capabilities ->
 * three steps -> closing CTA -> simple footer.
 */

import { useEffect, useState } from 'react'
import { COPY, ROUTES, type Lang } from './content'
import {
  IconArrow,
  IconBrand,
  IconBriefcase,
  IconCheck,
  IconClose,
  IconDoc,
  IconMenu,
  IconSparkle,
} from './icons'

type Props = { lang: Lang; onLang: (next: Lang) => void; onEnterApp: () => void }

const CAP_ICONS = [IconBriefcase, IconDoc, IconSparkle]

function Brand({ label }: { label: string }) {
  return (
    <span className="v3-brand">
      <span className="v3-brand__mark"><IconBrand size={16} /></span>
      {label}
    </span>
  )
}

function LangToggle({ lang, onLang, label }: { lang: Lang; onLang: (n: Lang) => void; label: string }) {
  return (
    <span className="v3-lang" role="group" aria-label={label}>
      <button type="button" aria-pressed={lang === 'zh'} onClick={() => onLang('zh')}>中文</button>
      <button type="button" aria-pressed={lang === 'en'} onClick={() => onLang('en')}>EN</button>
    </span>
  )
}

/**
 * Hero illustration: a slice of the tailoring workspace, not a tutorial.
 *
 * Left is what the posting asks for; right is the rewrite it produces, shown
 * as a current-vs-suggested pair. The eye reads left-to-right and gets
 * "posting requirements -> resume rewrite" without reading a word of the small
 * type. An earlier version used a numbered 1-2-3 stepper with a saturated blue
 * result block; both are gone.
 *
 * Content rule unchanged: three generic technology tags and generic guidance
 * strings. No employer, role, person, number, score or percentage.
 */
function TailorShot({ t }: { t: (typeof COPY)['zh']['shot'] }) {
  return (
    <figure className="v3-shot">
      <div className="v3-shot__bar">
        <i /><i /><i />
      </div>

      <div className="v3-shot__head">
        <strong>{t.title}</strong>
        <span>{t.status}</span>
      </div>

      <div className="v3-shot__work">
        {/* left: the posting */}
        <aside className="v3-shot__req">
          <p className="v3-shot__colTitle">{t.reqTitle}</p>
          <div className="v3-shot__tags">
            <span className="v3-shot__tag">Java</span>
            <span className="v3-shot__tag">Spring Boot</span>
            <span className="v3-shot__tag">REST API</span>
          </div>
          {/* abstract posting body -- deliberately unreadable, it is not content */}
          <div className="v3-shot__lines" aria-hidden="true">
            <i style={{ width: '92%' }} /><i style={{ width: '74%' }} />
            <i style={{ width: '86%' }} /><i style={{ width: '58%' }} />
            <i style={{ width: '80%' }} /><i style={{ width: '66%' }} />
          </div>
        </aside>

        {/* right: the rewrite */}
        <div className="v3-shot__suggest">
          <p className="v3-shot__colTitle">{t.suggestTitle}</p>

          <div className="v3-shot__before">
            <p className="v3-shot__miniLabel">{t.beforeLabel}</p>
            <div className="v3-shot__lines" aria-hidden="true">
              <i style={{ width: '88%' }} /><i style={{ width: '52%' }} />
            </div>
          </div>

          <div className="v3-shot__after">
            <p className="v3-shot__miniLabel">{t.afterLabel}</p>
            <p>{t.out1}</p>
            <p>{t.out2}</p>
          </div>
        </div>
      </div>

      <figcaption className="v3-shot__foot">
        <span className="v3-shot__trust">
          <IconCheck size={13} />
          {t.basedOn}
        </span>
        <span className="v3-shot__confirm">{t.needsConfirm}</span>
      </figcaption>
    </figure>
  )
}

export function SiteHome({ lang, onLang, onEnterApp }: Props) {
  const t = COPY[lang]
  const [stuck, setStuck] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)

  useEffect(() => {
    function onScroll() {
      setStuck(window.scrollY > 8)
    }
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  useEffect(() => {
    if (!menuOpen) {
      return
    }
    function onKey(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setMenuOpen(false)
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [menuOpen])

  const navItems = [
    { label: t.siteNav.home, href: ROUTES.home },
    { label: t.siteNav.jobs, href: ROUTES.jobs },
    { label: t.siteNav.resumeTools, href: ROUTES.resumeBuilder },
    { label: t.siteNav.tailor, href: ROUTES.tailor },
  ]

  return (
    <div className="v3-site">
      {/* ------------------------------------------------------- 1. top nav */}
      <header className="v3-siteHeader" data-stuck={stuck ? 'true' : 'false'}>
        <div className="v3-siteHeader__inner">
          <Brand label={t.brand} />

          <nav className="v3-siteNav" aria-label={t.app.sidebarLabel}>
            {navItems.map((item, index) => (
              <a key={item.label} href="#top" aria-current={index === 0 ? 'page' : undefined}>
                {item.label}
              </a>
            ))}
          </nav>

          <div className="v3-siteHeader__actions">
            <LangToggle lang={lang} onLang={onLang} label={t.auth.language} />
            <button type="button" className="v3-btn v3-btn--quiet" onClick={onEnterApp}>
              {t.auth.login}
            </button>
            <button type="button" className="v3-btn v3-btn--primary" onClick={onEnterApp}>
              {t.auth.register}
            </button>
            <button
              type="button"
              className="v3-iconBtn v3-siteHeader__burger"
              aria-expanded={menuOpen}
              aria-label={menuOpen ? t.app.closeMenu : t.app.openMenu}
              onClick={() => setMenuOpen((open) => !open)}
            >
              {menuOpen ? <IconClose size={18} /> : <IconMenu size={18} />}
            </button>
          </div>
        </div>

        {menuOpen ? (
          <div className="v3-siteHeader__drawer">
            <nav aria-label={t.app.sidebarLabel}>
              {navItems.map((item) => (
                <a key={item.label} href="#top" onClick={() => setMenuOpen(false)}>{item.label}</a>
              ))}
            </nav>
            <div className="v3-siteHeader__drawerFoot">
              <button type="button" className="v3-btn v3-btn--ghost" onClick={onEnterApp}>
                {t.auth.login}
              </button>
              <button type="button" className="v3-btn v3-btn--primary" onClick={onEnterApp}>
                {t.auth.register}
              </button>
            </div>
          </div>
        ) : null}
      </header>

      {/* ------------------------------------------- 2. hero + two actions */}
      <section className="v3-hero" id="top">
        <div className="v3-wrap v3-hero__grid">
          <div className="v3-hero__copy">
            <h1>
              {t.hero.titleA}
              <em>{t.hero.titleEm}</em>
            </h1>
            <p className="v3-hero__sub">{t.hero.sub}</p>
            <div className="v3-hero__cta">
              <button type="button" className="v3-btn v3-btn--primary v3-btn--lg" onClick={onEnterApp}>
                {t.hero.primary}
                <IconArrow size={16} />
              </button>
              <button type="button" className="v3-btn v3-btn--ghost v3-btn--lg" onClick={onEnterApp}>
                {t.hero.secondary}
              </button>
            </div>
            <ul className="v3-hero__facts">
              {t.hero.facts.map((fact) => (
                <li key={fact}>
                  <IconCheck size={13} />
                  {fact}
                </li>
              ))}
            </ul>
          </div>
          <TailorShot t={t.shot} />
        </div>
      </section>

      {/* --------------------------------------------- 3. three capabilities */}
      <section className="v3-section">
        <div className="v3-wrap">
          <div className="v3-section__head">
            <p className="v3-eyebrow">{t.caps.eyebrow}</p>
            <h2>{t.caps.title}</h2>
            <p>{t.caps.sub}</p>
          </div>
          <ul className="v3-caps">
            {t.caps.items.map((item, index) => {
              const Icon = CAP_ICONS[index]
              return (
                <li key={item.title} className="v3-cap">
                  <span className="v3-cap__icon"><Icon size={18} /></span>
                  <h3>{item.title}</h3>
                  <p>{item.body}</p>
                  <button type="button" className="v3-link v3-cap__link" onClick={onEnterApp}>
                    {item.link}
                    <IconArrow size={14} />
                  </button>
                </li>
              )
            })}
          </ul>
        </div>
      </section>

      {/* ---------------------------------------------------- 4. three steps */}
      <section className="v3-section v3-section--tint">
        <div className="v3-wrap">
          <div className="v3-section__head">
            <p className="v3-eyebrow">{t.steps.eyebrow}</p>
            <h2>{t.steps.title}</h2>
            <p>{t.steps.sub}</p>
          </div>
          <ol className="v3-steps">
            {t.steps.items.map((item, index) => (
              <li key={item.title} className="v3-step">
                <span className="v3-step__num">{index + 1}</span>
                <div className="v3-step__body">
                  <h3>{item.title}</h3>
                  <p>{item.body}</p>
                </div>
              </li>
            ))}
          </ol>
        </div>
      </section>

      {/* ------------------------------------------------------ 5. final CTA */}
      <section className="v3-cta">
        <div className="v3-wrap v3-cta__inner">
          <div className="v3-cta__copy">
            <h2>{t.cta.title}</h2>
            <p>{t.cta.sub}</p>
          </div>
          <div className="v3-cta__actions">
            <button type="button" className="v3-btn v3-btn--onDark v3-btn--lg" onClick={onEnterApp}>
              {t.cta.primary}
              <IconArrow size={16} />
            </button>
            <button type="button" className="v3-btn v3-btn--lg v3-btn--onDarkGhost" onClick={onEnterApp}>
              {t.cta.secondary}
            </button>
          </div>
        </div>
      </section>

      {/* --------------------------------------------------- 6. simple footer */}
      <footer className="v3-footer">
        <div className="v3-wrap v3-footer__inner">
          <div className="v3-footer__brand">
            <Brand label={t.brand} />
            <p>{t.footer.about}</p>
          </div>
          <nav className="v3-footer__nav" aria-label={t.footer.nav}>
            {navItems.map((item) => (
              <a key={item.label} href="#top">{item.label}</a>
            ))}
            <button type="button" onClick={onEnterApp}>{t.auth.login}</button>
            <button type="button" onClick={onEnterApp}>{t.auth.register}</button>
          </nav>
        </div>
        <div className="v3-wrap v3-footer__bottom">
          <span>© 2026 {t.footer.rights}</span>
          <span>{t.footer.legal}</span>
        </div>
      </footer>
    </div>
  )
}
