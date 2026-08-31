package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EntryOption {

    private String entryKey;
    private String entryLabel;
    private String entryType;
    private List<String> nodeIds = new ArrayList<>();
}
