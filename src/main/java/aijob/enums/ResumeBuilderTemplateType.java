package aijob.enums;

/**
 * The template type chosen first, when a draft is created. It only suggests a
 * starting section order, suggested empty professional-link types, and display
 * titles -- it never binds the form to a school, major, country, or profession,
 * never deletes filled entries when the user switches templates, and never
 * changes which fields exist. {@link #GENERIC_ATS} is the default recommendation
 * (see the resume-builder create-draft form).
 */
public enum ResumeBuilderTemplateType {
    STUDENT,
    INTERNSHIP,
    EXPERIENCED,
    CAREER_CHANGE,
    TECHNICAL,
    GENERIC_ATS
}
