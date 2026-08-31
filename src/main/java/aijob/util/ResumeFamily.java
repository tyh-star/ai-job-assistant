package aijob.util;

import aijob.entity.Resume;
import aijob.enums.ResumeOrigin;
import aijob.exception.ResumeNotFoundException;
import aijob.mapper.ResumeMapper;

import java.util.LinkedHashSet;
import java.util.Set;

public final class ResumeFamily {

    private ResumeFamily() {
    }

    public static Resume requireOwned(ResumeMapper resumeMapper, Long resumeId, Long userId) {
        Resume resume = resumeMapper.findById(resumeId);
        if (resume == null || !userId.equals(resume.getUserId())) {
            throw new ResumeNotFoundException("Resume not found");
        }
        return resume;
    }

    public static Resume uploadRoot(ResumeMapper resumeMapper, Resume resume, Long userId) {
        Resume current = resume;
        Set<Long> seen = new LinkedHashSet<>();
        while (current != null
                && isTemplate(current)
                && current.getParentResumeId() != null) {
            if (current.getId() != null && !seen.add(current.getId())) {
                break;
            }
            Resume parent = resumeMapper.findById(current.getParentResumeId());
            if (parent == null || !userId.equals(parent.getUserId())) {
                throw new ResumeNotFoundException("Resume not found");
            }
            current = parent;
        }
        return current;
    }

    private static boolean isTemplate(Resume resume) {
        return resume.getOrigin() != null
                && ResumeOrigin.TEMPLATE.name().equalsIgnoreCase(resume.getOrigin());
    }
}
