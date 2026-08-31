package aijob.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

    @Bean
    public StoragePathResolver storagePathResolver(
            StorageProperties properties) {
        return StoragePathResolver.forRoot(properties.getRoot());
    }
}
