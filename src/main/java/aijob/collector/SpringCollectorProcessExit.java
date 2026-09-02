package aijob.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Closes the Spring context and then really exits the JVM.
 *
 * <p>{@code SpringApplication.exit} only computes the status and closes the context;
 * it does not terminate the process. Without the explicit {@code System.exit} a
 * failing one-shot command would still return 0 to the shell -- and a command that
 * merely stopped doing work would sit there with the collector's scheduler alive,
 * which is exactly the "the container hangs" behaviour this replaces.
 */
@Component
@Profile("collector")
public class SpringCollectorProcessExit implements CollectorProcessExit {

    private final ConfigurableApplicationContext context;

    public SpringCollectorProcessExit(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void exit(int code) {
        int resolved = SpringApplication.exit(context, () -> code);
        System.exit(resolved);
    }
}
