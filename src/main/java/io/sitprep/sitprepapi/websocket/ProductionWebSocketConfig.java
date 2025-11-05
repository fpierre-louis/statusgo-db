// src/main/java/io/sitprep/sitprepapi/websocket/ProductionWebSocketConfig.java
package io.sitprep.sitprepapi.websocket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@Profile({"prod","production"})
@EnableWebSocketMessageBroker
public class ProductionWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Value("${app.allowed-origins:https://sitprep.app,https://statusready.app}")
    private String[] allowedOrigins;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns(allowedOrigins)
                .withSockJS()
                .setSessionCookieNeeded(false);
        System.out.println("[WS] /ws registered (PROD) origins=" + String.join(",", allowedOrigins) + " cookieNeeded=false");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic","/queue");
    }
}