/**
 * Readable names for resume files.
 *
 * React Native 0.86's FormData writes a file part as
 *   filename="<encodeURIComponent(name with "/" replaced by "_")>"
 * (Libraries/Network/FormData.js, encodeFilename), and the server stores that
 * header value as the resume's fileName without decoding it. A file picked as
 * "简历.pdf" on a phone is therefore stored as "%E7%AE%80%E5%8E%86.pdf". Files
 * uploaded from the website are stored with their real name.
 *
 * The stored value is decoded exactly once, and only when it is precisely what
 * encodeURIComponent would have produced. Anything else -- a plain name, a literal
 * "%" in a real name, broken escapes -- is shown as stored.
 */

const PERCENT_ESCAPE = /%[0-9A-Fa-f]{2}/
// eslint-disable-next-line no-control-regex
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/g

function normalizeEscapes(value: string): string {
  return value.replace(/%[0-9A-Fa-f]{2}/g, (escape) => escape.toUpperCase())
}

/** Undoes the one layer of encoding the React Native upload adds, and nothing more. */
export function decodeUploadedFileName(stored: string): string {
  if (!PERCENT_ESCAPE.test(stored)) {
    return stored
  }
  let decoded: string
  try {
    decoded = decodeURIComponent(stored)
  } catch {
    return stored
  }
  // Re-encoding must give back the stored value, so a real name that merely contains
  // "%" (and therefore also spaces, CJK text or other raw characters) is never touched.
  return encodeURIComponent(decoded) === normalizeEscapes(stored) ? decoded : stored
}

/**
 * The name to show for a resume file everywhere: list, details, dashboard, quality
 * score and the share sheet. Never contains path separators or control characters.
 */
export function displayFileName(stored: string | null | undefined): string {
  const raw = stored ?? ''
  const readable = decodeUploadedFileName(raw)
    .replace(CONTROL_CHARACTERS, '')
    .replace(/[\\/]/g, '_')
    .trim()
  return readable || raw.replace(CONTROL_CHARACTERS, '').trim()
}
