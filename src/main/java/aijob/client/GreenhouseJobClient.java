package aijob.client;

import java.util.List;

public interface GreenhouseJobClient {

    GreenhouseBoardSnapshot fetchBoard(String boardToken);

    record GreenhouseBoardSnapshot(
            String companyName,
            List<GreenhouseJobPosting> jobs
    ) {
    }

    record GreenhouseJobPosting(
            String externalJobId,
            String title,
            String location,
            String jobUrl,
            String htmlContent
    ) {
    }
}
