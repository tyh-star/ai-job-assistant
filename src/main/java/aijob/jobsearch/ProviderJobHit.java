package aijob.jobsearch;

public record ProviderJobHit(
        String rawExternalJobId,
        String title,
        String company,
        String location,
        String postedAt,
        String fullDescription,
        String sourceUrl,
        String publisher,
        String source
) {
}
