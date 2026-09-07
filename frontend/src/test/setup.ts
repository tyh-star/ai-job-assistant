import '@testing-library/jest-dom/vitest'

// jsdom implements neither of these. Stubbed globally (not per-test) since any component
// using scrollIntoView -- not just JobCatalogSearch's pagination scroll -- would
// otherwise throw "scrollIntoView is not a function" the moment a test renders it.
// Individual tests override window.matchMedia with vi.spyOn when they need to assert
// the prefers-reduced-motion branch specifically.
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {}
}
if (!window.matchMedia) {
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }) as MediaQueryList
}
