/**
 * One safe way to show and save resume file names on the website.
 *
 * Phone uploads (React Native FormData) used to be stored percent-encoded, e.g.
 * "%E7%AE%80%E5%8E%86.pdf" for "简历.pdf". The backend now decodes on upload and in its
 * responses; this module applies the same rules on the page as well, so older rows and
 * older API versions still read correctly.
 *
 * Order matters: percent-decoding first, then path and control-character cleanup, so an
 * encoded "%2F", "%5C" or "%00" can never survive as a separator or NUL.
 */

const MAX_LAYERS = 3
const ESCAPE = /%[0-9A-Fa-f]{2}/
/** Only characters encodeURIComponent leaves alone, plus escapes. */
const ENCODED_SHAPE = /^[A-Za-z0-9\-_.!~*'()%]*$/
const BROKEN_PERCENT = /%(?![0-9A-Fa-f]{2})/
// Built from an escaped string so the control characters stay visible in source.
const UNSAFE = new RegExp('[\\u0000-\\u001f\\u007f-\\u009f\\u202a-\\u202e\\u2066-\\u2069]', 'g')
const RESERVED_ON_DISK = /[<>:"|?*]/g

/**
 * Undoes percent-encoding, at most a few layers, and only while the value looks fully
 * encoded and decodes to valid UTF-8. Anything else is returned unchanged -- never throws.
 */
export function decodeStoredFileName(stored: string): string {
  let current = stored
  for (let layer = 0; layer < MAX_LAYERS; layer += 1) {
    if (!ESCAPE.test(current) || !ENCODED_SHAPE.test(current) || BROKEN_PERCENT.test(current)) {
      break
    }
    let next: string
    try {
      next = decodeURIComponent(current)
    } catch {
      break
    }
    if (next === current) {
      break
    }
    current = next
  }
  return current
}

/** The name to show anywhere: decoded, last path segment only, no control characters. */
export function displayFileName(stored: string | null | undefined): string {
  const decoded = decodeStoredFileName(stored ?? '')
  const segment = decoded.split(/[\\/]/).pop() ?? ''
  const cleaned = segment.replace(UNSAFE, '').trim()
  return cleaned === '.' || cleaned === '..' ? '' : cleaned
}

/** A name that is safe to hand to the browser as a download file name. */
export function safeDownloadFileName(name: string | null | undefined, fallback = 'download'): string {
  const cleaned = displayFileName(name).replace(RESERVED_ON_DISK, '_').trim()
  return cleaned || fallback
}

/**
 * Reads the download name from Content-Disposition, preferring RFC 5987 filename*.
 * Malformed values fall back instead of throwing.
 */
export function fileNameFromContentDisposition(header: string | null | undefined, fallback: string): string {
  if (header) {
    const star = /filename\*\s*=\s*([^']*)'[^']*'([^;]+)/i.exec(header)
    if (star && /^utf-8$/i.test(star[1].trim())) {
      try {
        return safeDownloadFileName(decodeURIComponent(star[2].trim().replace(/^"|"$/g, '')), fallback)
      } catch {
        // fall through to the plain filename
      }
    }
    const plain = /filename\s*=\s*(?:"([^"]*)"|([^;]+))/i.exec(header)
    const value = plain?.[1] ?? plain?.[2]
    if (value) {
      return safeDownloadFileName(value.trim(), fallback)
    }
  }
  return safeDownloadFileName(fallback, 'download')
}
