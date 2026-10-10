package aijob.rewrite.dto;

import java.util.List;

/**
 * @param unchanged        true when nothing needed improving (the file is still produced)
 * @param rejectedCount    model changes dropped by the fact check
 * @param downloadUrl      the generated DOCX
 * @param downloadFileName readable name the download is saved under
 */
public record FreeRewriteResponse(
        Long resumeId,
        String model,
        List<RewriteChangeView> changes,
        int changeCount,
        int rejectedCount,
        boolean unchanged,
        String downloadUrl,
        String downloadFileName
) {
}
