package aijob.client;

import aijob.exception.GreenhouseBoardNotFoundException;
import aijob.exception.GreenhouseServiceException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

@Component
public class GreenhouseJobClientImpl implements GreenhouseJobClient {

    private final RestClient greenhouseRestClient;

    public GreenhouseJobClientImpl(@Qualifier("greenhouseRestClient") RestClient greenhouseRestClient) {
        this.greenhouseRestClient = greenhouseRestClient;
    }

    @Override
    public GreenhouseBoardSnapshot fetchBoard(String boardToken) {
        BoardJson board = getBoard(boardToken);
        JobsJson jobsJson = getJobs(boardToken);

        String companyName = board == null ? null : board.name;
        List<GreenhouseJobPosting> jobs = new ArrayList<>();
        if (jobsJson != null && jobsJson.jobs != null) {
            for (JobJson job : jobsJson.jobs) {
                if (job == null) {
                    continue;
                }
                jobs.add(new GreenhouseJobPosting(
                        job.id == null ? null : String.valueOf(job.id),
                        job.title,
                        job.location == null ? null : job.location.name,
                        job.absoluteUrl,
                        job.content
                ));
            }
        }
        return new GreenhouseBoardSnapshot(companyName, jobs);
    }

    @Override
    public GreenhouseJobPosting fetchJob(String boardToken, String jobId) {
        JobJson job = execute(() -> greenhouseRestClient.get()
                .uri("/{boardToken}/jobs/{jobId}", boardToken, jobId)
                .retrieve()
                .onStatus(status -> status.value() == 404, (request, response) -> {
                    throw new GreenhouseBoardNotFoundException("Greenhouse job not found");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    throw new GreenhouseServiceException("Greenhouse is temporarily unavailable");
                })
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new GreenhouseServiceException("Greenhouse is temporarily unavailable");
                })
                .body(JobJson.class));
        if (job == null) {
            return null;
        }
        return new GreenhouseJobPosting(
                job.id == null ? null : String.valueOf(job.id),
                job.title,
                job.location == null ? null : job.location.name,
                job.absoluteUrl,
                job.content
        );
    }

    private BoardJson getBoard(String boardToken) {
        return execute(() -> greenhouseRestClient.get()
                .uri("/{boardToken}", boardToken)
                .retrieve()
                .onStatus(status -> status.value() == 404, (request, response) -> {
                    throw new GreenhouseBoardNotFoundException("Greenhouse board not found");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    throw new GreenhouseServiceException("Greenhouse is temporarily unavailable");
                })
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new GreenhouseServiceException("Greenhouse is temporarily unavailable");
                })
                .body(BoardJson.class));
    }

    private JobsJson getJobs(String boardToken) {
        return execute(() -> greenhouseRestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/{boardToken}/jobs")
                        .queryParam("content", "true")
                        .build(boardToken))
                .retrieve()
                .onStatus(status -> status.value() == 404, (request, response) -> {
                    throw new GreenhouseBoardNotFoundException("Greenhouse board not found");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    throw new GreenhouseServiceException("Greenhouse is temporarily unavailable");
                })
                .onStatus(HttpStatusCode::isError, (request, response) -> {
                    throw new GreenhouseServiceException("Greenhouse is temporarily unavailable");
                })
                .body(JobsJson.class));
    }

    private <T> T execute(Supplier<T> call) {
        try {
            return call.get();
        } catch (GreenhouseBoardNotFoundException | GreenhouseServiceException ex) {
            throw ex;
        } catch (ResourceAccessException | RestClientResponseException ex) {
            throw new GreenhouseServiceException(
                    "Greenhouse is temporarily unavailable",
                    ex
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class BoardJson {
        public String name;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class JobsJson {
        public List<JobJson> jobs;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class JobJson {
        public Long id;
        public String title;
        public String content;
        @JsonProperty("absolute_url")
        public String absoluteUrl;
        public LocationJson location;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class LocationJson {
        public String name;
    }
}
