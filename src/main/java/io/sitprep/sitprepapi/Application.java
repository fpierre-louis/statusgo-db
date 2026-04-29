package io.sitprep.sitprepapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableAsync} activates the {@code @Async} annotation on
 * {@link io.sitprep.sitprepapi.service.LastActivityService#writeAsync},
 * so the per-request "last active" bump runs on a worker thread instead
 * of blocking the HTTP response. Spring uses a SimpleAsyncTaskExecutor
 * by default; that's fine for our low call volume.
 *
 * <p>{@code @EnableScheduling} activates {@code @Scheduled} methods. The
 * first user is {@code AlertIngestService} which polls NWS active alerts
 * every 5 minutes and caches the result so the FE doesn't fan out to
 * NWS once per page-load. See docs/ALERTS_INTEGRATION.md "Build order"
 * step 5.</p>
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

}
