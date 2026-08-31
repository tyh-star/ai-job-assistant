package aijob.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class EditedTailorEntry {

    private String canonicalEntryKey;
    private List<EditedTailorChange> nodes = new ArrayList<>();
}
