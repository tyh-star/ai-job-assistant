package aijob.service;

import aijob.dto.ResumeStructure;

import java.nio.file.Path;

public interface ResumeTemplateDocxService {

    void write(ResumeStructure structure, Path outputPath);
}
