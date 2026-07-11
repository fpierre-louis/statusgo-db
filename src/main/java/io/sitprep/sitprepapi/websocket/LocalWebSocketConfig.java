package io.sitprep.sitprepapi.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@Profile({"default","dev","local","test"})
@EnableWebSocketMessageBroker
public class LocalWebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Now relies on the global SecurityConfig to provide AllowCredentials: true.
        // We only specify the allowed origins for the /ws endpoint here.
        // capacitor://localhost (iOS) + https://localhost (Android) are
        // the native-app shell origins — included so a BE running under
        // a non-prod profile (default/dev/local/test) still accepts the
        // native apps' SockJS handshake instead of 403-ing it.
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(
                        "http://localhost:3000", "http://127.0.0.1:3000",
                        "capacitor://localhost", "https://localhost")
                .withSockJS();
    }
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        // Match prod: enable STOMP-level broker heartbeats (10s/10s) so the
        // client's 15s heartbeats are honored and dead sockets are detected
        // instead of lingering. SimpleBroker heartbeats require a scheduler.
        registry.enableSimpleBroker("/topic","/queue")
                .setHeartbeatValue(new long[]{10000L, 10000L})
                .setTaskScheduler(heartbeatScheduler());
    }

    private ThreadPoolTaskScheduler heartbeatScheduler;

    private synchronized ThreadPoolTaskScheduler heartbeatScheduler() {
        if (heartbeatScheduler == null) {
            ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
            s.setPoolSize(1);
            s.setThreadNamePrefix("ws-hb-");
            s.setRemoveOnCancelPolicy(true);
            s.initialize();
            heartbeatScheduler = s;
        }
        return heartbeatScheduler;
    }
}