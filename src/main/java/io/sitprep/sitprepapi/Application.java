package io.sitprep.sitprepapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * @EnableAsync activates the @Async annotation on
 * {@link io.sitprep.sitprepapi.service.LastActivityService#writeAsync},
 * so the per-request "last active" bump runs on a worker thread instead
 * of blocking the HTTP response. Spring uses a SimpleAsyncTaskExecutor
 * by default; that's fine for our low call volume.
 */
@SpringBootApplication
@EnableAsync
public class Application {

	public static void main(String[] args) {
		SpringApplication.run(Application.class, args);
	}

}
