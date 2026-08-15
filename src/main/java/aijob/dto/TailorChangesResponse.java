package aijob.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
public class TailorChangesResponse {

    @JsonPropertyDescription("Paragraph-level edits to apply. Empty if no safe edits exist.")
    public List<TailorChange> changes = new ArrayList<>();
}
