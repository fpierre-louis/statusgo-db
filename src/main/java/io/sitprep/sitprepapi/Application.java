package io.sitprep.sitprepapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * {@code @EnableAsync} activates the {@code @Async} annotation on
 * {@link io.sitprep.sitprepapi.service.LastActivityService#writeAsync},
 * so the per-request "last active" bump runs on a worker thread instead
 * of blocking the HTTP response. The pool is sized in
 * {@link io.sitprep.sitprepapi.config.AsyncConfig}.
 *
 * <p>{@code @EnableScheduling} now lives on
 * {@link io.sitprep.sitprepapi.config.SchedulingConfig} alongside the
 * bounded {@code ThreadPoolTaskScheduler} (pool=2). Co-located so the
 * activation and pool sizing can't drift. {@code AlertIngestService}
 * remains the primary user — see docs/ALERTS_INTEGRATION.md step 5.</p>
 */
@SpringBootApplication
@EnableAsync
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

}
