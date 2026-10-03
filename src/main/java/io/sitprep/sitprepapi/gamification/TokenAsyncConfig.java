package io.sitprep.sitprepapi.gamification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Token evaluation's own executor. Deliberately NOT the shared
 * {@code taskExecutor}: that one is {@code CallerRunsPolicy}, which under load
 * runs the work on the request thread — the action that earned the token would
 * wait for its evaluation. Here a full queue DROPS the evaluation with a log
 * line. That loses nothing durable: criteria are re-read from the records, so
 * the next qualifying action evaluates again.
 */
@Configuration
public class TokenAsyncConfig {

    private static final Logger log = LoggerFactory.getLogger(TokenAsyncConfig.class);

    public static final String EXECUTOR = "tokenExecutor";

    @Bean(name = EXECUTOR)
    public ThreadPoolTaskExecutor tokenExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(1);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(200);
        ex.setThreadNamePrefix("sitprep-tokens-");
        ex.setRejectedExecutionHandler((task, pool) ->
                log.warn("token evaluation dropped: queue full ({} queued)", pool.getQueue().size()));
        ex.initialize();
        return ex;
    }
}
