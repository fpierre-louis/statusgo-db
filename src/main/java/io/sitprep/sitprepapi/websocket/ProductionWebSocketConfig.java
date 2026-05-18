// src/main/java/io/sitprep/sitprepapi/websocket/ProductionWebSocketConfig.java
package io.sitprep.sitprepapi.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
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
                .setSessionCookieNeeded(false);
        System.out.println("[WS] /ws registered (PROD) origins=" + String.join(",", origins) + " cookieNeeded=false");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic","/queue");
    }
}
