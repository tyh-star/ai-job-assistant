package aijob.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aijob.storage")
public class StorageProperties {

    /**
     * Root directory for resume uploads and tailored files.
     * Relative values are resolved against the process working directory.
     */
    private String root = "uploads";

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }
}
