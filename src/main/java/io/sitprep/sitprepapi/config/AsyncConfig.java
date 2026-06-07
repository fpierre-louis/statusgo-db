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

    // Pool sizes sized for the 512 MB Heroku Basic dyno: each idle thread
    // costs ~512 KB stack. core=2, max=8, queue=100 gives meaningful burst
    // headroom without pushing past R14 (the 2026-06-07 deploy hit 605M with
    // max=16). Bump these in tandem with Hikari pool when the dyno tier rises.
    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(2);
        e.setMaxPoolSize(8);
        e.setQueueCapacity(100);
        e.setThreadNamePrefix("sitprep-async-");
        e.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        e.initialize();
        return e;
    }
}
