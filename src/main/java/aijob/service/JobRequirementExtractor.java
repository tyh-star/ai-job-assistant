package aijob.service;

import aijob.dto.JobRequirementDraft;

import java.util.List;

public interface JobRequirementExtractor {

    List<JobRequirementDraft> extract(String jobDescription);
}
