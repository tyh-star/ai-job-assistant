import { Directory, File, Paths } from 'expo-file-system'
import * as Sharing from 'expo-sharing'
import { tailorApi } from '../api/endpoints'
import { displayFileName } from '../resumes/displayName'
import { ShareUnavailableError } from '../resumes/files'
import { mimeForFileType, safeDownloadName, utiForFileType } from '../resumes/model'

const TAILORED_URL = /^\/api\/v1(\/jobs\/(\d+)\/tailored\/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\.(docx|pdf)))$/

export type TailoredFile = { path: string; fileType: 'docx' | 'pdf' }

/**
 * Turns a downloadUrl/pdfDownloadUrl from the server into the API-relative path the
 * client calls with the Bearer header. Anything that is not exactly the tailored-file
 * route for this job is refused, so it can never be sent to /resumes/{id} or elsewhere.
 */
export function tailoredFileFromUrl(url: string | null | undefined, jobId: number): TailoredFile | null {
  const match = TAILORED_URL.exec((url ?? '').trim())
  if (!match || Number(match[2]) !== jobId) {
    return null
  }
  return { path: match[1], fileType: match[4].toLowerCase() as 'docx' | 'pdf' }
}

/** "<resume name> - <job title>.docx": readable, and it keeps Chinese characters. */
export function tailoredShareName(resumeFileName: string, jobTitle: string, fileType: 'docx' | 'pdf'): string {
  const stem = displayFileName(resumeFileName).replace(/\.(pdf|docx)$/i, '').trim()
  const title = jobTitle.trim()
  // A "/" in a job title is text here, not a folder: keep it from cutting the name.
  const joined = [stem, title].filter(Boolean).join(' - ').replace(/[\\/]+/g, ' ')
  return safeDownloadName(joined, fileType)
}

type Deps = {
  download: typeof tailorApi.download
  isSharingAvailable: () => Promise<boolean>
  share: (uri: string, options: Sharing.SharingOptions) => Promise<void>
}

const nativeDeps: Deps = {
  download: (path) => tailorApi.download(path),
  isSharingAvailable: () => Sharing.isAvailableAsync(),
  share: (uri, options) => Sharing.shareAsync(uri, options),
}

/** Same flow as resume files: authenticated download into the cache, then the share sheet. */
export async function downloadAndShareTailored(file: TailoredFile, name: string, deps: Deps = nativeDeps) {
  if (!(await deps.isSharingAvailable())) {
    throw new ShareUnavailableError()
  }
  const downloaded = await deps.download(file.path)
  const directory = new Directory(Paths.cache, 'tailored')
  directory.create({ idempotent: true, intermediates: true })
  const target = new File(directory, name)
  if (target.exists) {
    target.delete()
  }
  target.create()
  target.write(downloaded.bytes)
  await deps.share(target.uri, {
    mimeType: mimeForFileType(file.fileType),
    UTI: utiForFileType(file.fileType),
    dialogTitle: name,
  })
  return { name, uri: target.uri }
}
