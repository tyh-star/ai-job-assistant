import * as DocumentPicker from 'expo-document-picker'
import { File } from 'expo-file-system'
import { DOCX_MIME, PDF_MIME, type PickedDocument } from './model'

/**
 * Opens the system document picker for one PDF or DOCX. Returns null when the user
 * cancels. The filter is a convenience only; the caller still validates the result.
 */
export async function pickResumeDocument(): Promise<PickedDocument | null> {
  const result = await DocumentPicker.getDocumentAsync({
    type: [PDF_MIME, DOCX_MIME],
    copyToCacheDirectory: true,
    multiple: false,
  })
  if (result.canceled || result.assets.length === 0) {
    return null
  }
  const asset = result.assets[0]
  let size: number | null | undefined = asset.size
  if (typeof size !== 'number') {
    try {
      size = new File(asset.uri).size
    } catch {
      size = null
    }
  }
  return { uri: asset.uri, name: asset.name, size, mimeType: asset.mimeType }
}
