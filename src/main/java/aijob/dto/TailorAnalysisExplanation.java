package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TailorAnalysisExplanation {

    private List<String> strengths = new ArrayList<>();
    private List<String> gaps = new ArrayList<>();
    private List<String> nextActions = new ArrayList<>();
}
