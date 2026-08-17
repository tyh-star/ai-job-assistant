package aijob.dto;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@JsonClassDescription("Resume sections copied from numbered PDF layout blocks. Do not invent facts.")
public class ResumeStructureLlmResponse {

    public List<ResumeStructureLlmText> summaryBullets = new ArrayList<>();

    public List<ResumeStructureLlmText> skills = new ArrayList<>();

    public List<ResumeStructureLlmExperience> experience = new ArrayList<>();

    public List<ResumeStructureLlmProject> projects = new ArrayList<>();

    public List<ResumeStructureLlmEducation> education = new ArrayList<>();
}
