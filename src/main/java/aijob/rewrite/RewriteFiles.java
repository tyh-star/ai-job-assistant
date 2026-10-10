package aijob.rewrite;

import aijob.entity.Resume;
import aijob.util.ResumeFileNames;

/** Readable download names for rewritten resumes, e.g. "简历_rewrite.docx". */
final class RewriteFiles {

    private RewriteFiles() {
    }

    static String downloadName(Resume resume, String suffix) {
        String display = resume == null ? "" : ResumeFileNames.displayName(resume.getFileName());
        int dot = display.lastIndexOf('.');
        String base = dot > 0 ? display.substring(0, dot) : display;
        if (base.isBlank()) {
            base = "resume";
        }
        return base + "_" + suffix + ".docx";
    }
}
