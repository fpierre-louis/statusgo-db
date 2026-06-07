// src/main/java/io/sitprep/sitprepapi/websocket/ProductionWebSocketConfig.java
package io.sitprep.sitprepapi.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.*;

import java.util.Arrays;
import java.util.stream.Stream;

@Configuration
@Profile({"prod","production"})
@EnableWebSocketMessageBroker
public class ProductionWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${app.allowed-origins:https://sitprep.app,https://statusready.app}")
    private String[] allowedOrigins;

    /**
     * Capacitor native shells. The iOS WKWebView serves the FE bundle
     * from {@code capacitor://localhost}; Android (androidScheme=https)
     * from {@code https://localhost}. Both send that value as the
     * {@code Origin} header on the SockJS {@code /ws} handshake.
     *
     * <p>These are NOT optional and are appended unconditionally below
     * — without them the native apps' WebSocket handshake 403s on
     * {@code /ws/info} and the STOMP client reports
     * {@code "websocket closed 1002 Cannot connect to server"}, even
     * though plain HTTP CORS already permits the same origins (that's
     * the separate {@code SecurityConfig.corsConfigurationSource()}
     * — the WebSocket origin check does not consult it). Appending in
     * code rather than via {@code app.allowed-origins} guarantees no
     * env override can silently drop native connectivity.</p>
     */
    private static final String[] NATIVE_APP_ORIGINS = {
            "capacitor://localhost",
            "https://localhost"
    };

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = Stream.concat(
                Arrays.stream(allowedOrigins),
                Arrays.stream(NATIVE_APP_ORIGINS)
        ).toArray(String[]::new);

        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(origins)
                .withSockJS()
                // Bounded SockJS heartbeat scheduler — Spring's default is
                // pool=8, logged at boot as "sockJsScheduler[pool size = 8]".
                // Each idle scheduler thread ~512 KB stack; pool=2 is
                // sufficient for heartbeat + session cleanup at our scale
                // and saves ~3 MB on the 512 MB Heroku dyno (R14 ceiling).
                .setTaskScheduler(sockJsHeartbeatScheduler())
                .setSessionCookieNeeded(false);
        System.out.println("[WS] /ws registered (PROD) origins=" + String.join(",", origins) + " cookieNeeded=false");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic","/queue");
    }

    /**
     * Inbound STOMP frame executor. Spring's default sizes to
     * {@code 2 * cores}; on Heroku dynos {@code Runtime.availableProcessors()}
     * reports 8, so the default would allocate up to 16 threads (~8 MB
     * stacks). Pool size 1/2 is plenty for our pre-launch beta-tester
     * traffic and meaningfully cuts thread-stack memory.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.taskExecutor()
                .corePoolSize(1)
                .maxPoolSize(2)
                .queueCapacity(50);
    }

    /** Outbound STOMP frame executor — same rationale as inbound. */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor()
                .corePoolSize(1)
                .maxPoolSize(2)
                .queueCapacity(50);
    }

    /**
     * Dedicated SockJS heartbeat scheduler — wired into the endpoint
     * registration above. Kept separate from the app-wide
     * {@code taskScheduler} bean ({@link io.sitprep.sitprepapi.config.SchedulingConfig})
     * so a busy SockJS session can't starve {@code @Scheduled} tasks.
     */
    private ThreadPoolTaskScheduler sockJsHeartbeatScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(2);
        s.setThreadNamePrefix("sockjs-hb-");
        s.setRemoveOnCancelPolicy(true);
        s.initialize();
        return s;
    }
}
