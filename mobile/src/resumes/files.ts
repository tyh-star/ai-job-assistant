import { Directory, File, Paths } from 'expo-file-system'
import * as Sharing from 'expo-sharing'
import { resumesApi } from '../api/endpoints'
import type { ResumeResponse } from '../api/types'
import { displayFileName } from './displayName'
import { mimeForFileType, safeDownloadName, utiForFileType } from './model'

export class ShareUnavailableError extends Error {
  constructor() {
    super('Sharing is not available on this device')
    this.name = 'ShareUnavailableError'
  }
}

type Deps = {
  download: typeof resumesApi.download
  isSharingAvailable: () => Promise<boolean>
  share: (uri: string, options: Sharing.SharingOptions) => Promise<void>
}

const nativeDeps: Deps = {
  download: (resumeId) => resumesApi.download(resumeId),
  isSharingAvailable: () => Sharing.isAvailableAsync(),
  share: (uri, options) => Sharing.shareAsync(uri, options),
}

/**
 * Downloads one resume with the signed-in user's token (sent as a header by the API
 * client), writes it into the app cache under a safe name, and opens the system
 * share sheet, from which the user can open or send it.
 */
export async function downloadAndShare(
  resume: Pick<ResumeResponse, 'id' | 'fileName' | 'fileType'>,
  deps: Deps = nativeDeps,
): Promise<{ name: string; uri: string }> {
  if (!(await deps.isSharingAvailable())) {
    throw new ShareUnavailableError()
  }

  const downloaded = await deps.download(resume.id)
  // The header carries the stored name, which may still be the upload-encoded form.
  const name = safeDownloadName(displayFileName(downloaded.fileName ?? resume.fileName), resume.fileType)

  const directory = new Directory(Paths.cache, 'resumes')
  directory.create({ idempotent: true, intermediates: true })
  const file = new File(directory, name)
  if (file.exists) {
    file.delete()
  }
  file.create()
  file.write(downloaded.bytes)

  await deps.share(file.uri, {
    mimeType: mimeForFileType(resume.fileType),
    UTI: utiForFileType(resume.fileType),
    dialogTitle: name,
  })
  return { name, uri: file.uri }
}
