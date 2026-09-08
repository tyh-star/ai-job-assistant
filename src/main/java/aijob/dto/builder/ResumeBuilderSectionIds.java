package aijob.dto.builder;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The fixed, well-known section ids a draft's {@code sectionOrder}/{@code
 * sectionEnabled} can reference, plus the {@code CUSTOM:} prefix a custom section's own
 * id is namespaced under so a custom section can never collide with (or spoof) a fixed
 * one. Shared by {@link ResumeBuilderDraftContent#normalize()}, the validator, and the
 * normalizer -- the one place all three agree on what a "section" is.
 * <p>
 * Deliberately excludes basic info and job target/career summary -- those are always
 * present header content, not optional repeatable sections, and are never toggled or
 * reordered.
 */
public final class ResumeBuilderSectionIds {

    public static final String SKILLS = "SKILLS";
    public static final String EXPERIENCE = "EXPERIENCE";
    public static final String EDUCATION = "EDUCATION";
    public static final String PROJECTS = "PROJECTS";
    public static final String VOLUNTEER = "VOLUNTEER";
    public static final String CERTIFICATIONS = "CERTIFICATIONS";
    public static final String LANGUAGES = "LANGUAGES";
    public static final String LEADERSHIP = "LEADERSHIP";
    public static final String PUBLICATIONS = "PUBLICATIONS";
    public static final String HOBBIES = "HOBBIES";

    public static final String CUSTOM_PREFIX = "CUSTOM:";

    /** Fixed section ids in a sensible default order for a brand new draft. */
    public static final List<String> DEFAULT_ORDER = List.of(
            SKILLS, EXPERIENCE, EDUCATION, PROJECTS, VOLUNTEER,
            CERTIFICATIONS, LANGUAGES, LEADERSHIP, PUBLICATIONS, HOBBIES
    );

    public static final Set<String> FIXED_IDS = new LinkedHashSet<>(DEFAULT_ORDER);

    public static boolean isCustomSectionRef(String sectionId) {
        return sectionId != null && sectionId.startsWith(CUSTOM_PREFIX);
    }

    public static String customSectionRef(String customSectionId) {
        return CUSTOM_PREFIX + customSectionId;
    }

    private ResumeBuilderSectionIds() {
    }
}
