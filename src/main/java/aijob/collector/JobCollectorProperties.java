package aijob.collector;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Collector limits. Defaults are deliberately conservative: this runs on the same
 * t3.medium as MySQL and the API, and collection is off until an operator opts in.
 */
@ConfigurationProperties(prefix = "aijob.collector")
public class JobCollectorProperties {

    /** Master switch. Off on feature branches and by default in Compose. */
    private boolean enabled = false;

    private int intervalMinutes = 120;

    private int maxConcurrency = 2;

    /** Sources picked up per scheduler tick. */
    private int batchSize = 100;

    /**
     * How long a posting may stay missing from successful runs before it is expired.
     * Guards against a board briefly omitting a job.
     */
    private int expiryGraceHours = 72;

    /** Identifies this crawler to boards, together with {@link #contactEmail}. */
    private String userAgent = "AiJobAssistantCollector/1.0";

    private String contactEmail = "";

    private int requestTimeoutSeconds = 20;

    private int maxRetries = 3;

    private long retryBaseDelayMillis = 500;

    private long retryMaxDelayMillis = 8_000;

    /** Per-source cap so one huge board cannot fill the disk in a single run. */
    private int maxJobsPerSource = 2_000;

    private int maxDescriptionChars = 100_000;

    private int maxRawPayloadChars = 200_000;

    /** Postings expired for longer than this have their stored payload dropped. */
    private int rawPayloadRetentionDays = 30;

    private int runHistoryRetentionDays = 30;

    /** Touched after each successful database heartbeat; used by the container healthcheck. */
    private String heartbeatFile = "/tmp/aijob-collector-heartbeat";

    /**
     * How long one process may hold a source's collection lease before another process
     * treats it as abandoned. Must comfortably exceed the longest realistic run, or a
     * slow board would be collected twice at once; must stay finite, or a killed
     * container would block its boards forever.
     */
    private int lockTimeoutMinutes = 60;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getIntervalMinutes() {
        return intervalMinutes;
    }

    public void setIntervalMinutes(int intervalMinutes) {
        this.intervalMinutes = intervalMinutes < 1 ? 1 : intervalMinutes;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency < 1 ? 1 : maxConcurrency;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize < 1 ? 1 : batchSize;
    }

    public int getExpiryGraceHours() {
        return expiryGraceHours;
    }

    public void setExpiryGraceHours(int expiryGraceHours) {
        this.expiryGraceHours = expiryGraceHours < 0 ? 0 : expiryGraceHours;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent == null || userAgent.isBlank()
                ? "AiJobAssistantCollector/1.0"
                : userAgent.strip();
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(String contactEmail) {
        this.contactEmail = contactEmail == null ? "" : contactEmail.strip();
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds < 1 ? 1 : requestTimeoutSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries < 1 ? 1 : maxRetries;
    }

    public long getRetryBaseDelayMillis() {
        return retryBaseDelayMillis;
    }

    public void setRetryBaseDelayMillis(long retryBaseDelayMillis) {
        this.retryBaseDelayMillis = retryBaseDelayMillis < 0 ? 0 : retryBaseDelayMillis;
    }

    public long getRetryMaxDelayMillis() {
        return retryMaxDelayMillis;
    }

    public void setRetryMaxDelayMillis(long retryMaxDelayMillis) {
        this.retryMaxDelayMillis = retryMaxDelayMillis < 0 ? 0 : retryMaxDelayMillis;
    }

    public int getMaxJobsPerSource() {
        return maxJobsPerSource;
    }

    public void setMaxJobsPerSource(int maxJobsPerSource) {
        this.maxJobsPerSource = maxJobsPerSource < 1 ? 1 : maxJobsPerSource;
    }

    public int getMaxDescriptionChars() {
        return maxDescriptionChars;
    }

    public void setMaxDescriptionChars(int maxDescriptionChars) {
        this.maxDescriptionChars = maxDescriptionChars < 1 ? 1 : maxDescriptionChars;
    }

    public int getMaxRawPayloadChars() {
        return maxRawPayloadChars;
    }

    public void setMaxRawPayloadChars(int maxRawPayloadChars) {
        this.maxRawPayloadChars = maxRawPayloadChars < 0 ? 0 : maxRawPayloadChars;
    }

    public int getRawPayloadRetentionDays() {
        return rawPayloadRetentionDays;
    }

    public void setRawPayloadRetentionDays(int rawPayloadRetentionDays) {
        this.rawPayloadRetentionDays = rawPayloadRetentionDays < 0 ? 0 : rawPayloadRetentionDays;
    }

    public int getRunHistoryRetentionDays() {
        return runHistoryRetentionDays;
    }

    public void setRunHistoryRetentionDays(int runHistoryRetentionDays) {
        this.runHistoryRetentionDays = runHistoryRetentionDays < 1 ? 1 : runHistoryRetentionDays;
    }

    public String getHeartbeatFile() {
        return heartbeatFile;
    }

    public void setHeartbeatFile(String heartbeatFile) {
        this.heartbeatFile = heartbeatFile == null || heartbeatFile.isBlank()
                ? "/tmp/aijob-collector-heartbeat"
                : heartbeatFile.strip();
    }

    public int getLockTimeoutMinutes() {
        return lockTimeoutMinutes;
    }

    public void setLockTimeoutMinutes(int lockTimeoutMinutes) {
        this.lockTimeoutMinutes = lockTimeoutMinutes < 1 ? 1 : lockTimeoutMinutes;
    }
}
