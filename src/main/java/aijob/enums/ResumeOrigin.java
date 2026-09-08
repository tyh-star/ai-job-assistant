package aijob.enums;

public enum ResumeOrigin {
    UPLOAD,
    /** Internal working copy of a PDF's canonical DOCX -- deliberately hidden from the
     * main "我的简历" list (see ResumesPage.tsx's origin filter). Never reused for the
     * resume builder's output -- see {@link #GENERATED}. */
    TEMPLATE,
    /** Produced by the 通用简历生成器 (resume builder) from a user's own typed draft.
     * Unlike {@link #TEMPLATE}, a GENERATED resume is a first-class, standalone resume
     * and MUST remain visible in "我的简历" -- see ResumeServiceImpl#getMyResumes. */
    GENERATED
}
