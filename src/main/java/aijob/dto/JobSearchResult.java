package aijob.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobSearchResult {

    private String externalJobId;
    private String title;
    private String company;
    private String location;
    private String postedAt;
    private String descriptionSnippet;
    private String source;
    private String sourceUrl;
    private String publisher;
    private Boolean fullDescriptionAvailable;
    private Boolean alreadySaved;
}
