package io.sitprep.sitprepapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Bounded {@code TaskScheduler} for {@code @Scheduled} methods.
 *
 * <p>Without an explicit bean, Spring creates a single-thread
 * {@link java.util.concurrent.ScheduledThreadPoolExecutor} for
 * {@code @Scheduled} methods, BUT if a {@code TaskScheduler} bean exists
 * elsewhere (e.g. WebSocket SockJS) Spring will reuse that one — which
 * historically defaulted to a pool of 8 threads in prod (logged as
 * {@code sockJsScheduler[pool size = 8]}). Each idle scheduler thread
 * carries a ~512 KB stack. On a 512 MB Heroku dyno that's wasteful when
 * the only periodic work is {@code AlertIngestService.scheduledPoll()}.</p>
 *
 * <p>This bean is explicitly registered against the
 * {@link ScheduledTaskRegistrar} so {@code @Scheduled} discovery binds
 * to it deterministically rather than falling through to whichever
 * scheduler Spring found first.</p>
 *
 * <p>{@code @EnableScheduling} also lives here so the activation is
 * co-located with the pool sizing (was on {@code Application}; moved to
 * keep scheduling config in one place).</p>
 */
@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    /**
     * Pool size 2 — one for AlertIngestService.scheduledPoll(), one for
     * any future @Scheduled method (idempotency-key sweep already runs
     * on its own path). Bump when adding more periodic work.
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(2);
        s.setThreadNamePrefix("sitprep-sched-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(20);
        s.setRemoveOnCancelPolicy(true);
        s.initialize();
        return s;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler());
    }
}
