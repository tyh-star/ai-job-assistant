package aijob.service;

import aijob.dto.PdfLayout;
import aijob.dto.ResumeStructure;

public interface ResumeStructureLlmService {

    ResumeStructure classify(PdfLayout layout, ResumeStructure ruleDraft);
}
