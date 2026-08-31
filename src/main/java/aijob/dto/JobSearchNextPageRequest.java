package aijob.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class JobSearchNextPageRequest {

    @JsonProperty("expectedPage")
    private int expectedPage;

    public JobSearchNextPageRequest() {
    }

    public JobSearchNextPageRequest(int expectedPage) {
        this.expectedPage = expectedPage;
    }

    public int getExpectedPage() {
        return expectedPage;
    }

    public void setExpectedPage(int expectedPage) {
        this.expectedPage = expectedPage;
    }
}
