package io.sitprep.sitprepapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Bounded executor for {@code @Async} methods.
 *
 * <p>Without this bean Spring falls back to {@link org.springframework.core.task.SimpleAsyncTaskExecutor},
 * which spawns a fresh thread for every submission (unbounded). A burst of
 * FCM dispatches or notification fan-outs could exhaust the dyno's thread
 * budget. This pool caps concurrency and applies caller-runs backpressure
 * when the queue saturates.</p>
 *
 * <p>Spring picks up the bean named {@code taskExecutor} as the default
 * executor for {@code @Async} method invocations.</p>
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(4);
        e.setMaxPoolSize(16);
        e.setQueueCapacity(200);
        e.setThreadNamePrefix("sitprep-async-");
        e.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        e.initialize();
        return e;
    }
}
