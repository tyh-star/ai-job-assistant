package aijob.collector;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables scheduling only inside the collector container. The API container gets no
 * scheduler, so nothing there can trigger an outbound board read.
 */
@Configuration
@Profile("collector")
@EnableScheduling
public class CollectorSchedulingConfig {
}
