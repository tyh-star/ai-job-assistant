/** Frontend path for a catalog posting. Encodes opaque ids such as `freehire:…`. */
export function catalogJobPath(catalogJobId: string): string {
  return `/jobs/${encodeURIComponent(catalogJobId)}`
}

export function catalogJobIdFromParam(param: string | undefined): string {
  if (!param) {
    return ''
  }
  try {
    return decodeURIComponent(param)
  } catch {
    return param
  }
}

export function isSpecifiedCatalogFacet(value: string | null | undefined): boolean {
  return Boolean(value) && value !== 'UNSPECIFIED'
}
