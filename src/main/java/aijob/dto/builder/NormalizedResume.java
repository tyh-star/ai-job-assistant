package aijob.dto.builder;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * The single rendered model of a resume-builder draft -- produced ONCE by
 * {@link aijob.util.ResumeBuilderNormalizer#normalize}, then consumed by BOTH the
 * preview endpoint (serialized as-is, as {@link ResumeBuilderPreview}) and
 * {@link aijob.service.ResumeBuilderDocxService} for the actual DOCX. Preview and the
 * generated file can never disagree about which sections/entries appear or what order
 * they render in, because they are never built from the raw draft content twice by two
 * different code paths -- both start from this same object.
 * <p>
 * Every blank-omission decision (a section with no real content, an entry with no
 * facts) is made HERE, once -- neither the preview serializer nor the DOCX writer make
 * their own decision about what counts as "blank".
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NormalizedResume {

    private Header header;
    /** Null when the user entered no career summary. */
    private String summary;
    private List<NormalizedSection> sections = new ArrayList<>();
    /** Ids (from {@link ResumeBuilderSectionIds}) of sections that were enabled but had
     * no actual content, so were left out of {@link #sections} entirely. */
    private List<String> omittedSectionIds = new ArrayList<>();
    /** Non-fatal notices -- e.g. "content may not fit one page". Never blocks
     * generation by itself. */
    private List<String> warnings = new ArrayList<>();
    /** Key facts (name, a way to contact, a target title) that are still missing.
     * Informational -- never invented, never blocks generation by itself. */
    private List<String> missingKeyFacts = new ArrayList<>();
    /** False only when there is nothing at all to put in the document (no name and no
     * contact info and no sections) -- the one case generation actually refuses. */
    private boolean readyToGenerate;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Header {
        private String fullName;
        /** "City, Country" joined only from the parts the user filled in. */
        private String location;
        private String phone;
        private String email;
        private List<String> links = new ArrayList<>();
        private String targetTitle;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NormalizedSection {
        private String id;
        private String title;
        private List<NormalizedBlock> blocks = new ArrayList<>();
    }

    /** One entry within a section (one job, one project, one certificate, ...) or, for
     * skills/hobbies/languages, one grouped line. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NormalizedBlock {
        /** e.g. "Software Engineer — Example Company", "B.Sc. Computer Science — Example
         * University". Never blank for a rendered block. */
        private String heading;
        /** e.g. dates + location, right-aligned in the DOCX. Blank when nothing to
         * show. */
        private String meta;
        private List<String> lines = new ArrayList<>();
        /** True (default): each line in {@link #lines} renders as its own bullet --
         * responsibilities, features, etc. False: {@link #lines} is rendered as plain
         * paragraph(s) with no bullet -- used for the single joined skills/hobbies
         * line, where a bullet in front of "Java, Python, Go" would misleadingly imply
         * three separate list items. Decided once, here, by the normalizer -- the DOCX
         * writer never re-derives this from the section id. */
        private boolean bulletedLines = true;
    }
}
