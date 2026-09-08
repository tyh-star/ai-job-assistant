package aijob.service;

import aijob.dto.builder.NormalizedResume;

import java.nio.file.Path;

/** Writes a single-column ATS-format DOCX from a {@link NormalizedResume} -- the SAME
 * normalized model the preview endpoint renders, so the file a user downloads can never
 * disagree with what they previewed. See NormalizedResume's javadoc. */
public interface ResumeBuilderDocxService {

    void write(NormalizedResume resume, Path outputPath);
}
