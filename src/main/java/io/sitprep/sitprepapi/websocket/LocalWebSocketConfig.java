package io.sitprep.sitprepapi.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@Profile({"default","dev","local"})
@EnableWebSocketMessageBroker
public class LocalWebSocketConfig implements WebSocketMessageBrokerConfigurer {
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Now relies on the global SecurityConfig to provide AllowCredentials: true.
        // We only specify the allowed origins for the /ws endpoint here.
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("http://localhost:3000","http://127.0.0.1:3000")
                .withSockJS();
    }
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic","/queue");
    }
}