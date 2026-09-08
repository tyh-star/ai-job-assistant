package aijob.enums;

/**
 * The template type chosen first, when a draft is created. Purely a starting
 * recommendation/label shown back to the user -- it never binds the form to any
 * specific school, major, country, course, or profession, and never changes which
 * fields exist or how generation works. {@link #GENERIC_ATS} is the default
 * recommendation (see the resume-builder create-draft form).
 */
public enum ResumeBuilderTemplateType {
    STUDENT,
    INTERNSHIP,
    EXPERIENCED,
    CAREER_CHANGE,
    TECHNICAL,
    GENERIC_ATS
}
